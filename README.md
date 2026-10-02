# 렌터카 현황판

Firebase Auth / Realtime Database를 사용하는 차량·결제·문서 관리 웹앱이며, Capacitor로 Android 앱을 만듭니다. 공개 고객 상담은 `server/aiconsult`의 Cloud Run 함수에서 처리합니다.

## 이번 변경 적용 순서

웹 화면만 먼저 배포하면 AI 기능이 작동하지 않습니다. 서버와 설정을 먼저 적용하고 웹·APK를 배포하세요.

1. **Firebase 규칙**: `server/aiconsult/private-rules.fragment.json`의 두 경로를 기존 규칙의 `rules` 바로 아래에 추가하세요. 이 파일은 전체 규칙이 아닌 조각입니다. 루트에 `.read: true`, `.write: true` 또는 `auth != null`처럼 하위 경로 전체를 허용하는 규칙이 있으면 먼저 제거하고 기존 데이터 경로별로 권한을 부여해야 합니다. 상위에서 허용된 권한은 하위의 `false`로 취소되지 않습니다. 직원과 관리자 클라이언트 모두 `privateAiSettings`, `privateAiUsage` 읽기·쓰기가 거절되는지 확인하세요. Admin SDK만 이 경로에 접근합니다.
2. **AI 키 이전**: 구버전의 AI 설정 변경을 잠시 중단하세요. `server/aiconsult`에서 Application Default Credentials와 기존 `FIREBASE_CONFIG`를 설정하고 `node scripts/migrate-ai-keys.js`로 건수만 확인한 후 `node scripts/migrate-ai-keys.js --apply`로 이전하세요. 기존 서버 전용 키가 있으면 보존하고, 업체 데이터 아래의 구 키를 제거합니다. 과거에 브라우저로 전달된 키는 제공업체에서 교체하세요.
3. **Cloud Run**: `server/aiconsult` 폴더 전체(새 `ai-service.js` 포함)로 기존 `aiconsult` 함수를 재배포하세요. 진입점은 동일합니다. 콘솔에서 파일 하나만 붙여넣는 기존 방식은 새 모듈이 누락되므로 사용하지 마세요. 웹의 `AI_SERVICE_URL`과 `chat.html`의 URL이 실제 서비스와 같은지 확인하세요.
4. **Android 서명 — 기존 앱 설정 유지**: 저장소 Secrets에 `ANDROID_KEYSTORE_BASE64`, `ANDROID_STORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`를 설정하세요. **새 키를 생성하지 말고 기존 `apk/debug.keystore`와 같은 키를 사용해야 합니다.** 현재 설치된 앱과 같은 인증서로 release APK를 서명하면 삭제·재설치 없이 덮어쓰기 업데이트할 수 있으며 로컬 설정이 유지됩니다. 기존 키는 변경 전 커밋 `0eec7a0df12ad0eb91f4cf1e103ed95f5d1917f5`의 `apk/debug.keystore`에서 복구할 수 있습니다. 기존 비밀번호는 `android`, alias는 `androiddebugkey`입니다. 키 파일의 base64 값을 첫 번째 Secret에 저장하세요. 앞으로 키 파일은 저장소에 커밋하지 않습니다. 이 조치는 기존 설치와의 호환성을 유지하기 위한 것으로, 키가 Git 이력에 공개된 과거 노출은 해결하지 못합니다. 향후 키 교체는 지원 기기와 Android 서명키 회전 호환성을 확인한 별도 작업으로 진행해야 합니다. 기기에서 앱을 제거하지 마세요.
5. **웹과 APK**: 서버·규칙·키 이전을 확인한 뒤 이 변경을 배포하고 `APK 만들기`를 실행하세요. 빌드는 서명 Secrets가 없으면 실패하며 새로운 임시 키를 생성하지 않습니다. 배포 전에 APK 인증서가 기존 APK와 같은지 `apksigner verify --print-certs`로 확인하세요. 이번 PR은 이전 배포 APK 자체를 교체하지 않습니다.

AI 서버는 직원 호출 시 Firebase ID 토큰과 현재 업체 멤버십을 확인합니다. 관리자만 키를 변경하고 모든 직원에게는 사용 가능 여부만 돌려줍니다. 공개 상담은 허용 Origin 검사에 더해 서버 공통 카운터로 IP/사용자당 분당 12회, 업체당 분당 60회·하루 1,000회, 상담 세션당 40회로 제한합니다. Origin 검사는 인증 수단이 아니며 비브라우저 클라이언트가 위조할 수 있습니다. 이 제한은 비용 상한을 낮추는 장치이고, 제공업체의 결제 한도도 설정할 수 있습니다. 카운터는 UTC 날짜 기준입니다. 공개 상담과 직원 사진 인식이 업체 사용량 한도를 공유합니다.

## 검증

- `server/aiconsult`: `npm ci && npm test`
- 루트: `npm ci && npm test`
- Android: Secrets 설정 후 수동 workflow로 release APK 빌드·실기기 확인

같은 차량을 동시에 수정할 때의 기존 객체 교체 방식은 유지했습니다.

`vendor/purify.min.js`는 DOMPurify 3.4.16 원본이며 생성된 문서를 DOM 삽입 전에 정리합니다. 라이선스는 같은 폴더에 있습니다.
