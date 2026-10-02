// 홈페이지 AI 상담 서버 (Cloud Run 함수, 진입점: aiconsult)
//
// 홈페이지(pang-rent.github.io/main)의 AI 상담 창 → 현황판 chat.html → 여기로 대화를 보냅니다.
// - AI 키는 페이지에 두지 않고, 관리자가 현황판에 넣은 공용 키를 여기서 읽어 씁니다. 대화는 Grok 먼저(안 되면 Gemini), 신차 찻값 검색은 Gemini(구글 검색).
// - 차량 재고·번호판·고객 정보는 AI에 넘기지 않습니다 (가능 여부는 담당자가 확인해서 연락).
// - 고객이 이름·연락처를 알려주면 상담 신청으로 저장하고, 직원 메신저에 올리고, 직원 폰에 푸시를 보냅니다.
//
// 배포 때 환경 변수: FIREBASE_CONFIG (sendchatpush와 같은 값)

const functions = require('@google-cloud/functions-framework');
const admin = require('firebase-admin');
const { consumeQuota, getKeys, handleStaff } = require('./ai-service');

// ── 설정 ──
const ALLOWED_ORIGINS = [
  'https://pang-rent.github.io',
  'https://status-board-kr.github.io'
];
// 현황판(index.html)과 같은 모델 목록: Gemini 먼저, 안 되면 Grok으로 (둘 다 넣어두면 서로 예비)
const GEMINI_MODELS = ['gemini-3.1-flash-lite', 'gemini-3.5-flash-lite', 'gemini-flash-lite-latest', 'gemini-3.6-flash', 'gemini-flash-latest'];
const GROK_MODELS = ['grok-4.5', 'grok-4.3'];
const MAX_TURNS = 40;            // 한 대화에서 고객 메시지 최대 수
const MAX_MSG_LEN = 600;         // 고객 메시지 한 개 최대 글자
const RATE_PER_MIN = 12;         // 같은 IP에서 1분에 최대 요청 수
const COMPANY_RE = /^[A-Za-z0-9_-]{4,64}$/;
const SESSION_RE = /^[A-Za-z0-9_-]{8,64}$/;

// 테스트에서 바꿔 끼울 수 있게 (평소엔 실제 Firebase / fetch 사용)
const deps = {
  db: null,
  messaging: null,
  fetch: (url, options = {}) => fetch(url, { ...options, signal: options.signal || AbortSignal.timeout(15000) }),
  now: () => Date.now()
};
function db(){
  if(!deps.db){
    if(!admin.apps.length) admin.initializeApp();
    deps.db = admin.database();
    deps.messaging = admin.messaging();
  }
  return deps.db;
}

// ── 요청 제한 (인스턴스 메모리 기준, 가볍게) ──
const _hits = new Map();
function rateLimited(ip){
  const now = deps.now(), from = now - 60000;
  const list = (_hits.get(ip) || []).filter(t => t > from);
  list.push(now);
  _hits.set(ip, list);
  if(_hits.size > 5000){ for(const [k, v] of _hits){ if(!v.length || v[v.length - 1] < from) _hits.delete(k); } }
  return list.length > RATE_PER_MIN;
}

