package kr.go.donggu.photoheatmap

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.HeatmapLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.heatmapIntensity
import org.maplibre.android.style.layers.PropertyFactory.heatmapOpacity
import org.maplibre.android.style.layers.PropertyFactory.heatmapRadius
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

class MainActivity : AppCompatActivity() {

    data class PhotoPoint(val name: String, val lat: Double, val lng: Double)

    private lateinit var mapView: MapView
    private lateinit var status: TextView
    private lateinit var counts: TextView
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var points: List<PhotoPoint> = emptyList()
    private var heatMode = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasPhotoPermission() && hasMediaLocationPermission()) scanPhotos()
            else status.text = "사진 접근 + 사진 위치정보 권한이 필요합니다."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(11, 16, 32))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val title = TextView(this).apply {
            text = "📍 사진 위치 히트맵"
            setTextColor(Color.WHITE)
            textSize = 22f
            setPadding(0, 0, 0, dp(4))
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "MediaStore 원본 사진 → EXIF GPS → 지도 → 점 / 히트맵"
            setTextColor(Color.rgb(159, 176, 204))
            textSize = 12f
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(subtitle)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val load = makeButton("📷 사진 불러오기")
        val sample = makeButton("🧪 샘플")
        row1.addView(load, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f).apply { marginEnd = dp(6) })
        row1.addView(sample, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        root.addView(row1)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        val pointButton = makeButton("● 점 보기")
        val heatButton = makeButton("🔥 히트맵")
        row2.addView(pointButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        row2.addView(heatButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        root.addView(row2)

        counts = TextView(this).apply {
            text = "사진 0장 · GPS 0장 · 위치 없음 0장"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, dp(12), 0, dp(2))
        }
        root.addView(counts)

        status = TextView(this).apply {
            text = "사진 접근 권한을 허용하면 원본 EXIF GPS를 읽습니다."
            setTextColor(Color.rgb(159, 176, 204))
            textSize = 12f
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(status)

        mapView = MapView(this)
        root.addView(mapView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { readyMap ->
            map = readyMap
            readyMap.setStyle(STYLE_URL) { loadedStyle ->
                style = loadedStyle
                ensureLayers(loadedStyle)
                renderPoints()
            }
            readyMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(35.129, 129.045), 11.0))
        }

        load.setOnClickListener {
            if (hasPhotoPermission() && hasMediaLocationPermission()) scanPhotos()
            else requestPermissionsForPhotos()
        }
        sample.setOnClickListener { loadSample() }
        pointButton.setOnClickListener {
            heatMode = false
            updateLayerVisibility()
            status.text = "점 보기: GPS가 있는 사진을 한 점씩 표시합니다."
        }
        heatButton.setOnClickListener {
            heatMode = true
            updateLayerVisibility()
            status.text = "히트맵: 사진이 많이 찍힌 위치일수록 강하게 표시합니다."
        }
    }

    private fun makeButton(label: String) = Button(this).apply {
        text = label
        isAllCaps = false
        gravity = Gravity.CENTER
        textSize = 12f
    }

    private fun requestPermissionsForPhotos() {
        val p = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            p += Manifest.permission.READ_MEDIA_IMAGES
            if (Build.VERSION.SDK_INT >= 34) p += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        } else {
            p += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        p += Manifest.permission.ACCESS_MEDIA_LOCATION
        permissionLauncher.launch(p.distinct().toTypedArray())
    }

    private fun hasPhotoPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            val full = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            val partial = Build.VERSION.SDK_INT >= 34 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
            full || partial
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasMediaLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun scanPhotos() {
        status.text = "MediaStore에서 원본 사진을 분석하는 중…"
        Thread {
            val resolver = contentResolver
            val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
            val found = mutableListOf<PhotoPoint>()
            var scanned = 0
            var noGps = 0
            var errors = 0

            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_TAKEN} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                while (cursor.moveToNext() && scanned < 1000) {
                    scanned++
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "photo_$id"
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    try {
                        val original = MediaStore.setRequireOriginal(uri)
                        val latLong = resolver.openInputStream(original)?.use { ExifInterface(it).latLong }
                        if (latLong != null && latLong.size >= 2) {
                            found += PhotoPoint(name, latLong[0], latLong[1])
                        } else noGps++
                    } catch (_: Exception) {
                        errors++
                    }

                    if (scanned % 25 == 0) {
                        val s = scanned
                        val g = found.size
                        runOnUiThread { status.text = "분석 중… ${s}장 확인 / GPS ${g}장" }
                    }
                }
            }

            runOnUiThread {
                points = found
                counts.text = "사진 ${scanned}장 · GPS ${found.size}장 · 위치 없음 ${noGps}장"
                status.text = when {
                    scanned == 0 -> "접근 가능한 사진이 없습니다."
                    found.isEmpty() && errors > 0 -> "GPS를 읽지 못했습니다. 원본 위치정보 권한을 확인하세요. (오류 ${errors}건)"
                    found.isEmpty() -> "접근 가능한 사진에서 GPS EXIF를 찾지 못했습니다."
                    else -> "완료: 원본 EXIF에서 GPS ${found.size}건을 읽었습니다." + if (errors > 0) " (오류 ${errors}건)" else ""
                }
                renderPoints()
            }
        }.start()
    }

    private fun loadSample() {
        val raw = listOf(
            35.1152 to 129.0414,
            35.1158 to 129.0422,
            35.1205 to 129.0378,
            35.1210 to 129.0384,
            35.1287 to 129.0413,
            35.1294 to 129.0430,
            35.1301 to 129.0426,
            35.1114 to 129.0523,
            35.1120 to 129.0532,
            35.1384 to 129.0591
        )
        points = raw.mapIndexed { i, p -> PhotoPoint("샘플_${i + 1}.jpg", p.first, p.second) }
        counts.text = "샘플 ${points.size}장 · GPS ${points.size}장 · 위치 없음 0장"
        status.text = "샘플 데이터입니다. 실제 사진은 ‘사진 불러오기’를 누르세요."
        renderPoints()
    }

    private fun ensureLayers(loadedStyle: Style) {
        if (loadedStyle.getSource(SOURCE_ID) == null) {
            loadedStyle.addSource(GeoJsonSource(SOURCE_ID, FeatureCollection.fromFeatures(emptyArray<Feature>())))
        }
        if (loadedStyle.getLayer(HEAT_ID) == null) {
            loadedStyle.addLayer(
                HeatmapLayer(HEAT_ID, SOURCE_ID).withProperties(
                    heatmapRadius(28f),
                    heatmapIntensity(1.15f),
                    heatmapOpacity(0.88f),
                    visibility(Property.NONE)
                )
            )
        }
        if (loadedStyle.getLayer(POINT_ID) == null) {
            loadedStyle.addLayer(
                CircleLayer(POINT_ID, SOURCE_ID).withProperties(
                    circleRadius(6f),
                    circleColor("#267BFF"),
                    circleStrokeWidth(1.5f),
                    circleStrokeColor("#FFFFFF"),
                    circleOpacity(0.82f),
                    visibility(Property.VISIBLE)
                )
            )
        }
        updateLayerVisibility()
    }

    private fun renderPoints() {
        val loadedStyle = style ?: return
        val source = loadedStyle.getSourceAs<GeoJsonSource>(SOURCE_ID) ?: return
        val features = points.map { p ->
            Feature.fromGeometry(Point.fromLngLat(p.lng, p.lat)).apply { addStringProperty("name", p.name) }
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
        updateLayerVisibility()

        if (points.isNotEmpty()) {
            val lat = points.map { it.lat }.average()
            val lng = points.map { it.lng }.average()
            map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), if (points.size == 1) 16.0 else 11.5), 600)
        }
    }

    private fun updateLayerVisibility() {
        val loadedStyle = style ?: return
        loadedStyle.getLayerAs<CircleLayer>(POINT_ID)?.setProperties(
            visibility(if (heatMode) Property.NONE else Property.VISIBLE)
        )
        loadedStyle.getLayerAs<HeatmapLayer>(HEAT_ID)?.setProperties(
            visibility(if (heatMode) Property.VISIBLE else Property.NONE)
        )
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroy() { mapView.onDestroy(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) { mapView.onSaveInstanceState(outState); super.onSaveInstanceState(outState) }

    companion object {
        private const val STYLE_URL = "https://demotiles.maplibre.org/style.json"
        private const val SOURCE_ID = "photo-source"
        private const val POINT_ID = "photo-points"
        private const val HEAT_ID = "photo-heat"
    }
}
