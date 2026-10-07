# Photo Heatmap Android POC

휴대폰 사진의 **원본 EXIF GPS**를 읽어 지도 위에 **점(Point)** 또는 **히트맵(Heatmap)** 으로 표시하는 Android 네이티브 POC입니다.

## 핵심

웹 HTML 버전과 달리 Android 네이티브 앱은 다음 흐름을 사용합니다.

`사진 권한 → ACCESS_MEDIA_LOCATION → MediaStore.setRequireOriginal() → ExifInterface → GPS → MapLibre`

그래서 Android가 웹페이지에 전달할 때 가릴 수 있는 사진 위치 메타데이터를, 사용자의 정식 권한을 받아 원본에서 읽도록 구성했습니다.

## 기술 스택

- Kotlin
- Android SDK 36
- AndroidX ExifInterface 1.4.2
- MapLibre Native Android 13.6.1
- GitHub Actions 클라우드 APK 빌드

## 휴대폰에서 실행하는 가장 쉬운 방법

이 저장소는 `main` 브랜치에 코드가 올라오면 GitHub Actions가 디버그 APK를 자동 빌드하도록 구성되어 있습니다.

1. 저장소의 **Actions** 탭을 엽니다.
2. 최신 **Build Android APK** 실행을 엽니다.
3. 빌드가 성공하면 아래 **Artifacts**의 `PhotoHeatmap-debug-apk`를 다운로드합니다.
4. 압축을 풀어 `app-debug.apk`를 갤럭시에서 실행합니다.
5. Android가 묻는 **사진 접근** 및 **사진 위치정보 접근** 권한을 허용합니다.
6. 앱에서 `사진 불러오기`를 누른 뒤 `점 보기` / `히트맵`을 전환합니다.

> 디버그 APK는 Google Play 배포용이 아니라 POC 설치·시연용입니다.

## 앱 동작

- 접근 가능한 사진 최대 1,000장 검사
- `MediaStore.setRequireOriginal()`로 원본 사진 URI 요청
- `ExifInterface.latLong`으로 GPS 추출
- GPS가 있는 사진 수 / 위치정보 없는 사진 수 표시
- MapLibre 지도에서 점 보기 / 히트맵 보기 전환
- `샘플` 버튼으로 권한 없이 지도 UI 시연 가능

## 개인정보

현재 POC는 사진 파일이나 GPS 좌표를 서버로 업로드하지 않습니다. 사진 메타데이터는 기기 안에서 읽고 지도 표시용 좌표만 앱 메모리에서 사용합니다.