// ── AI 지시문 ──
function systemPrompt(companyName, phone){
  return [
    `당신은 "${companyName}"의 친절한 상담원입니다. 광주광역시·전남 전지역에서 장기 렌트, 단기 렌트, 보험대차(사고 대차)를 합니다.`,
    `대표 전화: ${phone}`,
    '',
    '[답하는 방법]',
    '- 한국어 존댓말로, 짧고 따뜻하게 (2~4문장). 이모지는 가끔만.',
    '- 대략적인 가격은 아래 "대략 가격"만 "~부터"로 안내하고, 정확한 가격은 차량·연식·기간·나이에 따라 달라서 담당자가 상담 후 안내한다고 말하세요.',
    '- 차량이 지금 있는지, 몇 대 있는지는 모릅니다. "가능 여부는 담당자가 바로 확인해서 연락드릴게요"라고 하세요. 절대 있다/없다를 지어내지 마세요.',
    '- 계약 확정, 할인 약속, 보험 처리 확답은 하지 마세요. 모르는 건 담당자 연결로 안내하세요.',
    '- 렌터카와 상관없는 요청(코딩, 숙제 등)은 정중히 거절하고 렌트 상담으로 돌아오세요.',
    '',
    '[대략 가격 (만원, 자차 미포함)] 경형 캐스퍼 월40~/일4~, 소형 아반떼·베뉴 월50~/일5~, 중형 쏘나타 월60~/일6~, 소형 SUV 투싼·스포티지 월60~/일6~. 그 외 차종(카니발, 그랜저, 수입차 등)은 상담 후 안내.',
    '가격을 말할 때는 꼭 "자차(자기차량손해) 미포함 기준이고, 연식·옵션에 따라 금액이 달라요"라고 함께 말하세요. 정확한 금액은 담당자가 안내한다고 하세요.',
    '',
    '[장기렌트 (가지고 있는 차, 중고 장기렌트 포함)]',
    '고객이 장기렌트를 원하면 차급(경형·소형·중형·SUV), 운전자 나이(만 21세 이상/26세 이상), 월 주행거리(2,000·3,000·4,000·5,000km), 자차 포함 여부를 하나씩 물어보세요. 정비는 항상 포함이에요.',
    '차급을 알게 되면 (나머지는 몰라도) 답의 맨 끝에 아래 한 줄을 붙이세요. 서버가 예상 월 렌트료를 계산해서 보여줍니다:',
    '<<LONGTERM {"grade":"SUV","age":26,"km":2000,"own":0}>>',
    'grade는 경형/소형/중형/SUV 중 하나(캐스퍼·모닝·레이=경형, 아반떼·K3·베뉴=소형, 쏘나타·K5=중형, 투싼·스포티지·셀토스=SUV), age 21 또는 26(모르면 26), km 2000~5000(모르면 2000), own 자차 포함 1/미포함 0(모르면 0). 금액은 직접 말하지 마세요.',
    '',
    '[신차 장기렌트]',
    '고객이 신차 장기렌트를 원하면 원하는 차종·트림, 차량 가격(옵션 포함, 대략이라도), 기간(12·24·36·48·60개월), 운전자 나이(만 21세 이상인지 26세 이상인지), 원하는 보증금 비율을 하나씩 물어보세요.',
    '차종(트림까지 알면 좋음)과 기간을 알게 되면, 차량 가격을 몰라도 답의 맨 끝에 아래 한 줄을 붙이세요 (고객에게는 안 보이고, 서버가 찻값을 검색하고 예상 월 렌트료를 계산해서 붙여 보여줍니다):',
    '<<NEWCAR {"car":"기아 쏘렌토 하이브리드 1.6 시그니처","price":0,"months":60,"age":26,"deposit":0}>>',
    'car는 제조사·모델·트림, price는 고객이 말한 차량 가격(원 단위, 모르면 0 — 서버가 찾아요), age는 21 또는 26(모르면 26), deposit은 고객이 말한 보증금 %(없으면 0).',
    '찻값이나 월 렌트료 숫자는 절대 직접 말하거나 지어내지 마세요. "바로 계산해드릴게요"처럼만 말하세요.',
    '예상 금액을 보여준 뒤 상담 신청을 받으면 memo에 "신차 · 차량가 ○○ · ○○개월 · 예상 월 ○○원"을 넣으세요.',
    '',
    '[상담 신청 받기]',
    '고객이 상담·예약을 원하면 대화로 아래를 자연스럽게 하나씩 물어보세요. 모르는 건 건너뛰어도 괜찮다고 해주세요.',
    '이름, 연락처(휴대폰), 대여 형태(단기/장기/보험대차), 원하는 차종, 날짜·기간, 생년월일(보험 나이 확인용), 요청사항.',
    '최소한 이름과 연락처를 받았고 고객이 신청할 뜻을 보이면, 답의 맨 끝에 아래 한 줄을 붙이세요 (고객에게는 안 보입니다):',
    '<<INQUIRY {"name":"","phone":"","kind":"단기|장기|보험대차|기타","car":"","period":"","birth":"","memo":""}>>',
    '모르는 칸은 빈 문자열로 두세요. 신청 뒤에 고객이 내용을 고치면 고친 내용으로 한 번 더 붙이세요.',
    '신청을 받으면 "접수됐어요. 담당자가 곧 연락드릴게요"라고 안내하세요.'
  ].join('\n');
}

// ── AI 호출: 대화는 Grok 먼저 → 안 되면 Gemini (Gemini는 찻값 검색에 주로 씀) ──
async function callAi(keys, sys, messages){
  let last = '';
  if(keys.grok){
    for(const model of GROK_MODELS){
      try{
        const r = await deps.fetch('https://api.x.ai/v1/chat/completions', {
          method: 'POST',
          headers: { 'Authorization': 'Bearer ' + keys.grok, 'content-type': 'application/json' },
          body: JSON.stringify({ model, temperature: 0.4, max_tokens: 600,
            messages: [{ role: 'system', content: sys }].concat(messages.map(m => ({ role: m.role === 'user' ? 'user' : 'assistant', content: m.text }))) })
        });
        if(r.status >= 200 && r.status < 300){
          const d = await r.json();
          const text = d && d.choices && d.choices[0] && d.choices[0].message && d.choices[0].message.content;
          if(text && String(text).trim()) return String(text).trim();
          last = 'grok empty';
        }else{ last = 'grok status ' + r.status; }
      }catch(e){ last = 'grok ' + String(e && e.message || e); }
    }
  }
  if(keys.gemini){
    try{ return await callGemini(keys.gemini, sys, messages); }catch(e){ last = e.message; }
  }
  throw new Error('AI 응답 실패: ' + last);
}
async function callGemini(key, sys, messages){
  const contents = messages.map(m => ({ role: m.role === 'user' ? 'user' : 'model', parts: [{ text: m.text }] }));
  let last = '';
  for(const model of GEMINI_MODELS){
    try{
      const r = await deps.fetch('https://generativelanguage.googleapis.com/v1beta/models/' + model + ':generateContent', {
        method: 'POST',
        headers: { 'x-goog-api-key': key, 'content-type': 'application/json' },
        body: JSON.stringify({
          systemInstruction: { parts: [{ text: sys }] },
          contents,
          generationConfig: { temperature: 0.4, maxOutputTokens: 600 }
        })
      });
      if(r.status >= 200 && r.status < 300){
        const d = await r.json();
        const cand = d && d.candidates && d.candidates[0];
        const text = cand && cand.content ? (cand.content.parts || []).map(p => p.text || '').join('') : '';
        if(text.trim()) return text.trim();
        last = 'empty';
      }else{
        last = 'status ' + r.status;
      }
    }catch(e){ last = String(e && e.message || e); }
  }
  throw new Error('AI 응답 실패: ' + last);
}

