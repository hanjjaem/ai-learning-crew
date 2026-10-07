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
- MapLibre Native Android 13.6.1 + OpenFreeMap
- GitHub Actions 클라우드 APK 빌드

## 휴대폰에서 설치하기

`main`에 코드가 올라오면 GitHub Actions가 APK를 빌드해 **Releases**에 올립니다. 휴대폰에서 아래 링크를 열면 바로 받아집니다.

**https://github.com/hanjjaem/ai-learning-crew/releases/latest/download/PhotoHeatmap.apk**

1. 위 링크로 `PhotoHeatmap.apk`를 받습니다.
2. 설치가 막히면 Play 스토어 → 프로필 → Play 프로텍트 → 설정에서 **앱 검사**를 잠시 끄고 설치한 뒤 다시 켭니다.
3. 앱을 열고 **사진 불러오기** → 사진 접근은 **모두 허용**, **사진 위치정보 접근**도 허용합니다.

> v0.2.0부터 모든 빌드를 같은 키로 서명합니다. v0.1.0을 설치했다면 한 번만 지우고 설치하세요. 이후 버전은 덮어써 설치됩니다.

## 앱 동작

- 갤러리 사진(최근 5,000장)의 원본 EXIF GPS를 읽어 히트맵 / 점으로 표시
  (`ACCESS_MEDIA_LOCATION` → `MediaStore.setRequireOriginal()` → `ExifInterface`)
- 한 번 읽은 사진은 캐시해서 다음 실행부터 빠르게 열림
- 앱으로 돌아오면 새로 찍은 사진을 자동으로 반영
- ‘선택한 사진만’ 허용 상태면 **사진 더 고르기**로 사진을 추가
- **촬영**: 카메라로 찍고, 사진에 GPS가 없으면 그 순간의 현재 위치를 사진에 기록
- 지도는 OpenFreeMap(키 없음, 한글 지명). 열리지 않으면 OpenStreetMap 타일로 자동 전환
- 확대하면 사진 점이 보이고, 점을 누르면 사진 썸네일·촬영 시각·위치 카드

## 개인정보

현재 POC는 사진 파일이나 GPS 좌표를 서버로 업로드하지 않습니다. 사진 메타데이터는 기기 안에서 읽고 지도 표시용 좌표만 앱 메모리에서 사용합니다.
