/**
 * 고객 문의 채팅 - 순수 로직 (DB·네트워크 없이 테스트 가능한 부분)
 * ─────────────────────────────────────────────
 * 현황판 차량 데이터를 "고객에게 보여줘도 되는 요약"으로 바꾸고,
 * Claude에게 줄 안내문(system prompt)을 만듭니다.
 *
 * 고객에게 절대 나가면 안 되는 것: 차량번호, 고객 이름, 계약 금액, 입금 여부,
 * 메모, 직원 정보. 여기서 만든 요약에는 애초에 그 값들이 들어가지 않습니다.
 */

export const MAX_TEXT = 500;          // 고객 메시지 한 번 최대 글자 수
export const MAX_TURNS = 30;          // 한 대화방에서 고객이 보낼 수 있는 최대 메시지 수
export const DEFAULT_DAILY_LIMIT = 300; // 업체당 하루 최대 답변 수 (설정으로 변경 가능)

const ID_RE = /^[A-Za-z0-9_-]{4,64}$/;
export function isValidId(s) { return typeof s === 'string' && ID_RE.test(s); }

// 현황판은 차량 목록을 배열로 저장하지만, 객체로 저장된 경우도 대비
export function toList(vehicles) {
  if (!vehicles) return [];
  const arr = Array.isArray(vehicles) ? vehicles : Object.values(vehicles);
  return arr.filter(v => v && typeof v === 'object');
}

// 현황판 index.html 의 상태 규칙과 동일:
//  '대기' = 바로 가능, '준비중' = 곧 가능, 장기 구분 = 장기 계약, 그 외 = 운행(대여)중
export function availabilityOf(v, longTermBranch) {
  if (v.status === '대기') return 'available';
  if (v.status === '준비중') return 'preparing';
  if (longTermBranch && (v.type === longTermBranch || v.branch === longTermBranch)) return 'longterm';
  return 'rented';
}

function cleanDate(d) {
  return (typeof d === 'string' && /^\d{4}-\d{2}-\d{2}/.test(d)) ? d.slice(0, 10) : null;
}

/**
 * 차종별 요약. 예:
 *  [{ model:'아반떼', cls:'중형', fuel:'휘발유', available:2, preparing:0, rented:1, longterm:0, nextReturn:'2026-10-03' }]
 */
export function summarizeFleet(vehicles, { longTermBranch = '장기' } = {}) {
  const groups = new Map();
  for (const v of toList(vehicles)) {
    const model = String(v.model || '').trim() || '차종 미입력';
    const cls = String(v.cls || '').trim();
    const fuel = String(v.fuel || '').trim();
    const key = [model, cls, fuel].join('|');
    if (!groups.has(key)) {
      groups.set(key, { model, cls, fuel, available: 0, preparing: 0, rented: 0, longterm: 0, nextReturn: null });
    }
    const g = groups.get(key);
    const a = availabilityOf(v, longTermBranch);
    g[a] += 1;
    if (a === 'rented') {
      const rd = cleanDate(v.returnDate);
      if (rd && (!g.nextReturn || rd < g.nextReturn)) g.nextReturn = rd;
    }
  }
  return [...groups.values()].sort((a, b) =>
    (b.available - a.available) || a.model.localeCompare(b.model, 'ko'));
}

export function fleetText(summary, reservations = []) {
  if (!summary.length) return '(등록된 차량이 없습니다)';
  return summary.map(g => {
    const resv = reservations.filter(r => r.model && r.model === g.model).map(bookingRange);
    const spec = [g.cls, g.fuel].filter(Boolean).join(', ');
    const parts = [];
    if (g.available) parts.push(`바로 가능 ${g.available}대`);
    if (g.preparing) parts.push(`준비중(곧 가능) ${g.preparing}대`);
    if (g.rented) parts.push(`대여중 ${g.rented}대` + (g.nextReturn ? ` (가장 빠른 반납 예정 ${g.nextReturn})` : ''));
    if (g.longterm) parts.push(`장기 계약중 ${g.longterm}대`);
    return `- ${g.model}${spec ? ` (${spec})` : ''}: ${parts.join(', ') || '정보 없음'}`
      + (resv.length ? `\n    · 이미 예약 잡힌 기간(${resv.length}건): ${resv.join(', ')}` : '');
  }).join('\n');
}

