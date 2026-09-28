# 고객 문의 채팅 설치 방법

고객이 링크로 들어와서 "카니발 이번 주말 돼요?"라고 물으면, 현황판 차량 현황을 보고 AI가 바로 답해줍니다.
예약을 원하는 고객은 채팅에서 바로 예약 신청을 하고, 그 신청은 **현황판 일정에 "📅 예약요청"으로 자동 등록**돼요.
새 예약·상담 요청이 들어오면 **현황판 직원 채팅에 알림 메시지**가 올라오고 **직원 폰(앱)으로 푸시**가 가요.
관리 화면에서 **확정**을 누르고 **차량을 고르면**, 일정 제목이 "✅ 예약확정 10/3 10시 ~ 10/5 18시 카니발(34나1111) · 홍길동"으로 바뀌고 **현황판 그 차량 카드 오른쪽 끝에 "📅 예약 10/3 10시 ~ 10/5 18시"**가 떠요. **취소**를 누르면 일정과 표시가 모두 지워져요.
AI는 이미 잡힌 예약 기간도 보고 가능 여부를 답해요.
날짜가 바뀌거나 연장하면 관리 화면의 **날짜 변경**으로 고치세요. 일정 날짜·제목과 차량 카드 표시가 같이 바뀌어요. 차량 카드의 "예약" 표시는 **대여 시작 전까지만** 나오고, 출고 후에는 평소처럼 현황판 반납일자로 관리하면 돼요. 고객이 채팅으로 연장을 요청하면 AI가 상담 요청으로 넘겨요.

| 파일 | 역할 |
|---|---|
| `chat.html` | 고객용 채팅 페이지 |
| `inquiry-admin.html` | 사장님용 관리 화면 (설정, 링크, 상담 요청, 대화 기록) |
| `functions/` | 답변 서버 (Firebase Cloud Functions). `answer.js`가 모든 채널이 같이 쓰는 핵심 로직 |

**고객에게 나가는 정보**: 차종, 종별, 연료, 차종별 가능 대수, 가장 빠른 반납 예정일, 관리 화면에 직접 적은 가격·업체 안내.
**나가지 않는 정보**: 차량번호, 고객 이름, 계약 금액, 입금 여부, 메모, 직원 정보.

## 1. Firebase 요금제 확인 (한 번만)

서버 기능(Cloud Functions)은 Blaze(종량제) 요금제에서만 쓸 수 있어요. 이미 직원 채팅 푸시 서버(`sendChatPush`)를 쓰고 계셔서 **Blaze일 가능성이 높아요**. [Firebase 콘솔](https://console.firebase.google.com/project/fleet-board-f2345/usage/details)에서 확인만 해주세요.

이 배포는 `inquiry`라는 별도 묶음(codebase)으로 올라가서, 기존 `sendChatPush` 같은 다른 서버 기능은 **건드리지 않아요**.

## 2. 배포 키 등록 (한 번만)

1. Firebase 콘솔 → ⚙ 프로젝트 설정 → **서비스 계정** → "새 비공개 키 생성" → JSON 파일 다운로드
2. [Google Cloud IAM](https://console.cloud.google.com/iam-admin/iam?project=fleet-board-f2345)에서 그 서비스 계정(`firebase-adminsdk-…`)에 역할 추가:
   **Firebase 관리자**, **서비스 계정 사용자**, **Cloud Functions 관리자**
3. GitHub 저장소 → Settings → Secrets and variables → Actions → New repository secret
   이름 `FIREBASE_SERVICE_ACCOUNT`, 값은 JSON 파일 내용 전체

## 3. 서버 배포

GitHub 저장소 → **Actions** → "고객 문의 채팅 서버 배포" → **Run workflow**.
초록색 체크가 뜨면 끝이에요. `functions/` 코드를 고쳤을 때만 다시 실행하면 됩니다.

## 4. Claude 키 확인

현황판의 **🤖 AI 설정**에 Claude 키(`sk-ant-`로 시작)가 들어가 있어야 해요. 이미 넣어두셨다면 그대로 씁니다.
(Gemini 키만 있으면 채팅은 답하지 않아요.)

## 5. 켜기

1. `https://status-board-kr.github.io/inquiry-admin.html` 에 현황판 대표 계정으로 로그인
2. **가격 안내**, **업체 안내**(영업시간·위치·조건)를 적고 **고객 문의 채팅 켜기** 체크 → 저장
3. 위쪽 **고객에게 보낼 링크**를 복사해서
   - 당근 비즈프로필 → 자동응답 문구에 "차량 현황은 여기서 바로 답변드려요 👉 (링크)"
   - 인스타 프로필 링크, 인스타 자동응답(빠른 답장)에도 같은 링크

## 요금

- 답변 한 번에 Claude 사용료 몇 원 수준 (모델: `claude-sonnet-5`, 현황판 사진 인식과 같은 모델)
- 관리 화면의 **하루 최대 답변 수**(기본 300)를 넘으면 그날은 "전화로 문의해주세요"로 안내해서 요금이 튀지 않아요.

## 참고

- 데이터 위치: `companies/{업체ID}/customerChat/` 아래 `settings`, `leads`(예약·상담 요청), `sessions`(대화), `usage`(일별 사용량). 예약 요청은 `companies/{업체ID}/schedules`에도 들어가요.
- 푸시는 직원이 앱에서 알림을 켜둔 경우(`members/{uid}/pushToken`)에만 가요.
- 관리 화면에서 설정 저장이 실패하면 Firebase Realtime Database 보안 규칙이 `companies/{업체ID}` 아래 쓰기를 막고 있는 경우예요. 업체 멤버가 `customerChat` 을 읽고 쓸 수 있게 규칙을 확인해주세요.