// ── 답에서 상담 신청 표시 꺼내기 ──
function extractInquiry(text){
  const m = String(text).match(/<<INQUIRY\s*(\{[\s\S]*?\})\s*>>/);
  const reply = String(text).replace(/<<INQUIRY[\s\S]*?>>/g, '').trim();
  if(!m) return { reply, inquiry: null };
  try{
    const j = JSON.parse(m[1]);
    const clean = (v, n) => String(v == null ? '' : v).replace(/[\u0000-\u001f]/g, ' ').trim().slice(0, n);
    const inquiry = {
      name: clean(j.name, 30), phone: clean(j.phone, 30), kind: clean(j.kind, 20),
      car: clean(j.car, 60), period: clean(j.period, 80), birth: clean(j.birth, 20), memo: clean(j.memo, 300)
    };
    if(!inquiry.name || !/\d{3,}/.test(inquiry.phone)) return { reply, inquiry: null };   // 이름·연락처 없으면 신청 아님
    return { reply, inquiry };
  }catch(e){ return { reply, inquiry: null }; }
}

// ── 신차 장기렌트 예상 금액 (현황판 문서 발행 → 신차 렌트 견적서의 ⚙️ 계산 기준과 같은 계산) ──
// 원가 = 내 할부금(할부 금리·기간·내 선수금) + (내 선수금 + 취등록세 + 등록 부대비용) ÷ 계약기간 + 보험료 + 정비비 + 지입료 + 기타
// 월 렌트료 = 원가 + 마진 − 고객 보증금 × 월 금리 (+21세 추가), 천원 단위 반올림
const NEWCAR_RATES_DEFAULT = { rate: 6, months: 60, down: 0, acq: 4, reg: 0, ins: 100000, maint: 50000, fee: 50000, etc: 0, margin: 50000, age21: 30000, d2: 10, d3: 30 };
// 현대·기아 차종 가격표 (기본값 — 현황판 문서 발행 → 신차 렌트 견적서 ⚙️ 계산 기준에서 고쳐 저장하면 그 표를 씀)
const NEWCAR_CARS_DEFAULT = "현대 캐스퍼 가솔린 스마트 1546\n현대 캐스퍼 가솔린 디에센셜 1792\n현대 캐스퍼 가솔린 인스퍼레이션 2035\n현대 아반떼 가솔린 모던 2398\n현대 아반떼 가솔린 프리미엄 2771\n현대 아반떼 가솔린 인스퍼레이션 3152\n현대 아반떼 하이브리드 모던 3042\n현대 아반떼 하이브리드 프리미엄 3361\n현대 아반떼 하이브리드 인스퍼레이션 3699\n현대 쏘나타 가솔린 프리미엄 2826\n현대 쏘나타 가솔린 익스클루시브 3260\n현대 쏘나타 가솔린 인스퍼레이션 3549\n현대 쏘나타 하이브리드 프리미엄 3270\n현대 쏘나타 하이브리드 익스클루시브 3674\n현대 쏘나타 하이브리드 인스퍼레이션 3979\n현대 그랜저 가솔린 프리미엄 4245\n현대 그랜저 가솔린 익스클루시브 4694\n현대 그랜저 가솔린 캘리그래피 5310\n현대 그랜저 하이브리드 프리미엄 4833\n현대 그랜저 하이브리드 익스클루시브 5282\n현대 그랜저 하이브리드 캘리그래피 5899\n현대 코나 가솔린 모던 2429\n현대 코나 가솔린 프리미엄 2875\n현대 코나 가솔린 인스퍼레이션 3102\n현대 코나 하이브리드 모던 2896\n현대 코나 하이브리드 프리미엄 3318\n현대 코나 하이브리드 인스퍼레이션 3512\n현대 투싼 가솔린 모던 2844\n현대 투싼 가솔린 프리미엄 3069\n현대 투싼 가솔린 인스퍼레이션 3407\n현대 투싼 하이브리드 모던 3270\n현대 투싼 하이브리드 프리미엄 3514\n현대 투싼 하이브리드 인스퍼레이션 3861\n현대 싼타페 가솔린 익스클루시브 3657\n현대 싼타페 하이브리드 익스클루시브 4022\n현대 팰리세이드 가솔린 익스클루시브 4478\n현대 팰리세이드 가솔린 H-Pick 5040\n현대 팰리세이드 가솔린 XRT 5211\n현대 팰리세이드 가솔린 캘리그래피 5606\n현대 팰리세이드 가솔린 블랙잉크 5767\n현대 팰리세이드 하이브리드 익스클루시브 5077\n현대 팰리세이드 하이브리드 H-Pick 5640\n현대 팰리세이드 하이브리드 캘리그래피 6206\n현대 팰리세이드 하이브리드 블랙잉크 6367\n현대 스타리아 LPG 투어러스마트 3502\n기아 모닝 가솔린 트렌디 1421\n기아 모닝 가솔린 프레스티지 1601\n기아 모닝 가솔린 시그니처 1816\n기아 모닝 가솔린 GT라인 1911\n기아 레이 가솔린 트렌디 1555\n기아 레이 가솔린 프레스티지 1815\n기아 레이 가솔린 시그니처 1955\n기아 K5 가솔린 프레스티지 2892\n기아 K5 가솔린 노블레스 3244\n기아 K5 가솔린 시그니처 3558\n기아 K5 하이브리드 프레스티지 3334\n기아 K5 하이브리드 노블레스 3670\n기아 K5 하이브리드 시그니처 3964\n기아 K8 가솔린 노블레스라이트 3679\n기아 K8 가솔린 노블레스 4085\n기아 K8 가솔린 시그니처 4440\n기아 K8 하이브리드 노블레스라이트 4206\n기아 K8 하이브리드 노블레스 4611\n기아 K8 하이브리드 시그니처 4966\n기아 셀토스 가솔린 트렌디 2477\n기아 셀토스 가솔린 프레스티지 2840\n기아 셀토스 가솔린 시그니처 3101\n기아 셀토스 가솔린 X-라인 3217\n기아 셀토스 하이브리드 트렌디 2898\n기아 셀토스 하이브리드 프레스티지 3208\n기아 셀토스 하이브리드 시그니처 3469\n기아 셀토스 하이브리드 X-라인 3584\n기아 스포티지 가솔린 프레스티지 2944\n기아 스포티지 가솔린 X-Line 3622\n기아 스포티지 하이브리드 프레스티지 3436\n기아 스포티지 하이브리드 X-Line 4103\n기아 쏘렌토 가솔린 프레스티지 3641\n기아 쏘렌토 가솔린 노블레스 3966\n기아 쏘렌토 가솔린 시그니처 4247\n기아 쏘렌토 가솔린 X-Line 4341\n기아 쏘렌토 하이브리드 프레스티지 3963\n기아 쏘렌토 하이브리드 노블레스 4299\n기아 쏘렌토 하이브리드 시그니처 4576\n기아 쏘렌토 하이브리드 X-Line 4670\n기아 카니발 가솔린 프레스티지 3686\n기아 카니발 가솔린 X-Line 4532\n기아 카니발 하이브리드 프레스티지 4141\n기아 카니발 하이브리드 X-Line 4987\n현대 캐스퍼 전기 프리미엄 2847\n현대 캐스퍼 전기 인스퍼레이션 3212\n현대 캐스퍼 전기 크로스 3412\n현대 코나 전기 모던 4152\n현대 코나 전기 프리미엄 4899\n현대 아이오닉5 전기 E-Value+ 4735\n현대 아이오닉5 전기 E-Lite 5064\n현대 아이오닉5 전기 모던 5290\n현대 아이오닉5 전기 프리미엄 5825\n현대 아이오닉5 전기 인스퍼레이션 6150\n현대 아이오닉6 전기 스탠다드 4856\n현대 아이오닉6 전기 익스클루시브 5095\n기아 레이 전기 에어 3062\n기아 EV3 전기 에어 3995\n기아 EV3 전기 GT라인 4475\n기아 EV4 전기 에어 4042\n기아 EV4 전기 어스 4501\n기아 EV4 전기 GT라인 4611\n기아 EV5 전기 에어 4155\n기아 EV6 전기 에어 5260\n기아 EV9 전기 에어 6857\n기아 EV9 전기 어스 7336\n기아 EV9 전기 GT라인 7917";
// ── 차종 가격표: 한 줄에 "제조사 모델 연료 트림 가격(만원)" ──
function ncParseCars(text) {
  return String(text || "").split(/\n/).map(function (l) {
    var p = l.trim().split(/\s+/);
    if (p.length < 5) return null;
    var price = Math.round(Number(p[p.length - 1].replace(/,/g, "")) * 10000);
    return { maker: p[0], model: p[1], fuel: p[2], trim: p.slice(3, -1).join(" "), price: price };
  }).filter(function (e) { return e && e.price >= 5000000 && e.price <= 300000000; });
}
// "기아 쏘렌토 하이브리드 1.6 시그니처" → 표에서 모델·연료·트림이 맞는 줄 (트림을 모르면 그 연료의 가장 싼 트림)
function ncMatchCar(list, car) {
  var n = function (s) { return String(s || "").toLowerCase().replace(/\s+/g, ""); };
  var c = n(car).replace(/케이파이브|케이5/g, "k5").replace(/케이에이트|케이8/g, "k8");
  var cands = list.filter(function (e) { return c.indexOf(n(e.model)) >= 0; });
  if (!cands.length) return null;
  var want = /하이브리드|hev|hybrid/.test(c) ? "하이브리드" : /전기|일렉트릭|electric|ev/.test(c) ? "전기" : /lpg/.test(c) ? "lpg" : "";
  var f = cands.filter(function (e) { var x = n(e.fuel); return want ? x === want : (x !== "하이브리드" && x !== "전기"); });
  if (!f.length) f = cands;
  var t = f.filter(function (e) { return e.trim && c.indexOf(n(e.trim)) >= 0; }).sort(function (a, b) { return n(b.trim).length - n(a.trim).length; });
  if (t.length) return Object.assign({ base: false }, t[0]);
  return Object.assign({ base: true }, f.slice().sort(function (a, b) { return a.price - b.price; })[0]);
}

