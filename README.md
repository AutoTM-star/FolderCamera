# 폴더 카메라

앱을 켜면 폴더 목록이 나오고, 폴더를 고르고 촬영하면 그 폴더에 사진이 저장되는 간단한 안드로이드 카메라 앱입니다.

- 저장 위치: 내장메모리 `Pictures/폴더이름/` (갤러리 앱에서 폴더별 앨범으로 보임)
- 지원: 안드로이드 10 이상

## GitHub에서 APK 받기 (PC에 아무것도 설치 안 해도 됨)

1. github.com 에서 **New repository** → 이름 `FolderCamera` → **Create repository**
2. 새 저장소 화면의 **uploading an existing file** 링크 클릭
3. 압축을 푼 `FolderCamera` 폴더 **안의 내용물 전부**를 끌어다 놓고 **Commit changes**
4. **Actions** 탭에서 빌드가 끝날 때까지 대기 (약 3~5분, 초록 체크 ✅ 가 뜨면 완료)
5. 저장소 첫 화면 오른쪽 **Releases** → `FolderCamera.apk` 를 폰에서 눌러 다운로드 → 설치
   (처음엔 "출처를 알 수 없는 앱 설치" 허용이 필요할 수 있음)

코드를 고쳐서 다시 올리면 자동으로 새 APK가 만들어지고, 기존 앱 위에 덮어서 업데이트할 수 있습니다.

### `.github` 폴더가 안 올라갔을 때
Mac 등에서는 점(.)으로 시작하는 폴더가 숨겨져 업로드에서 빠질 수 있습니다.
저장소에서 **Add file → Create new file** → 파일 이름에 `.github/workflows/build.yml` 입력
→ 이 프로젝트의 `.github/workflows/build.yml` 내용을 붙여넣고 **Commit** 하면 됩니다.

## (선택) Android Studio로 직접 빌드

Android Studio에서 **Open** → 이 폴더 선택 → 폰을 USB로 연결 후 ▶ 실행

## 파일 구성

- `MainActivity.kt` — 폴더 목록 / 새 폴더 만들기 / 길게 눌러 목록에서 삭제
- `CameraActivity.kt` — 카메라 미리보기, 촬영 후 선택한 폴더에 저장
- `.github/workflows/build.yml` — GitHub에서 APK 자동 빌드
