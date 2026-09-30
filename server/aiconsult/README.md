# 홈페이지 AI 상담 서버 (aiconsult) 배포하기

홈페이지 AI 상담 창(`chat.html`)이 이 서버에 대화를 보내면, 서버가 AI(Gemini 먼저, 안 되면 Grok)로 답하고
고객이 이름·연락처를 알려주면 상담 신청을 저장해 직원 메신저·푸시로 알려줍니다.
AI 키는 현황판 🤖 AI 설정에 관리자가 넣어둔 공용 키를 그대로 씁니다 (따로 넣을 필요 없음).

## 1. Cloud Run에 함수 만들기 (처음 한 번)
1. https://console.cloud.google.com/run?project=fleet-board-f2345 접속
2. 위쪽 **함수 작성**(Write a function) 또는 **서비스 만들기 → 함수** 선택
3. 설정
   - 서비스 이름: `aiconsult`
   - 리전: `asia-northeast3 (서울)`
   - 런타임: `Node.js 20`
   - 인증: **공개 액세스 허용**(Allow public access / 인증되지 않은 호출 허용)
4. **컨테이너 → 변수 및 보안 비밀 → 변수 추가**
   - 이름 `FIREBASE_CONFIG`
   - 값 (sendchatpush에 넣은 것과 같음):
     `{"projectId":"fleet-board-f2345","databaseURL":"https://fleet-board-f2345-default-rtdb.asia-southeast1.firebasedatabase.app","storageBucket":"fleet-board-f2345.firebasestorage.app"}`
5. **만들기**
6. 코드 편집 화면이 나오면
   - **함수 진입점**(Function entry point): `aiconsult`
   - `index.js` 내용 전부 지우고 이 폴더의 `index.js` 붙여넣기
   - `package.json` 내용 전부 지우고 이 폴더의 `package.json` 붙여넣기
   - **저장 후 다시 배포**
7. 배포가 끝나면 위쪽에 나오는 **URL**(`https://aiconsult-…run.app`)을 복사해서 Claude에게 알려주세요.
   → Claude가 `chat.html`의 `AI_CONSULT_URL`에 넣고 올립니다.

## 2. Firebase 규칙 (연락 완료 체크용)
Realtime Database → 규칙에서 `"generalSales"` 줄 바로 위에 한 줄 추가 → 게시
```
"inquiries": { ".write": "auth != null && root.child('companies').child($companyId).child('members').child(auth.uid).exists()" },
```

## 코드를 고쳤을 때
Cloud Run → aiconsult → **소스** 탭 → **수정 및 재배포** → `index.js` 다시 붙여넣기 → 배포.

## 동작 요약
- 받는 요청: `POST {companyId, sessionId, messages:[{role:'user'|'assistant', text}]}`
- 돌려주는 값: `{reply, submitted, firstSubmit}`
- 저장: `companies/{companyId}/inquiries/s_{sessionId}` (같은 대화에서 고치면 덮어씀)
- 처음 접수될 때만 직원 메신저에 "📞 새 상담 신청" 메시지 + 직원 폰 푸시
- 차량 재고·번호판·고객 정보는 AI에 넘기지 않음
- 막는 것: 허용된 주소(pang-rent.github.io, status-board-kr.github.io)에서만, IP당 1분 12회, 메시지 600자, 대화 40턴