function extractNewcar(text){
  const m = String(text).match(/<<NEWCAR\s*(\{[\s\S]*?\})\s*>>/);
  const reply = String(text).replace(/<<NEWCAR[\s\S]*?>>/g, '').trim();
  if(!m) return { reply, req: null };
  try{
    const j = JSON.parse(m[1]);
    const price = Math.round(Number(String(j.price).replace(/[^0-9.]/g, '')));
    const months = Number(j.months);
    const age = Number(j.age) === 21 ? 21 : 26;
    const deposit = Math.max(0, Math.min(50, Number(j.deposit) || 0));
    const car = String(j.car || '').replace(/[\u0000-\u001f<>]/g, ' ').trim().slice(0, 60);
    const okPrice = price >= 5000000 && price <= 300000000;
    if([12, 24, 36, 48, 60].indexOf(months) < 0 || (!okPrice && !car)) return { reply, req: null };
    return { reply, req: { car, price: okPrice ? price : 0, months, age, deposit } };
  }catch(e){ return { reply, req: null }; }
}
// 찻값 검색: Gemini의 구글 검색으로 국내 신차 판매가를 찾음 (찾은 차는 하루 동안 기억)
// 검색이 되는 모델을 먼저 (lite 모델은 검색을 못 하는 경우가 있음)
const SEARCH_MODELS = ['gemini-flash-latest', 'gemini-3.6-flash', 'gemini-3.5-flash-lite', 'gemini-3.1-flash-lite', 'gemini-flash-lite-latest'];
const _priceCache = new Map();
// 무료 한도 보호: 검색은 하루 최대 SEARCH_DAILY_MAX번, 한도 초과(429)가 나면 30분 동안 검색 안 함
//  → 같은 Gemini 키를 쓰는 현황판(번호판 인식 등)이 막히지 않게
const SEARCH_DAILY_MAX = 30;
const _search = { day: '', count: 0, blockedUntil: 0 };
// "47,390,000" / 47390000 / "4,739만원" / "약 4,739만 원" → 원 단위 숫자
function parsePriceKr(v){
  if(typeof v === 'number') return Math.round(v);
  const s = String(v == null ? '' : v).replace(/\s/g, '');
  const man = s.match(/([0-9][0-9,]*(?:\.[0-9]+)?)만/);
  if(man) return Math.round(Number(man[1].replace(/,/g, '')) * 10000);
  const n = s.match(/[0-9][0-9,]*/);
  return n ? Math.round(Number(n[0].replace(/,/g, ''))) : 0;
}
const okCarPrice = p => p >= 5000000 && p <= 300000000;
async function searchCarPrice(key, car){
  const ck = car.replace(/\s+/g, ' ').toLowerCase();
  const hit = _priceCache.get(ck);
  if(hit && deps.now() - hit.at < (hit.v ? 86400000 : 600000)) return hit.v;
  const today = new Date(deps.now() + 9 * 3600000).toISOString().slice(0, 10);
  if(_search.day !== today){ _search.day = today; _search.count = 0; }
  if(deps.now() < _search.blockedUntil || _search.count >= SEARCH_DAILY_MAX){ console.warn('car price search skipped (limit)', car); return null; }
  _search.count++;
  const prompt = '구글 검색으로 대한민국에서 판매 중인 "' + car + '" 신차 가격을 찾아주세요. '
    + '부가세 포함 제조사 공식 판매가(선택 옵션 제외)이고, 트림이 없으면 가장 많이 팔리는 트림 기준입니다. '
    + '답은 아래 JSON 한 줄만. price는 원 단위 숫자(예: 47390000). 못 찾으면 price를 0으로.\n'
    + '{"price": 47390000, "name": "제조사 모델 트림", "year": "연식"}';
  let last = '';
  for(const model of SEARCH_MODELS){
    try{
      const r = await deps.fetch('https://generativelanguage.googleapis.com/v1beta/models/' + model + ':generateContent', {
        method: 'POST',
        headers: { 'x-goog-api-key': key, 'content-type': 'application/json' },
        body: JSON.stringify({ contents: [{ role: 'user', parts: [{ text: prompt }] }], tools: [{ google_search: {} }] })
      });
      if(r.status === 429){ _search.blockedUntil = deps.now() + 30 * 60000; last = model + ' status 429 (한도 초과, 30분 쉼)'; break; }
      if(r.status < 200 || r.status >= 300){ last = model + ' status ' + r.status; continue; }
      const d = await r.json();
      const cand = d && d.candidates && d.candidates[0];
      const text = cand && cand.content ? (cand.content.parts || []).map(p => p.text || '').join('') : '';
      let price = 0, name = car, year = '';
      const m = text.match(/\{[^{}]*"price"[^{}]*\}/);
      if(m){
        try{ const j = JSON.parse(m[0]); price = parsePriceKr(j.price); name = String(j.name || car); year = String(j.year || ''); }
        catch(e){ price = parsePriceKr((m[0].match(/"price"\s*:\s*"?([^",}]+)/) || [])[1]); }
      }
      if(!okCarPrice(price)){   // JSON이 아니어도 글 속 "4,739만원" 같은 가격을 찾아봄
        const t = text.match(/([0-9][0-9,]*(?:\.[0-9]+)?)\s*만\s*원/) || text.match(/([0-9]{1,3}(?:,[0-9]{3}){2,})\s*원/);
        if(t) price = parsePriceKr(t[0]);
      }
      if(okCarPrice(price)){
        const v = { price, name: name.slice(0, 60), year: year.slice(0, 20) };
        _priceCache.set(ck, { at: deps.now(), v });
        return v;
      }
      last = model + ' no price: ' + text.replace(/\s+/g, ' ').slice(0, 160);
    }catch(e){ last = model + ' ' + String(e && e.message || e); }
  }
  console.warn('car price search failed:', car, '|', last);   // Cloud Run 로그에서 확인용
  _priceCache.set(ck, { at: deps.now(), v: null });
  return null;
}
function newcarMonthly(R, price, months, age, depositPct){
  const r = (Number(R.rate) || 0) / 100 / 12, H = Number(R.months) || 60;
  const P = price * (1 - (Number(R.down) || 0) / 100);
  const inst = r ? P * r / (1 - Math.pow(1 + r, -H)) : P / H;
  const upfront = price * ((Number(R.down) || 0) + (Number(R.acq) || 0)) / 100 + (Number(R.reg) || 0);
  const cost = inst + upfront / months + (Number(R.ins) || 0) + (Number(R.maint) || 0) + (Number(R.fee) || 0) + (Number(R.etc) || 0)
    - price * depositPct / 100 * r + (age === 21 ? (Number(R.age21) || 0) : 0);
  return Math.max(0, Math.round((Math.max(0, cost) + (Number(R.margin) || 0)) / 1000) * 1000);
}
function newcarEstimateText(R, q){
  const won = n => Math.round(n).toLocaleString('ko-KR') + '원';
  const man = n => (n % 10000 === 0 ? (n / 10000).toLocaleString('ko-KR') + '만원' : won(n));
  const pcts = [0, Number(R.d2) || 0, Number(R.d3) || 0, q.deposit].filter((v, i, a) => i === 0 || (v > 0 && a.indexOf(v) === i)).sort((a, b) => a - b).slice(0, 4);
  const lines = pcts.map(d => '· ' + (d ? '보증금 ' + d + '%(' + man(Math.round(q.price * d / 100)) + ')' : '보증금 없음') + ': 월 ' + won(newcarMonthly(R, q.price, q.months, q.age, d)));
  const priceLine = q.listed
    ? '차량 가격 ' + man(q.price) + ' (' + q.listed + ', 옵션 제외)'
    : q.searched
    ? '차량 가격 약 ' + (Math.round(q.price / 10000)).toLocaleString('ko-KR') + '만원 (인터넷 검색: ' + q.searched + ', 옵션 제외)'
    : '차량 가격 ' + man(q.price);
  return '📋 신차 장기렌트 예상 월 렌트료\n' + (q.car ? q.car + '\n' : '') + priceLine + ' · ' + q.months + '개월 · 만 ' + q.age + '세 이상\n' + lines.join('\n')
    + '\n(부가세·보험 포함 예상 금액이에요. ' + (q.searched || q.listed ? '옵션을 넣으면 올라가요. ' : '') + '보증금은 계약이 끝나면 돌려드려요. 정확한 견적은 담당자가 안내드려요)';
}