export function buildSystemPrompt({ businessName, settings = {}, summary, reservations = [], today }) {
  const s = settings || {};
  const lines = [
    `당신은 "${businessName || '저희 업체'}"의 고객 문의 상담원입니다. 고객은 당근·인스타그램 등에서 링크를 타고 이 채팅에 들어왔습니다.`,
    '',
    '규칙:',
    '- 한국어로, 짧고 친절하게(보통 2~4문장) 답합니다.',
    '- 차량 가능 여부는 아래 [실시간 차량 현황]만 근거로 판단합니다.',
    '- 보유 대수나 남은 대수(예: "2대 있어요", "1대 남았어요")는 절대 말하지 않습니다. "가능해요", "그 날짜는 어려워요", "확인 후 연락드릴게요"처럼 가능 여부만 말합니다. 몇 대 있냐고 물어도 대수 대신 원하는 날짜를 물어 가능 여부로 답합니다.',
    '- 목록에 없는 차종은 없다고 단정하지 말고, 비슷한 차종을 제안하거나 담당자 확인으로 안내합니다.',
    '- 가격·조건은 아래 [가격 안내]와 [업체 안내]에 적힌 내용만 말합니다. 적혀 있지 않으면 지어내지 말고 담당자 연결을 제안합니다.',
    '- 계약 확정, 할인·가격 협상, 사고·보험 처리, 불만 접수처럼 직접 결정할 수 없는 문의는 고객의 이름과 연락처를 받아 request_callback 도구로 담당자에게 넘기고, "담당자가 곧 연락드릴게요"라고 안내합니다.',
    '- 고객이 대여를 원하면 신청을 받습니다. 한 번에 하나씩 자연스럽게 묻고, 고객이 이미 말한 것은 다시 묻지 않습니다.',
    '  · 날짜가 정해진 대여 → 예약 신청(request_booking): 차종, 시작 날짜와 시간, 반납 날짜와 시간(장기면 기간), 이름, 연락처는 꼭 받고, 대여 지역과 생년월일(보험 연령 확인용)도 물어봅니다.',
    '  · 장기렌트·견적 문의, 날짜가 아직 안 정해진 문의 → 상담 신청(request_consult): 단기/장기, 기간, 희망 시작 시기, 희망 차종·차급, 예산, 대여 지역, 이름, 연락처, 생년월일(보험 연령 확인용)을 물어봅니다.',
    '  · 이름과 연락처는 꼭 받습니다. 나머지는 고객이 모르거나 말하고 싶어 하지 않으면 빈 칸으로 두고 넘어갑니다. 생년월일은 YYYYMMDD 8자리로 바꿔 넣습니다.',
    '- 연락처를 처음 물을 때 "상담·예약 안내 연락에만 쓸게요"라고 짧게 알립니다.',
    '- 예약·상담 신청을 접수하기 바로 전에 "할인·이벤트 소식을 문자로 받아보시겠어요? (선택이에요)"라고 한 번만 묻습니다. 고객이 분명히 좋다고 한 경우에만 agreeMarketing 을 true 로, 싫다거나 대답이 애매하면 false 로 합니다. 동의를 조르지 않습니다.',
    '- 접수한 뒤에는 받은 내용을 한 줄로 확인해주고 "담당자가 확인 후 연락드릴게요"라고 안내합니다.',
    '- 예약 신청 전에 고객이 말한 날짜에 그 차종이 가능한지 [실시간 차량 현황]으로 확인하고, 어려우면 가능한 다른 차종이나 날짜를 제안합니다.',
    '- 예약은 "신청 접수"일 뿐 확정이 아닙니다. 접수 후에는 "담당자가 확인 후 연락드려 확정해드릴게요"라고 안내합니다.',
    '- 이미 예약했거나 대여 중인 고객이 날짜 변경·연장을 원하면, 원하는 새 날짜와 이름·연락처를 받아 request_callback 으로 넘기고 "담당자가 확인 후 연락드릴게요"라고 안내합니다. 연장 가능 여부를 직접 확정하지 않습니다.',
    '- 연락처를 받기 전에는 어떤 도구도 부르지 않습니다.',
    '- 차량번호, 다른 고객 정보, 내부 메모 등은 알지 못하며 말하지 않습니다.',
    '- 이 채팅의 목적과 무관한 요청(다른 주제의 글쓰기, 코드 등)은 정중히 거절합니다.',
    '- 날짜는 오늘 기준으로 이야기합니다.',
    '',
    `[오늘 날짜] ${today}`,
    '',
    '[실시간 차량 현황] (내부 참고용. 대수는 고객에게 말하지 않습니다. "바로 가능"이 지금 바로 출고 가능한 차량입니다)',
    fleetText(summary, reservations),
    '("이미 예약 잡힌 기간"은 그 차종 중 한 대가 그 기간에 예약돼 있다는 뜻입니다. 고객이 원하는 기간과 겹치면, 겹치지 않는 남은 대수로 가능 여부를 판단하세요.)',
    '',
    '[가격 안내]',
    String(s.priceGuide || '').trim() || '(등록된 가격 안내가 없습니다. 가격 문의는 담당자 연결로 안내하세요.)',
    '',
    '[업체 안내] (영업시간, 위치, 대여 조건 등)',
    String(s.extraInfo || '').trim() || '(없음)',
  ];
  if (s.phone) lines.push('', `[대표 연락처] ${String(s.phone).trim()}  (고객이 직접 전화하고 싶어하면 알려줘도 됩니다)`);
  return lines.join('\n');
}

