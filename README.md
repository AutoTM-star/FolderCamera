# 폴더 카메라

폴더를 고르고 촬영하면 그 폴더에 사진이 저장되는 간단한 안드로이드 카메라 앱입니다.
공유 현장에 참여하면 여러 사람이 찍은 사진이 관리자의 OneDrive 한 곳으로 자동으로 모입니다.

- 📁 내 폴더: 폰의 `Pictures/폴더이름/` 에만 저장
- 🏗 공유 현장: 폰의 `Pictures/현장/폴더/` 에 저장 + OneDrive `폴더카메라/현장/폴더/` 로 자동 업로드
- 줌, 음량 버튼 촬영, 터치 초점, 플래시, 삼성(기본) 카메라로 찍기
- 안드로이드 10 이상

## APK 받기
[Releases](../../releases) 에서 `FolderCamera.apk` (코드를 올리면 GitHub Actions가 자동 빌드)

## 공유 현장 설정 (관리자, 처음 한 번)

구성: 앱 → 중계 서버(Netlify, `server/` 폴더) → 관리자 OneDrive
현장 사람들은 로그인 없이 초대 링크만으로 참여하고, OneDrive 권한은 서버에만 보관됩니다.

1. **Netlify**: Add new site → Import from GitHub → 이 저장소 선택 → Deploy (설정은 `netlify.toml` 이 자동 적용)
2. **Azure 앱 등록** (portal.azure.com, 개인 계정은 Azure 무료 계정 필요)
   - Microsoft Entra ID → 앱 등록 → 새 등록
   - 지원되는 계정 유형: **개인 Microsoft 계정만**
   - 리디렉션 URI: 플랫폼 **웹**, `https://<내 사이트>.netlify.app/api/callback`
   - 인증서 및 암호 → 새 클라이언트 암호 → **값** 복사
3. **Netlify 환경 변수** (Site configuration → Environment variables) 후 다시 배포
   - `MS_CLIENT_ID` = 애플리케이션(클라이언트) ID
   - `MS_CLIENT_SECRET` = 클라이언트 암호 값
   - `ADMIN_PASSWORD` = 관리자 페이지 비밀번호
4. `https://<내 사이트>.netlify.app` → 비밀번호 입력 → **OneDrive 연결하기**
5. 현장 만들기 → **초대 메시지 복사** → 현장 사람들에게 보내기

클라이언트 암호는 만료일(최대 2년)이 있으니 만료 전에 새로 만들어 환경 변수를 바꿔 주세요.

## 파일 구성
- `app/.../MainActivity.kt` — 첫 화면 (공유 현장 / 내 폴더, 현장 참여)
- `app/.../SiteActivity.kt` — 공유 현장의 폴더 목록 (OneDrive와 동기화)
- `app/.../CameraActivity.kt` — 촬영, 저장, 업로드 예약
- `app/.../UploadWorker.kt` — 백그라운드 업로드 (인터넷 끊기면 나중에 자동 재시도)
- `server/netlify/functions/api.mjs` — 중계 서버
- `server/public/` — 관리자 페이지, 초대 페이지
