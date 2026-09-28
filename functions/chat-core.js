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

export function fleetText(summary) {
  if (!summary.length) return '(등록된 차량이 없습니다)';
  return summary.map(g => {
    const spec = [g.cls, g.fuel].filter(Boolean).join(', ');
    const parts = [];
    if (g.available) parts.push(`바로 가능 ${g.available}대`);
    if (g.preparing) parts.push(`준비중(곧 가능) ${g.preparing}대`);
    if (g.rented) parts.push(`대여중 ${g.rented}대` + (g.nextReturn ? ` (가장 빠른 반납 예정 ${g.nextReturn})` : ''));
    if (g.longterm) parts.push(`장기 계약중 ${g.longterm}대`);
    return `- ${g.model}${spec ? ` (${spec})` : ''}: ${parts.join(', ') || '정보 없음'}`;
  }).join('\n');
}

export function buildSystemPrompt({ businessName, settings = {}, summary, today }) {
  const s = settings || {};
  const lines = [
    `당신은 "${businessName || '저희 업체'}"의 고객 문의 상담원입니다. 고객은 당근·인스타그램 등에서 링크를 타고 이 채팅에 들어왔습니다.`,
    '',
    '규칙:',
    '- 한국어로, 짧고 친절하게(보통 2~4문장) 답합니다.',
    '- 차량 보유·가능 여부는 아래 [실시간 차량 현황]만 근거로 답합니다. 목록에 없는 차종은 "현재 보유하고 있지 않다"고 답합니다.',
    '- 가격·조건은 아래 [가격 안내]와 [업체 안내]에 적힌 내용만 말합니다. 적혀 있지 않으면 지어내지 말고 담당자 연결을 제안합니다.',
    '- 계약 확정, 할인·가격 협상, 사고·보험 처리, 불만 접수처럼 직접 결정할 수 없는 문의는 고객의 이름과 연락처를 받아 request_callback 도구로 담당자에게 넘기고, "담당자가 곧 연락드릴게요"라고 안내합니다.',
    '- 고객이 예약·상담을 원하면 연락처를 받아 request_callback 으로 넘깁니다. 연락처를 받기 전에는 도구를 부르지 않습니다.',
    '- 차량번호, 다른 고객 정보, 내부 메모 등은 알지 못하며 말하지 않습니다.',
    '- 이 채팅의 목적과 무관한 요청(다른 주제의 글쓰기, 코드 등)은 정중히 거절합니다.',
    '- 날짜는 오늘 기준으로 이야기합니다.',
    '',
    `[오늘 날짜] ${today}`,
    '',
    '[실시간 차량 현황] (차종별 대수. "바로 가능"이 지금 바로 출고 가능한 차량입니다)',
    fleetText(summary),
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

export function validPhone(p) {
  const digits = String(p || '').replace(/\D/g, '');
  return digits.length >= 9 && digits.length <= 12;
}

export function todayKST(now = new Date()) {
  return new Date(now.getTime() + 9 * 3600 * 1000).toISOString().slice(0, 10);
}