export const CALLBACK_TOOL = {
  name: 'request_callback',
  description: '고객의 상담 요청을 담당자에게 넘깁니다. 고객이 연락처(전화번호)를 알려준 뒤에만 호출하세요.',
  strict: true,
  input_schema: {
    type: 'object',
    properties: {
      name: { type: 'string', description: '고객 이름 또는 호칭 (모르면 빈 문자열)' },
      phone: { type: 'string', description: '고객 연락처' },
      request: { type: 'string', description: '고객이 원하는 내용 한두 줄 요약 (차종, 기간, 날짜 등)' },
    },
    required: ['name', 'phone', 'request'],
    additionalProperties: false,
  },
};

export const BOOKING_TOOL = {
  name: 'request_booking',
  description: '고객의 차량 대여 예약 신청을 접수합니다. 차종, 시작 날짜, 기간, 이름, 연락처를 모두 확인한 뒤에만 호출하세요. 현황판 일정에 "예약 요청"으로 등록되고 직원에게 알림이 갑니다.',
  strict: true,
  input_schema: {
    type: 'object',
    properties: {
      name: { type: 'string', description: '고객 이름' },
      phone: { type: 'string', description: '고객 연락처' },
      model: { type: 'string', description: '원하는 차종 (현황판 차종 이름 그대로)' },
      startDate: { type: 'string', description: '대여 시작 날짜 YYYY-MM-DD' },
      startTime: { type: 'string', description: '대여 시작 시각 HH:MM (24시간, 모르면 빈 문자열)' },
      endDate: { type: 'string', description: '반납 예정 날짜 YYYY-MM-DD (장기라 정해지지 않았으면 빈 문자열)' },
      endTime: { type: 'string', description: '반납 시각 HH:MM (24시간, 모르면 빈 문자열)' },
      period: { type: 'string', description: '기간 설명 (예: 3일, 1개월, 장기 6개월)' },
      rentalType: { type: 'string', enum: ['단기대여', '장기대여'], description: '한 달 미만이면 단기대여, 한 달 이상이면 장기대여' },
      rentalRegion: { type: 'string', description: '대여 지역·동네 (모르면 빈 문자열)' },
      birthdate: { type: 'string', description: '생년월일 8자리 YYYYMMDD (보험 연령 확인용, 말하지 않으면 빈 문자열)' },
      note: { type: 'string', description: '기타 요청 (배달, 보험 등. 없으면 빈 문자열)' },
      agreeMarketing: { type: 'boolean', description: '할인·이벤트 문자 수신에 고객이 분명히 동의했으면 true, 아니면 false' },
    },
    required: ['name', 'phone', 'model', 'startDate', 'startTime', 'endDate', 'endTime', 'period', 'rentalType', 'rentalRegion', 'birthdate', 'note', 'agreeMarketing'],
    additionalProperties: false,
  },
};

export const CONSULT_TOOL = {
  name: 'request_consult',
  description: '고객의 렌트 상담 신청(장기렌트·견적·날짜 미정 문의)을 접수합니다. 이름과 연락처를 받은 뒤에만 호출하세요. 현황판 일정·직원 알림·고객 명단 시트로 들어갑니다.',
  strict: true,
  input_schema: {
    type: 'object',
    properties: {
      rentalType: { type: 'string', enum: ['단기대여', '장기대여'], description: '한 달 미만이면 단기대여, 한 달 이상이면 장기대여' },
      rentalPeriod: { type: 'string', description: '대여 기간 (예: 3일, 12개월. 모르면 "미정")' },
      startWhen: { type: 'string', description: '희망 시작 시기 (예: 10/3, 다음 달 초. 모르면 빈 문자열)' },
      carClass: { type: 'string', description: '희망 차종이나 차급 (예: 카니발, 소형 SUV. 없으면 빈 문자열)' },
      budget: { type: 'string', description: '예산 (예: 월 50만원 이하. 모르면 빈 문자열)' },
      rentalRegion: { type: 'string', description: '대여 지역·동네 (모르면 빈 문자열)' },
      name: { type: 'string', description: '고객 이름' },
      phone: { type: 'string', description: '고객 연락처' },
      birthdate: { type: 'string', description: '생년월일 8자리 YYYYMMDD (보험 연령 확인용, 말하지 않으면 빈 문자열)' },
      note: { type: 'string', description: '기타 요청 한두 줄 (없으면 빈 문자열)' },
      agreeMarketing: { type: 'boolean', description: '할인·이벤트 문자 수신에 고객이 분명히 동의했으면 true, 아니면 false' },
    },
    required: ['rentalType', 'rentalPeriod', 'startWhen', 'carClass', 'budget', 'rentalRegion', 'name', 'phone', 'birthdate', 'note', 'agreeMarketing'],
    additionalProperties: false,
  },
};