// ── 장기렌트(보유 차량) 예상 금액: 현황판 문서 발행 → 장기 견적서 자동 계산과 같은 요금표 ──
// 26세 기본(자차 미포함) + 21세 +5만 + 월 주행거리 1천km마다 +5만 + 자차 +5만 + 정비 5만(필수)
const LONG_TABLE = { '경형': { 21: 450000, 26: 400000 }, '소형': { 21: 550000, 26: 500000 }, '중형': { 21: 650000, 26: 600000 }, 'SUV': { 21: 650000, 26: 600000 } };
const LONG_MAINT = 50000;
function extractLongterm(text){
  const m = String(text).match(/<<LONGTERM\s*(\{[\s\S]*?\})\s*>>/);
  const reply = String(text).replace(/<<LONGTERM[\s\S]*?>>/g, '').trim();
  if(!m) return { reply, req: null };
  try{
    const j = JSON.parse(m[1]);
    const g = String(j.grade || '').toUpperCase().replace('SUV', 'SUV');
    const grade = ['경형', '소형', '중형', 'SUV'].find(x => g.indexOf(x) >= 0);
    if(!grade) return { reply, req: null };
    const age = Number(j.age) === 21 ? 21 : 26;
    const km = [2000, 3000, 4000, 5000].indexOf(Number(j.km)) >= 0 ? Number(j.km) : 2000;
    const own = Number(j.own) === 1 || j.own === true ? 1 : 0;
    return { reply, req: { grade, age, km, own } };
  }catch(e){ return { reply, req: null }; }
}
function longtermMonthly(q){ return LONG_TABLE[q.grade][q.age] + (q.km - 2000) / 1000 * 50000 + (q.own ? 50000 : 0) + LONG_MAINT; }
function longtermEstimateText(q){
  const won = n => n.toLocaleString('ko-KR') + '원';
  const base = longtermMonthly(Object.assign({}, q, { own: 0 })), withOwn = longtermMonthly(Object.assign({}, q, { own: 1 }));
  return '📋 장기렌트 예상 월 렌트료\n' + q.grade + ' · 만 ' + q.age + '세 이상 · 월 ' + q.km.toLocaleString('ko-KR') + 'km · 정비 포함\n'
    + '· 자차 미포함: 월 ' + won(base) + '\n· 자차 포함: 월 ' + won(withOwn)
    + '\n(연식·차종·옵션에 따라 달라요. 지금 바로 탈 수 있는 차는 담당자가 확인해서 안내드려요)';
}

