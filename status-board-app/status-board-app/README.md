# 차량 현황판 Android 앱

Capacitor 기반 Android 앱입니다. 웹 화면과 Firebase 데이터는 현황판 웹앱과 공유합니다. 백그라운드 위치, 푸시·로컬 알림, 위젯을 제공합니다.

## 배포

[루트 README](../../README.md)의 서버·Firebase 규칙·AI 키 이전 절차를 먼저 적용하세요. GitHub Actions의 `APK 만들기`가 루트 웹 파일을 `www`로 복사하고 Android 프로젝트를 생성합니다.

배포 키는 GitHub Secrets에서 읽습니다. **기존 앱과 같은 서명키를 사용해야 삭제 없이 업데이트하고 로컬 설정을 유지할 수 있습니다.** 빌드는 기존 배포 APK와 새 APK의 서명 인증서를 비교하고 다르면 배포를 중단합니다. 키 파일을 저장소에 추가하지 마세요.

AI 키는 서버 전용 경로에 보관하며, 앱은 로그인 토큰으로 AI 서버에 요청합니다. 직원에게는 키의 사용 가능 여부만 전달합니다.

## 로컬 개발

1. `npm ci`
2. 루트의 `index.html`, `payment.html`, `fleet-auth.js`, manifest와 아이콘을 `www`로 복사
3. 처음 한 번 `npx cap add android`
4. `node scripts/setup-android.js && node scripts/setup-widget.js`
5. `npx @capacitor/assets generate --android && npx cap sync android`
6. `npx cap open android`

로컬 debug APK는 개발 테스트용입니다. 기존 배포 앱 위에 설치하려면 서명 인증서가 같아야 하므로 배포 업데이트는 GitHub Actions를 사용하세요.