export const TOOLS = [CALLBACK_TOOL, BOOKING_TOOL, CONSULT_TOOL];

export function validDate(d, today) {
  if (typeof d !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(d)) return false;
  const t = new Date(d + 'T00:00:00Z');
  if (isNaN(t) || t.toISOString().slice(0, 10) !== d) return false;
  return !today || d >= today;
}

export function validTime(t) {
  return t === '' || (typeof t === 'string' && /^([01]\d|2[0-3]):[0-5]\d$/.test(t));
}

// '2026-10-03', '10:00' → '10/3 10시' ('10:30' 이면 '10시30분')
export function shortWhen(date, time) {
  if (!date) return '';
  const [, m, d] = date.split('-').map(Number);
  let s = `${m}/${d}`;
  if (time) {
    const [h, mi] = time.split(':').map(Number);
    s += ` ${h}시${mi ? mi + '분' : ''}`;
  }
  return s;
}

export function bookingRange(b) {
  const from = shortWhen(b.startDate, b.startTime);
  const to = b.endDate ? shortWhen(b.endDate, b.endTime) : (b.period || '');
  return to ? `${from} ~ ${to}` : from;
}

/**
 * 예약 요청 → 현황판 일정(schedules) 항목. 현황판 일정 화면이 쓰는 {title, date, repeat, memo} 모양 그대로.
 * 현황판 오늘/내일 배너는 "제목 · 메모"를 한 줄로 보여주므로 메모도 한 줄로 짧게.
 */
export function bookingSchedule(b) {
  const memo = [
    [b.name, b.phone].filter(Boolean).join(' '),
    b.endDate && b.period ? b.period : '',
    b.rentalRegion ? `지역 ${b.rentalRegion}` : '',
    b.note ? `요청: ${b.note}` : '',
    '고객채팅 접수·확정 전',
  ].filter(Boolean).join(' · ');
  return {
    title: `📅 예약요청 ${bookingRange(b)} ${b.model} · ${b.name || '고객'}`.slice(0, 100),
    date: b.startDate,
    repeat: false,
    memo,
    source: 'customerChat',
    // 예약 정보 (현황판 차량 카드의 "예약" 표시와 AI 답변에 사용)
    resvStatus: 'pending',
    resvModel: b.model,
    resvStart: b.startDate,
    resvStartTime: b.startTime || '',
    resvEnd: b.endDate || '',
    resvEndTime: b.endTime || '',
    resvPeriod: b.period || '',
  };
}

/** 현황판 일정 중 아직 끝나지 않은 고객 예약만 추림 */
export function upcomingReservations(schedules, today) {
  const out = [];
  for (const s of Object.values(schedules || {})) {
    if (!s || !s.resvStatus || !s.resvStart) continue;
    if (s.resvStatus !== 'pending' && s.resvStatus !== 'confirmed') continue;
    const last = s.resvEnd || s.resvStart;
    if (last < today && !(s.resvPeriod && !s.resvEnd)) continue; // 기간만 있는 장기는 계속 표시
    out.push({ model: s.resvModel || '', plate: s.resvPlate || '', status: s.resvStatus,
      startDate: s.resvStart, startTime: s.resvStartTime || '', endDate: s.resvEnd || '', endTime: s.resvEndTime || '', period: s.resvPeriod || '' });
  }
  return out.sort((a, b) => a.startDate.localeCompare(b.startDate));
}

export function staffAlertText(kind, x) {
  if (kind === 'booking') {
    return `📅 새 예약 요청: ${x.model} ${bookingRange(x)} · ${x.name || '고객'} ${x.phone}`;
  }
  return `📞 상담 요청: ${x.name || '고객'} ${x.phone} · ${x.request}`;
}

