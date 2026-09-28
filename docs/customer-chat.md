# 고객 문의 채팅 설치 방법

고객이 링크로 들어와서 "카니발 이번 주말 돼요?"라고 물으면, 현황판 차량 현황을 보고 AI가 바로 답해줍니다.
상담·예약을 원하는 고객은 연락처를 남기고, 관리 화면에서 확인할 수 있어요.

| 파일 | 역할 |
|---|---|
| `chat.html` | 고객용 채팅 페이지 |
| `inquiry-admin.html` | 사장님용 관리 화면 (설정, 링크, 상담 요청, 대화 기록) |
| `functions/` | 답변 서버 (Firebase Cloud Functions) |

**고객에게 나가는 정보**: 차종, 종별, 연료, 차종별 가능 대수, 가장 빠른 반납 예정일, 관리 화면에 직접 적은 가격·업체 안내.
**나가지 않는 정보**: 차량번호, 고객 이름, 계약 금액, 입금 여부, 메모, 직원 정보.

## 1. Firebase 요금제 바꾸기 (한 번만)

서버 기능(Cloud Functions)은 Blaze(종량제) 요금제에서만 쓸 수 있어요. 소규모 사용은 무료 한도 안이라 보통 0원이에요.

1. [Firebase 콘솔](https://console.firebase.google.com/project/fleet-board-f2345/usage/details) → 요금제 수정 → **Blaze** 선택
2. 걱정되면 Google Cloud 결제 → 예산 및 알림에서 월 예산 알림(예: 5,000원)을 걸어두세요.

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

- 데이터 위치: `companies/{업체ID}/customerChat/` 아래 `settings`, `leads`(상담 요청), `sessions`(대화), `usage`(일별 사용량)
- 관리 화면에서 설정 저장이 실패하면 Firebase Realtime Database 보안 규칙이 `companies/{업체ID}` 아래 쓰기를 막고 있는 경우예요. 업체 멤버가 `customerChat` 을 읽고 쓸 수 있게 규칙을 확인해주세요.
