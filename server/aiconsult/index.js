// 홈페이지 AI 상담 서버 (Cloud Run 함수, 진입점: aiconsult)
//
// 홈페이지(pang-rent.github.io/main)의 AI 상담 창 → 현황판 chat.html → 여기로 대화를 보냅니다.
// - AI 키는 페이지에 두지 않고, 관리자가 현황판 🤖 AI 설정에 넣은 공용 키(Gemini 먼저, 안 되면 Grok)를 여기서 읽어 씁니다.
// - 차량 재고·번호판·고객 정보는 AI에 넘기지 않습니다 (가능 여부는 담당자가 확인해서 연락).
// - 고객이 이름·연락처를 알려주면 상담 신청으로 저장하고, 직원 메신저에 올리고, 직원 폰에 푸시를 보냅니다.
//
// 배포 때 환경 변수: FIREBASE_CONFIG (sendchatpush와 같은 값)

const functions = require('@google-cloud/functions-framework');
const admin = require('firebase-admin');

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
  fetch: (...a) => fetch(...a),
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
    '[대략 가격 (만원, 자차 미포함)] 경형 캐스퍼 월40~/일4~, 소형 아반떼·베뉴 월50~/일5~, 중형 쏘나타 월60~/일6~, 소형 SUV 투싼·스포티지 월70~/일7~. 그 외 차종(카니발, 그랜저, 수입차 등)은 상담 후 안내.',
    '가격을 말할 때는 꼭 "자차(자기차량손해) 미포함 기준이고, 연식·옵션에 따라 금액이 달라요"라고 함께 말하세요. 정확한 금액은 담당자가 안내한다고 하세요.',
    '',
    '[신차 장기렌트]',
    '고객이 신차 장기렌트를 원하면 원하는 차종·트림, 차량 가격(옵션 포함, 대략이라도), 기간(12·24·36·48·60개월), 운전자 나이(만 21세 이상인지 26세 이상인지), 원하는 보증금 비율을 하나씩 물어보세요.',
    '차량 가격과 기간을 알게 되면 답의 맨 끝에 아래 한 줄을 붙이세요 (고객에게는 안 보이고, 서버가 예상 월 렌트료를 계산해서 붙여 보여줍니다):',
    '<<NEWCAR {"price":48000000,"months":60,"age":26,"deposit":0}>>',
    'price는 원 단위 숫자, age는 21 또는 26(모르면 26), deposit은 고객이 말한 보증금 %(없으면 0). 월 렌트료 숫자는 절대 직접 계산하거나 지어내지 마세요.',
    '차량 가격을 모르면 "차량 가격을 알려주시면 예상 금액을 바로 계산해드릴게요, 모르시면 담당자가 확인해드려요"라고 하세요.',
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