// 기간: 숫자만 있으면 단위를 붙임 ('12' → '12개월')
export function periodText(x) {
  const unit = x.rentalType === '단기대여' ? '일' : '개월';
  return /^\d+$/.test(x.rentalPeriod || '') ? x.rentalPeriod + unit : (x.rentalPeriod || '');
}

/**
 * 상담 신청 → 현황판 일정. 오늘 날짜로 넣어 "할 일"로 보이게.
 * via: 접수 경로 ('AI상담'. 이후 카카오톡 등 채널이 생기면 그 이름)
 */
export function consultSchedule(x, today, via) {
  const kind = x.rentalType === '단기대여' ? '단기' : '장기';
  const memo = [
    `${x.name} ${x.phone}`,
    `${x.rentalType} ${periodText(x)}`,
    x.startWhen ? `시작 ${x.startWhen}` : '',
    x.carClass ? `희망 ${x.carClass}` : '',
    x.budget ? `예산 ${x.budget}` : '',
    x.rentalRegion ? `지역 ${x.rentalRegion}` : '',
    x.userMsg ? `요청: ${x.userMsg}` : '',
    `${via} 상담신청·연락 필요`,
  ].filter(Boolean).join(' · ');
  return {
    title: `📝 상담신청 ${kind} ${periodText(x)}${x.carClass ? ' ' + x.carClass : ''} · ${x.name}`.slice(0, 100),
    date: today,
    repeat: false,
    memo,
    source: 'chatConsult',
  };
}

export function consultAlertText(x, via) {
  return `📝 ${via} 상담신청: ${x.rentalType} ${periodText(x)}`
    + [x.carClass, x.budget ? `예산 ${x.budget}` : '', x.rentalRegion].filter(Boolean).map(t => ' · ' + t).join('')
    + ` · ${x.name} ${x.phone}`;
}

/** 생년월일 8자리(YYYYMMDD), 실제 있는 날짜인지 */
export function validBirthdate(b) {
  if (!/^(19|20)\d{6}$/.test(b)) return false;
  const d = `${b.slice(0, 4)}-${b.slice(4, 6)}-${b.slice(6)}`;
  const t = new Date(d + 'T00:00:00Z');
  return !isNaN(t) && t.toISOString().slice(0, 10) === d;
}

/** 고객 명단 구글 시트 주소: 구글 Apps Script 웹 앱 주소만 허용 */
export function validSheetUrl(u) {
  return typeof u === 'string' && /^https:\/\/script\.google\.com\/macros\/s\/[A-Za-z0-9_-]{10,}\/exec$/.test(u.trim());
}

/** '2026-09-28 15:04' (한국 시간) */
export function kstStamp(now = new Date()) {
  return new Date(now.getTime() + 9 * 3600 * 1000).toISOString().slice(0, 16).replace('T', ' ');
}

/** 시트에서 같은 번호가 한 번만 모이게 010-1234-5678 모양으로 맞춤 */
export function formatPhone(p) {
  const d = String(p || '').replace(/\D/g, '');
  if (/^01\d{9}$/.test(d)) return `${d.slice(0, 3)}-${d.slice(3, 7)}-${d.slice(7)}`;
  if (/^01\d{8}$/.test(d)) return `${d.slice(0, 3)}-${d.slice(3, 6)}-${d.slice(6)}`;
  return String(p || '').trim();
}

/**
 * 고객 명단 시트 한 줄 (docs/customer-sheet.gs 의 칸 순서와 같게).
 * 이벤트 문자는 agreeMarketing 이 '동의'인 분께만 보내세요.
 * x: 상담 신청(type:'consult') 또는 예약 요청(type:'booking')
 */
export function sheetRow(x, via, now = new Date()) {
  const booking = x.type === 'booking';
  return {
    at: kstStamp(now),
    via,
    kind: booking ? '예약요청' : '상담신청',
    rentalType: x.rentalType || '',
    rentalPeriod: booking ? (x.period || '') : periodText(x),
    startWhen: booking ? bookingRange(x) : (x.startWhen || ''),
    carClass: booking ? (x.model || '') : (x.carClass || ''),
    budget: x.budget || '',
    rentalRegion: x.rentalRegion || '',
    name: x.name || '',
    phone: formatPhone(x.phone),
    birthdate: x.birthdate || '',
    request: (booking ? x.note : x.userMsg) || '',
    agreeMarketing: x.agreeMarketing ? '동의' : '미동의',
  };
}

export function validPhone(p) {
  const digits = String(p || '').replace(/\D/g, '');
  return digits.length >= 9 && digits.length <= 12;
}

export function todayKST(now = new Date()) {
  return new Date(now.getTime() + 9 * 3600 * 1000).toISOString().slice(0, 10);
}
