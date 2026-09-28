/**
 * 고객 명단 구글 시트 (Apps Script)
 * ─────────────────────────────────────────────
 * AI 상담으로 받은 상담 신청·예약 요청이 "고객명단" 탭에 한 줄씩 쌓여요.
 * "문자동의명단" 탭에는 이벤트 문자 수신에 동의한 분의 이름·연락처만 자동으로 모여요.
 * (이벤트·광고 문자는 동의한 분께만 보내야 해요)
 *
 * 설치: docs/customer-chat.md 의 "고객 명단 구글 시트" 참고
 *  1. 새 구글 시트 → 확장 프로그램 → Apps Script → 이 코드를 통째로 붙여넣고 저장
 *  2. 배포 → 새 배포 → 유형 "웹 앱", 실행: 나, 액세스: 모든 사용자 → 배포 → 권한 허용
 *  3. 나온 웹 앱 주소(…/exec)를 현황판 고객 문의 관리 화면 "고객 명단 시트 주소"에 붙여넣기
 */
const SHEET_NAME = '고객명단';
const SMS_SHEET_NAME = '문자동의명단';
// [현황판 서버가 보내는 이름, 시트 칸 제목]  (functions/chat-core.js sheetRow 와 같은 순서)
const COLUMNS = [
  ['at', '접수 시각'], ['via', '경로'], ['kind', '구분'], ['rentalType', '대여 유형'], ['rentalPeriod', '기간'],
  ['startWhen', '시작 시기'], ['carClass', '희망 차종'], ['budget', '예산'], ['rentalRegion', '지역'],
  ['name', '이름'], ['phone', '연락처'], ['birthdate', '생년월일'], ['request', '요청'], ['agreeMarketing', '이벤트 문자 동의'],
];

function doPost(e) {
  let data;
  try { data = JSON.parse(e.postData.contents); } catch (err) { return reply_({ success: false, message: '잘못된 요청이에요' }); }
  const lock = LockService.getScriptLock();
  lock.waitLock(10000);
  try {
    const sheet = setup_();
    // 관리 화면의 "연결 확인" 버튼: 줄은 추가하지 않고 연결만 확인
    if (data.test) return reply_({ success: true, sheet: SpreadsheetApp.getActiveSpreadsheet().getName() });
    sheet.appendRow(COLUMNS.map(([key]) => clean_(data[key])));
    return reply_({ success: true });
  } finally {
    lock.releaseLock();
  }
}

function doGet() {
  return reply_({ success: true, message: '고객 명단 시트가 연결돼 있어요' });
}

// 처음 한 번: 탭·제목 줄·문자 동의 명단 만들기
function setup_() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  let sheet = ss.getSheetByName(SHEET_NAME);
  if (!sheet) {
    const first = ss.getSheets()[0];
    sheet = first.getLastRow() === 0 ? first.setName(SHEET_NAME) : ss.insertSheet(SHEET_NAME, 0);
    sheet.getRange(1, 1, 1, COLUMNS.length).setValues([COLUMNS.map(c => c[1])]).setFontWeight('bold').setBackground('#fff7e0');
    sheet.setFrozenRows(1);
  }
  if (!ss.getSheetByName(SMS_SHEET_NAME)) {
    const sms = ss.insertSheet(SMS_SHEET_NAME);
    sms.getRange('A1:B1').setValues([['이름', '연락처']]).setFontWeight('bold').setBackground('#e0f2fe');
    sms.setFrozenRows(1);
    // 고객명단에서 "동의"한 분만, 같은 이름·번호는 한 번만
    sms.getRange('A2').setFormula(`=IFERROR(UNIQUE(FILTER('${SHEET_NAME}'!J2:K, '${SHEET_NAME}'!N2:N="동의")), "")`);
  }
  return sheet;
}

// 수식으로 해석되지 않게 (=, +, -, @ 로 시작하면 앞에 ' 붙임)
function clean_(v) {
  const s = String(v == null ? '' : v).slice(0, 500);
  return /^[=+\-@]/.test(s) ? "'" + s : s;
}

function reply_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}