// ── 상담 신청 저장 + 직원 메신저 + 푸시 ──
async function saveInquiry(companyId, sessionId, inquiry, messages){
  const base = db().ref('companies/' + companyId);
  const ref = base.child('inquiries/s_' + sessionId);
  const prev = (await ref.once('value')).val();
  const nowIso = new Date(deps.now()).toISOString();
  const transcript = messages.slice(-30).map(m => (m.role === 'user' ? '고객: ' : '상담: ') + m.text).join('\n').slice(0, 6000);
  await ref.update(Object.assign({}, inquiry, {
    source: 'homepage',
    createdAt: (prev && prev.createdAt) || nowIso,
    updatedAt: nowIso,
    transcript,
    contacted: (prev && prev.contacted) || false
  }));
  if(prev) return false;   // 같은 대화에서 고친 경우엔 메신저·푸시를 또 보내지 않음

  const line = [inquiry.name, inquiry.phone, inquiry.kind, inquiry.car, inquiry.period].filter(Boolean).join(' · ');
  const text = `📞 새 상담 신청 (홈페이지)\n${line}${inquiry.memo ? '\n요청: ' + inquiry.memo : ''}\n→ 📞 상담 목록에서 연락 완료를 체크해주세요`;
  await base.child('chat').push({ text: text.slice(0, 2000), uid: 'system', email: '현황판', at: nowIso, type: 'system' });

  try{
    const members = (await base.child('members').once('value')).val() || {};
    const tokens = Object.values(members).map(m => m && m.pushToken).filter(Boolean);
    if(tokens.length && deps.messaging){
      await deps.messaging.sendEachForMulticast({
        tokens,
        notification: { title: '📞 새 상담 신청', body: line.slice(0, 100) },
        android: { priority: 'high', notification: { channelId: 'fleet_alerts_v2', sound: 'default' } }
      });
    }
  }catch(e){ console.warn('push failed', e && e.message); }
  return true;
}