// ── AI 호출: Gemini(모델이 바쁘면 다음 모델) → 다 안 되면 Grok ──
async function callAi(keys, sys, messages){
  let last = '';
  if(keys.gemini){
    try{ return await callGemini(keys.gemini, sys, messages); }catch(e){ last = e.message; }
  }
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
// 원가 = 내 할부금(할부 금리·기간·내 선수금) + (내 선수금 + 취등록세 + 등록 부대비용) ÷ 계약기간 + 보험료 + 지입료 + 기타
// 월 렌트료 = 원가 + 마진 − 고객 보증금 × 월 금리 (+21세 추가), 천원 단위 반올림
const NEWCAR_RATES_DEFAULT = { rate: 6, months: 60, down: 0, acq: 4, reg: 0, ins: 100000, fee: 50000, etc: 0, margin: 50000, age21: 30000, d2: 10, d3: 30 };
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
    if(!(price >= 5000000 && price <= 300000000) || [12, 24, 36, 48, 60].indexOf(months) < 0) return { reply, req: null };
    return { reply, req: { price, months, age, deposit } };
  }catch(e){ return { reply, req: null }; }
}
function newcarMonthly(R, price, months, age, depositPct){
  const r = (Number(R.rate) || 0) / 100 / 12, H = Number(R.months) || 60;
  const P = price * (1 - (Number(R.down) || 0) / 100);
  const inst = r ? P * r / (1 - Math.pow(1 + r, -H)) : P / H;
  const upfront = price * ((Number(R.down) || 0) + (Number(R.acq) || 0)) / 100 + (Number(R.reg) || 0);
  const cost = inst + upfront / months + (Number(R.ins) || 0) + (Number(R.fee) || 0) + (Number(R.etc) || 0)
    - price * depositPct / 100 * r + (age === 21 ? (Number(R.age21) || 0) : 0);
  return Math.max(0, Math.round((Math.max(0, cost) + (Number(R.margin) || 0)) / 1000) * 1000);
}
function newcarEstimateText(R, q){
  const won = n => Math.round(n).toLocaleString('ko-KR') + '원';
  const man = n => (n % 10000 === 0 ? (n / 10000).toLocaleString('ko-KR') + '만원' : won(n));
  const pcts = [0, Number(R.d2) || 0, Number(R.d3) || 0, q.deposit].filter((v, i, a) => i === 0 || (v > 0 && a.indexOf(v) === i)).sort((a, b) => a - b).slice(0, 4);
  const lines = pcts.map(d => '· ' + (d ? '보증금 ' + d + '%(' + man(Math.round(q.price * d / 100)) + ')' : '보증금 없음') + ': 월 ' + won(newcarMonthly(R, q.price, q.months, q.age, d)));
  return '📋 신차 장기렌트 예상 월 렌트료\n차량 가격 ' + man(q.price) + ' · ' + q.months + '개월 · 만 ' + q.age + '세 이상\n' + lines.join('\n')
    + '\n(부가세·보험 포함 예상 금액이에요. 보증금은 계약이 끝나면 돌려드려요. 정확한 견적은 담당자가 안내드려요)';
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
  if(origin && ALLOWED_ORIGINS.includes(origin)){
    res.set('Access-Control-Allow-Origin', origin);
    res.set('Vary', 'Origin');
  }
  res.set('Access-Control-Allow-Methods', 'POST, OPTIONS');
  res.set('Access-Control-Allow-Headers', 'Content-Type');
  if(req.method === 'OPTIONS') return res.status(204).send('');
  if(req.method !== 'POST') return res.status(405).json({ error: 'POST only' });
  if(origin && !ALLOWED_ORIGINS.includes(origin)) return res.status(403).json({ error: 'origin' });

  const ip = String((req.headers && (req.headers['x-forwarded-for'] || '')) || req.ip || '').split(',')[0].trim() || 'unknown';
  if(rateLimited(ip)) return res.status(429).json({ error: 'too many', reply: '잠시 후 다시 말씀해주세요. 급하시면 전화 주세요.' });

  const body = req.body || {};
  const companyId = String(body.companyId || '');
  const sessionId = String(body.sessionId || '');
  if(!COMPANY_RE.test(companyId) || !SESSION_RE.test(sessionId)) return res.status(400).json({ error: 'bad request' });
  let messages = Array.isArray(body.messages) ? body.messages : [];
  messages = messages
    .filter(m => m && (m.role === 'user' || m.role === 'assistant') && typeof m.text === 'string' && m.text.trim())
    .map(m => ({ role: m.role, text: m.text.trim().slice(0, m.role === 'user' ? MAX_MSG_LEN : 2000) }))
    .slice(-60);
  while(messages.length && messages[0].role !== 'user') messages.shift();   // AI 대화는 고객 말로 시작해야 함
  if(!messages.length || messages[messages.length - 1].role !== 'user') return res.status(400).json({ error: 'no message' });
  if(messages.filter(m => m.role === 'user').length > MAX_TURNS){
    return res.json({ reply: '대화가 길어졌네요. 자세한 건 전화로 바로 안내해드릴게요 😊', done: true });
  }

  try{
    const base = db().ref('companies/' + companyId);
    const [aiSnap, profSnap] = await Promise.all([base.child('aiSettings').once('value'), base.child('profile').once('value')]);
    const ai = aiSnap.val() || {};
    const keys = { gemini: ai.geminiKey || (ai.provider === 'gemini' ? ai.key : ''), grok: ai.grokKey || '' };
    if(!keys.gemini && !keys.grok) return res.status(503).json({ error: 'no ai key' });
    const prof = profSnap.val() || {};
    const companyName = '팡팡렌트카';
    const phone = String(prof.phone || '010-5145-8990').slice(0, 20);

    const raw = await callAi(keys, systemPrompt(companyName, phone), messages);
    const nc = extractNewcar(raw);
    let { reply, inquiry } = extractInquiry(nc.reply);
    if(nc.req){
      const saved = (await db().ref('companyDocs/' + companyId + '/_newcarRates').once('value')).val() || {};
      const R = Object.assign({}, NEWCAR_RATES_DEFAULT);
      Object.keys(NEWCAR_RATES_DEFAULT).forEach(k => { if(saved[k] != null && isFinite(Number(saved[k]))) R[k] = Number(saved[k]); });
      reply = (reply ? reply + '\n\n' : '') + newcarEstimateText(R, nc.req);
    }
    let submitted = false;
    if(inquiry) submitted = await saveInquiry(companyId, sessionId, inquiry, messages.concat([{ role: 'assistant', text: reply }]));
    return res.json({ reply: reply || '잠시만요, 다시 한 번 말씀해주시겠어요?', submitted: !!inquiry, firstSubmit: submitted });
  }catch(e){
    console.error(e);
    return res.status(502).json({ error: 'ai failed' });
  }
}

functions.http('aiconsult', handle);
module.exports = { handle, extractInquiry, extractNewcar, newcarMonthly, newcarEstimateText, NEWCAR_RATES_DEFAULT, systemPrompt, deps, _hits };
