package kr.go.donggu.photoheatmap

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.exifinterface.media.ExifInterface
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.HeatmapLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.heatmapColor
import org.maplibre.android.style.layers.PropertyFactory.heatmapIntensity
import org.maplibre.android.style.layers.PropertyFactory.heatmapOpacity
import org.maplibre.android.style.layers.PropertyFactory.heatmapRadius
import org.maplibre.android.style.layers.PropertyFactory.heatmapWeight
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    data class Photo(val id: Long, val name: String, val taken: Long, val lat: Double, val lng: Double)

    private data class CacheEntry(
        val modified: Long,
        val taken: Long,
        val lat: Double?,
        val lng: Double?,
        val name: String
    )

    // ---------- 화면 요소 ----------
    private lateinit var mapView: MapView
    private lateinit var topCard: LinearLayout
    private lateinit var countsText: TextView
    private lateinit var statusText: TextView
    private lateinit var fitButton: TextView
    private lateinit var bottomBar: LinearLayout
    private lateinit var heatTab: TextView
    private lateinit var dotTab: TextView
    private lateinit var loadButton: TextView
    private lateinit var shootButton: TextView
    private lateinit var detailCard: LinearLayout
    private lateinit var detailThumb: ImageView
    private lateinit var detailTitle: TextView
    private lateinit var detailSub: TextView
    private lateinit var detailOpen: TextView

    // ---------- 상태 ----------
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var usedFallbackStyle = false
    private var photos: List<Photo> = emptyList()
    private var heatMode = true
    private var didInitialFit = false
    private var photoDenials = 0

    private val io = Executors.newSingleThreadExecutor()
    private val scanning = AtomicBoolean(false)
    private var lastScanAt = 0L
    private val ui = Handler(Looper.getMainLooper())
    private val hideStatus = Runnable { statusText.visibility = View.GONE }

    private var captureUri: Uri? = null
    @Volatile private var captureLocation: Location? = null

    // ---------- 권한 / 촬영 ----------
    private val photoPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshButtons()
            if (hasPhotoPermission()) {
                photoDenials = 0
                if (!hasMediaLocationPermission()) {
                    showStatus("사진 위치정보 권한이 꺼져 있어요. 눌러서 설정에서 허용해 주세요.", sticky = true) { openAppSettings() }
                }
                scanPhotos()
            } else {
                photoDenials++
                showStatus("사진 접근을 허용해야 지도에 표시할 수 있어요.")
            }
        }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { launchCamera() }

    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val uri = captureUri ?: return@registerForActivityResult
            captureUri = null
            if (ok) handleCaptured(uri)
            else {
                io.execute { runCatching { contentResolver.delete(uri, null, null) } }
                showStatus("촬영을 취소했어요.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(SURFACE, SURFACE)
        )
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        captureUri = savedInstanceState?.let { BundleCompat.getParcelable(it, KEY_CAPTURE, Uri::class.java) }

        buildUi()

        mapView.onCreate(savedInstanceState)
        mapView.addOnDidFailLoadingMapListener { _ ->
            if (!usedFallbackStyle) {
                usedFallbackStyle = true
                map?.setStyle(Style.Builder().fromJson(OSM_RASTER_STYLE)) { onStyleLoaded(it) }
            }
        }
        mapView.getMapAsync { m ->
            map = m
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(35.13, 129.05), 11.0))
            m.addOnMapClickListener { onMapTap(it) }
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { onStyleLoaded(it) }
            positionOverlays()
        }

        refreshButtons()
        updateCounts(0, 0)
    }

    // =========================================================
    // 화면 구성
    // =========================================================
    private fun buildUi() {
        val root = FrameLayout(this)
        mapView = MapView(this)
        root.addView(mapView, FrameLayout.LayoutParams(MATCH, MATCH))

        // 상단 카드: 제목 · 집계 · 상태
        topCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(SURFACE, 18f)
            elevation = dp(6).toFloat()
            setPadding(dp(16), dp(12), dp(12), dp(12))
        }
        val headRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val headText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headText.addView(label("사진 위치 히트맵", 17f, INK, bold = true))
        countsText = label("", 13f, MUTED)
        headText.addView(countsText)
        headRow.addView(headText, LinearLayout.LayoutParams(0, WRAP, 1f))
        fitButton = smallPill("전체 보기").apply { setOnClickListener { fitAll() } }
        headRow.addView(fitButton)
        topCard.addView(headRow)
        statusText = label("", 13f, ACCENT).apply {
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        topCard.addView(statusText)
        root.addView(topCard, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP).apply {
            setMargins(dp(12), dp(12), dp(12), 0)
        })

        // 점을 눌렀을 때 뜨는 사진 카드
        detailCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(SURFACE, 18f)
            elevation = dp(6).toFloat()
            setPadding(dp(10), dp(10), dp(10), dp(10))
            visibility = View.GONE
        }
        detailThumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(SURFACE_ALT, 12f)
            clipToOutline = true
        }
        detailCard.addView(detailThumb, LinearLayout.LayoutParams(dp(68), dp(68)))
        val detailText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        detailTitle = label("", 15f, INK, bold = true)
        detailSub = label("", 12.5f, MUTED).apply { maxLines = 2 }
        detailText.addView(detailTitle)
        detailText.addView(detailSub)
        detailCard.addView(detailText, LinearLayout.LayoutParams(0, WRAP, 1f))
        val detailActions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        detailOpen = smallPill("사진 보기")
        val detailClose = smallPill("닫기").apply { setOnClickListener { hideDetail() } }
        detailActions.addView(detailOpen)
        detailActions.addView(detailClose, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) })
        detailCard.addView(detailActions)
        root.addView(detailCard, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply {
            setMargins(dp(12), 0, dp(12), dp(96))
        })

        // 하단 도크: 보기 전환 · 불러오기 · 촬영
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(SURFACE)
            elevation = dp(8).toFloat()
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(SURFACE_ALT, 14f)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        heatTab = segTab("히트맵").apply { setOnClickListener { setMode(true) } }
        dotTab = segTab("점").apply { setOnClickListener { setMode(false) } }
        seg.addView(heatTab)
        seg.addView(dotTab)
        bottomBar.addView(seg)
        bottomBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        loadButton = pill("사진 불러오기", filled = false).apply { setOnClickListener { onLoadClicked() } }
        shootButton = pill("촬영", filled = true).apply { setOnClickListener { onShootClicked() } }
        bottomBar.addView(loadButton)
        bottomBar.addView(shootButton, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        root.addView(bottomBar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            (topCard.layoutParams as ViewGroup.MarginLayoutParams).topMargin = bars.top + dp(10)
            topCard.requestLayout()
            bottomBar.setPadding(dp(12) + bars.left, dp(10), dp(12) + bars.right, dp(10) + bars.bottom)
            positionOverlays()
            insets
        }
        updateTabs()
    }

    /** 하단 도크 높이에 맞춰 사진 카드와 지도 표기(로고·저작권) 위치를 올린다. */
    private fun positionOverlays() {
        bottomBar.post {
            val barH = bottomBar.height
            (detailCard.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = barH + dp(10)
            detailCard.requestLayout()
            map?.uiSettings?.apply {
                setLogoMargins(dp(10), 0, 0, barH + dp(8))
                setAttributionMargins(dp(100), 0, 0, barH + dp(8))
                setCompassMargins(0, topCard.bottom + dp(10), dp(14), 0)
            }
        }
    }

    // =========================================================
    // 지도
    // =========================================================
    private fun onStyleLoaded(s: Style) {
        style = s
        if (s.getSource(SOURCE_ID) == null) {
            s.addSource(GeoJsonSource(SOURCE_ID, FeatureCollection.fromFeatures(emptyArray<Feature>())))
        }
        if (s.getLayer(HEAT_ID) == null) {
            s.addLayer(
                HeatmapLayer(HEAT_ID, SOURCE_ID).withProperties(
                    heatmapWeight(1f),
                    heatmapIntensity(
                        Expression.interpolate(
                            Expression.linear(), Expression.zoom(),
                            Expression.stop(5, 0.6f), Expression.stop(15, 1.6f)
                        )
                    ),
                    heatmapRadius(
                        Expression.interpolate(
                            Expression.linear(), Expression.zoom(),
                            Expression.stop(5, 10f), Expression.stop(12, 22f), Expression.stop(16, 42f)
                        )
                    ),
                    heatmapOpacity(
                        Expression.interpolate(
                            Expression.linear(), Expression.zoom(),
                            Expression.stop(13, 0.9f), Expression.stop(17, 0.55f)
                        )
                    ),
                    heatmapColor(
                        Expression.interpolate(
                            Expression.linear(), Expression.heatmapDensity(),
                            Expression.stop(0, Expression.rgba(47, 91, 211, 0)),
                            Expression.stop(0.15, Expression.rgba(47, 91, 211, 0.55)),
                            Expression.stop(0.35, Expression.rgb(23, 179, 154)),
                            Expression.stop(0.6, Expression.rgb(244, 196, 48)),
                            Expression.stop(0.8, Expression.rgb(240, 122, 42)),
                            Expression.stop(1, Expression.rgb(224, 50, 47))
                        )
                    )
                )
            )
        }
        if (s.getLayer(POINT_ID) == null) {
            s.addLayer(
                CircleLayer(POINT_ID, SOURCE_ID).withProperties(
                    circleRadius(
                        Expression.interpolate(
                            Expression.linear(), Expression.zoom(),
                            Expression.stop(8, 4f), Expression.stop(16, 8f)
                        )
                    ),
                    circleColor(ACCENT_HEX),
                    circleStrokeColor("#FFFFFF"),
                    circleStrokeWidth(2f),
                    circleOpacity(0.92f)
                )
            )
        }
        if (s.getLayer(SELECT_ID) == null) {
            s.addLayer(
                CircleLayer(SELECT_ID, SOURCE_ID).withProperties(
                    circleRadius(12f),
                    circleColor("#E0322F"),
                    circleStrokeColor("#FFFFFF"),
                    circleStrokeWidth(3f),
                    visibility(Property.NONE)
                )
            )
        }
        renderPhotos()
        if (photos.isNotEmpty() && !didInitialFit) {
            didInitialFit = true
            fitAll()
        }
    }

    private fun renderPhotos() {
        val s = style ?: return
        val source = s.getSourceAs<GeoJsonSource>(SOURCE_ID) ?: return
        val features = photos.map { p ->
            Feature.fromGeometry(Point.fromLngLat(p.lng, p.lat)).apply {
                addNumberProperty("id", p.id)
                addStringProperty("name", p.name)
                addNumberProperty("taken", p.taken)
            }
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
        applyMode()
    }

    private fun setMode(heat: Boolean) {
        heatMode = heat
        applyMode()
        updateTabs()
        if (heat) showStatus("가까이 확대하면 사진 점이 보이고, 눌러서 사진을 볼 수 있어요.")
    }

    private fun applyMode() {
        val s = style ?: return
        s.getLayerAs<HeatmapLayer>(HEAT_ID)?.setProperties(
            visibility(if (heatMode) Property.VISIBLE else Property.NONE)
        )
        // 히트맵 모드에서도 충분히 확대하면 점이 나타나 눌러볼 수 있게 한다.
        s.getLayerAs<CircleLayer>(POINT_ID)?.apply {
            minZoom = if (heatMode) 14.5f else 0f
            setProperties(visibility(Property.VISIBLE))
        }
    }

    private fun onMapTap(latLng: LatLng): Boolean {
        val m = map ?: return false
        val p = m.projection.toScreenLocation(latLng)
        val r = dp(16).toFloat()
        val hit = m.queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r), POINT_ID).firstOrNull()
        if (hit == null) {
            hideDetail()
            return false
        }
        val id = hit.getNumberProperty("id")?.toLong() ?: return false
        photos.firstOrNull { it.id == id }?.let { showDetail(it) }
        return true
    }

    private fun fitAll() {
        val m = map ?: return
        if (photos.isEmpty()) {
            showStatus("아직 지도에 올린 사진이 없어요.")
            return
        }
        val pts = photos.map { LatLng(it.lat, it.lng) }
        val distinct = pts.distinctBy { "%.5f,%.5f".format(Locale.US, it.latitude, it.longitude) }
        if (distinct.size < 2) {
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(pts[0], 15.0), 700)
            return
        }
        val bounds = LatLngBounds.Builder().includes(pts).build()
        val top = topCard.bottom + dp(24)
        val bottom = bottomBar.height + dp(24)
        m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, dp(40), top, dp(40), bottom), 700)
    }

    private fun flyTo(p: Photo) {
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lng), 15.5), 800)
    }

    // =========================================================
    // 사진 카드
    // =========================================================
    private fun showDetail(p: Photo) {
        val uri = imageUri(p.id)
        detailTitle.text = formatDate(p.taken)
        detailSub.text = "${p.name}\n%.5f, %.5f".format(Locale.US, p.lat, p.lng)
        detailThumb.setImageDrawable(null)
        detailOpen.setOnClickListener {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            } catch (_: ActivityNotFoundException) {
                showStatus("사진을 열 앱을 찾지 못했어요.")
            }
        }
        detailCard.visibility = View.VISIBLE
        style?.getLayerAs<CircleLayer>(SELECT_ID)?.apply {
            setFilter(Expression.eq(Expression.get("id"), Expression.literal(p.id)))
            setProperties(visibility(Property.VISIBLE))
        }
        io.execute {
            val bmp = runCatching { contentResolver.loadThumbnail(uri, Size(320, 320), null) }.getOrNull()
            runOnUiThread { if (bmp != null) detailThumb.setImageBitmap(bmp) }
        }
    }

    private fun hideDetail() {
        detailCard.visibility = View.GONE
        style?.getLayerAs<CircleLayer>(SELECT_ID)?.setProperties(visibility(Property.NONE))
    }

    // =========================================================
    // 사진 불러오기 (MediaStore 원본 EXIF)
    // =========================================================
    private fun onLoadClicked() {
        when {
            !hasPhotoPermission() && photoDenials >= 2 -> {
                showStatus("권한 창이 더 뜨지 않아요. 설정에서 사진 접근을 허용해 주세요.", sticky = true) { openAppSettings() }
                openAppSettings()
            }
            !hasPhotoPermission() || isPartialAccess() -> {
                // '선택한 사진만' 상태에서 다시 요청하면 안드로이드가 사진 선택 화면을 다시 보여준다.
                photoPermissionLauncher.launch(photoPermissions())
            }
            else -> {
                showStatus("갤러리 전체를 다시 확인하는 중…", sticky = true)
                scanPhotos()
            }
        }
    }

    private fun scanPhotos() {
        if (!hasPhotoPermission()) return
        if (!scanning.compareAndSet(false, true)) return
        val canReadLocation = hasMediaLocationPermission()
        io.execute {
            val found = ArrayList<Photo>()
            var checked = 0
            var noGps = 0
            var failed = false
            try {
                val cache = if (canReadLocation) loadCache() else emptyMap()
                val overrides = getSharedPreferences(PREFS, MODE_PRIVATE)
                val fresh = LinkedHashMap<Long, CacheEntry>()
                var newlyRead = 0
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.Images.Media.DATE_MODIFIED
                )
                contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null,
                    "${MediaStore.Images.Media.DATE_TAKEN} DESC"
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                    val modCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                    while (c.moveToNext() && checked < MAX_PHOTOS) {
                        checked++
                        val id = c.getLong(idCol)
                        val modified = c.getLong(modCol)
                        val name = (c.getString(nameCol) ?: "photo_$id").replace('\t', ' ').replace('\n', ' ')
                        val taken = if (c.isNull(takenCol)) modified * 1000 else c.getLong(takenCol)

                        val cached = cache[id]
                        val entry = if (cached != null && cached.modified == modified) {
                            cached
                        } else {
                            newlyRead++
                            val ll = readLatLng(id, canReadLocation)
                            CacheEntry(modified, taken, ll?.get(0), ll?.get(1), name)
                        }
                        fresh[id] = entry

                        var lat = entry.lat
                        var lng = entry.lng
                        if (lat == null || lng == null) {
                            overrides.getString("loc_$id", null)?.split(',')?.let { parts ->
                                lat = parts.getOrNull(0)?.toDoubleOrNull()
                                lng = parts.getOrNull(1)?.toDoubleOrNull()
                            }
                        }
                        val la = lat
                        val ln = lng
                        if (la != null && ln != null) found += Photo(id, name, taken, la, ln) else noGps++

                        if (newlyRead > 0 && newlyRead % 40 == 0) {
                            val n = checked
                            runOnUiThread { showStatus("사진 위치 읽는 중… ${n}장 확인", sticky = true) }
                        }
                    }
                }
                if (canReadLocation) saveCache(fresh)
            } catch (_: Exception) {
                failed = true
            } finally {
                runOnUiThread {
                    scanning.set(false)
                    lastScanAt = System.currentTimeMillis()
                    applyScan(found, checked, noGps, canReadLocation, failed)
                }
            }
        }
    }

    private fun applyScan(found: List<Photo>, checked: Int, noGps: Int, canReadLocation: Boolean, failed: Boolean) {
        val before = photos.map { it.id }.toSet()
        photos = found
        updateCounts(found.size, noGps, checked)
        renderPhotos()
        refreshButtons()
        when {
            failed -> showStatus("사진을 읽다가 문제가 생겼어요. 새로고침을 다시 눌러 주세요.")
            !canReadLocation -> showStatus("사진 위치정보 권한이 꺼져 있어요. 눌러서 설정에서 허용해 주세요.", sticky = true) { openAppSettings() }
            checked == 0 -> showStatus("볼 수 있는 사진이 없어요. ‘사진 더 고르기’로 사진을 추가해 주세요.")
            found.isEmpty() -> showStatus("사진에서 위치를 찾지 못했어요. 카메라 설정의 ‘위치 태그’가 켜져 있는지 확인해 주세요.")
            else -> hideStatusNow()
        }
        if (style != null && found.isNotEmpty()) {
            if (!didInitialFit) {
                didInitialFit = true
                fitAll()
            } else if (before.isNotEmpty()) {
                val added = found.filter { it.id !in before }
                if (added.isNotEmpty()) {
                    flyTo(added.first())
                    showStatus("새 사진 ${added.size}장을 지도에 더했어요.")
                }
            }
        }
    }

    private fun readLatLng(id: Long, original: Boolean): DoubleArray? = try {
        val base = imageUri(id)
        val uri = if (original) MediaStore.setRequireOriginal(base) else base
        contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            ExifInterface(pfd.fileDescriptor).latLong
        }?.takeIf { validLatLng(it) }
    } catch (_: Exception) {
        null
    }

    private fun validLatLng(ll: DoubleArray): Boolean =
        ll.size >= 2 && ll[0] in -90.0..90.0 && ll[1] in -180.0..180.0 && !(ll[0] == 0.0 && ll[1] == 0.0)

    // =========================================================
    // 촬영 (사진에 GPS가 없으면 현재 위치를 기록)
    // =========================================================
    private fun onShootClicked() {
        if (hasLocationPermission()) launchCamera()
        else locationPermissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    private fun launchCamera() {
        captureLocation = null
        val values = ContentValues().apply {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            put(MediaStore.Images.Media.DISPLAY_NAME, "PH_$stamp.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
        }
        val uri = try {
            contentResolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        } catch (_: Exception) {
            null
        }
        if (uri == null) {
            showStatus("사진을 저장할 자리를 만들지 못했어요.")
            return
        }
        captureUri = uri
        requestCurrentLocation { captureLocation = it }
        try {
            takePictureLauncher.launch(uri)
        } catch (_: ActivityNotFoundException) {
            captureUri = null
            io.execute { runCatching { contentResolver.delete(uri, null, null) } }
            showStatus("카메라 앱을 찾지 못했어요.")
        }
    }

    private fun handleCaptured(uri: Uri) {
        showStatus("찍은 사진의 위치를 확인하는 중…", sticky = true)
        io.execute {
            val size = runCatching { contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L }.getOrDefault(0L)
            if (size <= 0L) {
                runCatching { contentResolver.delete(uri, null, null) }
                runOnUiThread { showStatus("사진이 저장되지 않았어요. 다시 찍어 주세요.") }
                return@execute
            }
            var ll = runCatching {
                contentResolver.openFileDescriptor(uri, "r")?.use { ExifInterface(it.fileDescriptor).latLong }
            }.getOrNull()?.takeIf { validLatLng(it) }
            var note = "사진 GPS로 지도에 더했어요."
            if (ll == null) {
                val loc = captureLocation ?: awaitLocation(15_000)
                if (loc != null) {
                    ll = doubleArrayOf(loc.latitude, loc.longitude)
                    runCatching {
                        contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                            ExifInterface(pfd.fileDescriptor).apply {
                                setLatLong(loc.latitude, loc.longitude)
                                saveAttributes()
                            }
                        }
                    }
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("loc_${ContentUris.parseId(uri)}", "${loc.latitude},${loc.longitude}")
                        .apply()
                    note = "현재 위치로 지도에 더했어요."
                }
            }
            val finalLl = ll
            runOnUiThread {
                if (finalLl == null) {
                    showStatus("위치를 잡지 못했어요. 위치(GPS)가 켜져 있는지 확인해 주세요.")
                } else {
                    val id = ContentUris.parseId(uri)
                    val p = Photo(id, "방금 찍은 사진", System.currentTimeMillis(), finalLl[0], finalLl[1])
                    photos = photos.filter { it.id != id } + p
                    renderPhotos()
                    flyTo(p)
                    showStatus(note)
                }
                // 갤러리 기준으로 다시 맞춘다(권한이 있을 때).
                ui.postDelayed({ scanPhotos() }, 1500)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestCurrentLocation(callback: (Location?) -> Unit) {
        if (!hasLocationPermission()) {
            callback(null)
            return
        }
        val lm = getSystemService(LocationManager::class.java)
        if (lm == null) {
            callback(null)
            return
        }
        val enabled = runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
        val recent = enabled.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (recent != null && System.currentTimeMillis() - recent.time < 60_000) {
            callback(recent)
            return
        }
        val provider = when {
            Build.VERSION.SDK_INT >= 31 && LocationManager.FUSED_PROVIDER in enabled -> LocationManager.FUSED_PROVIDER
            LocationManager.GPS_PROVIDER in enabled -> LocationManager.GPS_PROVIDER
            LocationManager.NETWORK_PROVIDER in enabled -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null || Build.VERSION.SDK_INT < 30) {
            callback(recent)
            return
        }
        try {
            lm.getCurrentLocation(provider, null, mainExecutor) { loc -> callback(loc ?: recent) }
        } catch (_: Exception) {
            callback(recent)
        }
    }

    private fun awaitLocation(timeoutMs: Long): Location? {
        val latch = CountDownLatch(1)
        var result: Location? = null
        runOnUiThread {
            requestCurrentLocation {
                result = it
                latch.countDown()
            }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return result
    }

    // =========================================================
    // 캐시 (이미 읽은 사진은 다시 읽지 않음)
    // =========================================================
    private fun loadCache(): Map<Long, CacheEntry> {
        val f = File(filesDir, CACHE_FILE)
        if (!f.exists()) return emptyMap()
        return try {
            f.readLines().mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 6) return@mapNotNull null
                val id = p[0].toLongOrNull() ?: return@mapNotNull null
                id to CacheEntry(
                    modified = p[1].toLongOrNull() ?: return@mapNotNull null,
                    taken = p[2].toLongOrNull() ?: 0L,
                    lat = p[3].toDoubleOrNull(),
                    lng = p[4].toDoubleOrNull(),
                    name = p.drop(5).joinToString("\t")
                )
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveCache(entries: Map<Long, CacheEntry>) {
        runCatching {
            val text = buildString {
                entries.forEach { (id, e) ->
                    append(id).append('\t').append(e.modified).append('\t').append(e.taken).append('\t')
                    append(e.lat?.toString() ?: "").append('\t').append(e.lng?.toString() ?: "").append('\t')
                    append(e.name).append('\n')
                }
            }
            File(filesDir, CACHE_FILE).writeText(text)
        }
    }

    // =========================================================
    // 권한
    // =========================================================
    private fun photoPermissions(): Array<String> {
        val p = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            p += Manifest.permission.READ_MEDIA_IMAGES
            if (Build.VERSION.SDK_INT >= 34) p += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        } else {
            p += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        p += Manifest.permission.ACCESS_MEDIA_LOCATION
        return p.toTypedArray()
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasFullPhotoAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_IMAGES)
        else granted(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun isPartialAccess(): Boolean =
        Build.VERSION.SDK_INT >= 34 && !hasFullPhotoAccess() &&
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    private fun hasPhotoPermission(): Boolean = hasFullPhotoAccess() || isPartialAccess()

    private fun hasMediaLocationPermission(): Boolean = granted(Manifest.permission.ACCESS_MEDIA_LOCATION)

    private fun hasLocationPermission(): Boolean =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun openAppSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    private fun refreshButtons() {
        loadButton.text = when {
            !hasPhotoPermission() -> "사진 불러오기"
            isPartialAccess() -> "사진 더 고르기"
            else -> "새로고침"
        }
    }

    // =========================================================
    // 표시 도우미
    // =========================================================
    private fun updateCounts(withGps: Int, noGps: Int, checked: Int = 0) {
        countsText.text = when {
            !hasPhotoPermission() -> "아래 ‘사진 불러오기’를 눌러 시작하세요"
            else -> buildString {
                append("위치 있는 사진 ${withGps}장 · 위치 없음 ${noGps}장")
                if (isPartialAccess()) append(" · 선택한 사진만")
                if (checked >= MAX_PHOTOS) append(" · 최근 ${MAX_PHOTOS}장")
            }
        }
    }

    private fun showStatus(msg: String, sticky: Boolean = false, onTap: (() -> Unit)? = null) {
        ui.removeCallbacks(hideStatus)
        statusText.text = msg
        statusText.visibility = View.VISIBLE
        if (onTap != null) statusText.setOnClickListener { onTap() } else statusText.setOnClickListener(null)
        if (!sticky) ui.postDelayed(hideStatus, 4500)
    }

    private fun hideStatusNow() {
        ui.removeCallbacks(hideStatus)
        statusText.visibility = View.GONE
    }

    private fun updateTabs() {
        styleTab(heatTab, heatMode)
        styleTab(dotTab, !heatMode)
    }

    private fun styleTab(tab: TextView, selected: Boolean) {
        tab.setTextColor(if (selected) INK else MUTED)
        tab.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        tab.background = if (selected) rounded(SURFACE, 11f) else null
        tab.elevation = if (selected) dp(1).toFloat() else 0f
    }

    private fun formatDate(ms: Long): String =
        if (ms <= 0) "날짜 모름" else SimpleDateFormat("yyyy.MM.dd (E) HH:mm", Locale.KOREA).format(Date(ms))

    private fun imageUri(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

    private fun label(text: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun pill(text: String, filled: Boolean) = label(text, 15f, if (filled) Color.WHITE else INK, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(12), dp(16), dp(12))
        val shape = rounded(if (filled) ACCENT else SURFACE, 14f, if (filled) null else LINE)
        background = RippleDrawable(ColorStateList.valueOf(RIPPLE), shape, null)
        isClickable = true
        isFocusable = true
    }

    private fun smallPill(text: String) = label(text, 13f, ACCENT, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = RippleDrawable(ColorStateList.valueOf(RIPPLE), rounded(ACCENT_SOFT, 12f), null)
        isClickable = true
        isFocusable = true
    }

    private fun segTab(text: String) = label(text, 14f, MUTED).apply {
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(9), dp(14), dp(9))
        isClickable = true
        isFocusable = true
    }

    private fun rounded(color: Int, radiusDp: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * resources.displayMetrics.density
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // =========================================================
    // 생명주기
    // =========================================================
    override fun onStart() { super.onStart(); mapView.onStart() }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        refreshButtons()
        // 갤러리에 새로 찍힌 사진이 있으면 자동으로 반영한다.
        if (hasPhotoPermission() && System.currentTimeMillis() - lastScanAt > 3000) scanPhotos()
    }

    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        mapView.onDestroy()
        io.shutdown()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putParcelable(KEY_CAPTURE, captureUri)
        mapView.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    companion object {
        // 키 없이 쓰는 OpenFreeMap 지도(한글 지명 표시). 실패하면 OpenStreetMap 타일로 바꾼다.
        private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        private const val OSM_RASTER_STYLE = """
            {"version":8,
             "sources":{"osm":{"type":"raster","tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
                               "tileSize":256,"maxzoom":19,"attribution":"© OpenStreetMap contributors"}},
             "layers":[{"id":"osm","type":"raster","source":"osm"}]}
        """

        private const val SOURCE_ID = "photo-source"
        private const val POINT_ID = "photo-points"
        private const val HEAT_ID = "photo-heat"
        private const val SELECT_ID = "photo-selected"

        private const val MAX_PHOTOS = 5000
        private const val CACHE_FILE = "gps_cache.tsv"
        private const val PREFS = "photo_heatmap"
        private const val KEY_CAPTURE = "capture_uri"

        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        private val INK = Color.rgb(24, 32, 28)
        private val MUTED = Color.rgb(95, 107, 100)
        private val SURFACE = Color.rgb(255, 255, 255)
        private val SURFACE_ALT = Color.rgb(238, 241, 238)
        private val LINE = Color.rgb(217, 223, 218)
        private val ACCENT = Color.rgb(13, 110, 83)
        private val ACCENT_SOFT = Color.rgb(226, 242, 236)
        private const val ACCENT_HEX = "#0D6E53"
        private const val RIPPLE = 0x22000000
    }
}