// ── 요청 처리 ──
async function handle(req, res){
  const origin = req.get ? req.get('origin') : (req.headers && req.headers.origin);
  if(origin && (ALLOWED_ORIGINS.includes(origin) || origin === 'https://localhost')){
    res.set('Access-Control-Allow-Origin', origin);
    res.set('Vary', 'Origin');
  }
  res.set('Access-Control-Allow-Methods', 'POST, OPTIONS');
  res.set('Access-Control-Allow-Headers', 'Content-Type, Authorization');
  if(req.method === 'OPTIONS') return res.status(204).send('');
  if(req.method !== 'POST') return res.status(405).json({ error: 'POST only' });
  const staffOperation = req.body && ['ai-status', 'ai-config', 'ai-vision'].includes(req.body.operation);
  // Native app requests may omit Origin; they must still authenticate below.
  if((origin && !ALLOWED_ORIGINS.includes(origin) && !(staffOperation && origin === 'https://localhost')) || (!origin && !staffOperation)) return res.status(403).json({ error: 'origin' });
  if(staffOperation){
    try { return await handleStaff(req, res, { db, deps, admin, geminiModels: GEMINI_MODELS, grokModels: GROK_MODELS }); }
    catch { return res.status(503).json({ reply: 'AI 서버에 연결하지 못했어요. 잠시 후 다시 시도해주세요.' }); }
  }

  const ip = String((req.headers && (req.headers['x-forwarded-for'] || '')) || req.ip || '').split(',')[0].trim() || 'unknown';
  if(rateLimited(ip)) return res.status(429).json({ error: 'too many', reply: '잠시 후 다시 말씀해주세요. 급하시면 전화 주세요.' });

  const body = req.body || {};
  const companyId = String(body.companyId || '');
  const sessionId = String(body.sessionId || '');
  if(!COMPANY_RE.test(companyId) || !SESSION_RE.test(sessionId)) return res.status(400).json({ error: 'bad request' });
  let messages = Array.isArray(body.messages) ? body.messages : [];
  if(messages.length > 81) return res.status(400).json({ error: 'too many messages' });
  const userTurns = messages.filter(m => m && m.role === 'user').length;
  messages = messages
    .filter(m => m && (m.role === 'user' || m.role === 'assistant') && typeof m.text === 'string' && m.text.trim())
    .map(m => ({ role: m.role, text: m.text.trim().slice(0, m.role === 'user' ? MAX_MSG_LEN : 2000) }))
    .slice(-60);
  while(messages.length && messages[0].role !== 'user') messages.shift();   // AI 대화는 고객 말로 시작해야 함
  if(!messages.length || messages[messages.length - 1].role !== 'user') return res.status(400).json({ error: 'no message' });
  if(userTurns > MAX_TURNS){
    return res.json({ reply: '대화가 길어졌네요. 자세한 건 전화로 바로 안내해드릴게요 😊', done: true });
  }

  try{
    const base = db().ref('companies/' + companyId);
    const [ai, profSnap] = await Promise.all([getKeys(db(), companyId), base.child('profile').once('value')]);
    const keys = { gemini: ai.geminiKey || (ai.provider === 'gemini' ? ai.key : ''), grok: ai.grokKey || '' };
    if(!keys.gemini && !keys.grok) return res.status(503).json({ error: 'no ai key' });
    const prof = profSnap.val() || {};
    const companyName = String(prof.name || '팡팡렌트카').slice(0, 80);
    const phone = String(prof.phone || '010-5145-8990').slice(0, 20);

    if(!await consumeQuota(db(), companyId, 'ip:' + ip, deps.now(), { publicChat: true, sessionId })){
      return res.status(429).json({ reply: '대화 또는 AI 사용량 한도에 도달했어요. 자세한 안내는 전화로 문의해주세요.', done: true });
    }
    const raw = await callAi(keys, systemPrompt(companyName, phone), messages);
    const lt = extractLongterm(raw);
    const nc = extractNewcar(lt.reply);
    let { reply, inquiry } = extractInquiry(nc.reply);
    if(lt.req) reply = (reply ? reply + '\n\n' : '') + longtermEstimateText(lt.req);
    let saved = null;
    if(nc.req) saved = (await db().ref('companyDocs/' + companyId + '/_newcarRates').once('value')).val() || {};
    if(nc.req && !nc.req.price){
      const carsText = (saved && typeof saved.cars === 'string' && saved.cars.trim()) ? saved.cars : NEWCAR_CARS_DEFAULT;
      const hitCar = ncMatchCar(ncParseCars(carsText), nc.req.car);
      if(hitCar){
        nc.req.price = hitCar.price;
        nc.req.listed = [hitCar.maker, hitCar.model, hitCar.fuel, hitCar.trim].join(' ') + (hitCar.base ? ' 기본 트림 기준' : ' 기준');
      }
    }
    if(nc.req && !nc.req.price){
      const found = keys.gemini ? await searchCarPrice(keys.gemini, nc.req.car) : null;
      if(found){ nc.req.price = found.price; nc.req.searched = [found.name, found.year].filter(Boolean).join(' '); }
      else{
        reply = (reply ? reply + '\n\n' : '') + '차량 가격을 바로 찾지 못했어요. 대략적인 차량 가격(옵션 포함)을 알려주시면 바로 계산해드릴게요.';
        nc.req = null;
      }
    }
    if(nc.req){
      const R = Object.assign({}, NEWCAR_RATES_DEFAULT);
      Object.keys(NEWCAR_RATES_DEFAULT).forEach(k => { if(saved[k] != null && isFinite(Number(saved[k]))) R[k] = Number(saved[k]); });
      reply = (reply ? reply + '\n\n' : '') + newcarEstimateText(R, nc.req);
    }
    let submitted = false;
    if(inquiry) submitted = await saveInquiry(companyId, sessionId, inquiry, messages.concat([{ role: 'assistant', text: reply }]));
    return res.json({ reply: reply || '잠시만요, 다시 한 번 말씀해주시겠어요?', submitted: !!inquiry, firstSubmit: submitted });
  }catch(e){
    console.error('AI request failed');
    return res.status(502).json({ error: 'ai failed' });
  }
}

functions.http('aiconsult', handle);
module.exports = { extractLongterm, longtermMonthly, longtermEstimateText, _search, ncParseCars, ncMatchCar, NEWCAR_CARS_DEFAULT, handle, extractInquiry, extractNewcar, searchCarPrice, parsePriceKr, _priceCache, newcarMonthly, newcarEstimateText, NEWCAR_RATES_DEFAULT, systemPrompt, deps, _hits };
