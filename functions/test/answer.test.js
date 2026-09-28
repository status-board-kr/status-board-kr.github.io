import { test } from 'node:test';
import assert from 'node:assert/strict';
import { answer, ChatError } from '../answer.js';

// 아주 작은 가짜 Realtime Database (경로 → 값)
function fakeDb(initial) {
  const root = structuredClone(initial);
  let n = 0;
  const parts = p => p.split('/').filter(Boolean);
  const read = p => parts(p).reduce((o, k) => (o == null ? undefined : o[k]), root);
  const write = (p, v) => {
    const ks = parts(p); let o = root;
    for (const k of ks.slice(0, -1)) o = (o[k] ??= {});
    o[ks.at(-1)] = v;
  };
  const ref = p => ({
    key: parts(p).at(-1),
    get: async () => ({ val: () => read(p) ?? null }),
    set: async v => write(p, structuredClone(v)),
    update: async v => { for (const [k, x] of Object.entries(v)) write(p + '/' + k, structuredClone(x)); },
    push: (v) => { const r = ref(p + '/k' + (++n)); if (v !== undefined) return r.set(v).then(() => r); return r; },
    transaction: async fn => { const v = fn(read(p)); write(p, v); return { snapshot: { val: () => v } }; },
  });
  return { ref, root };
}

// 순서대로 정해둔 응답을 돌려주는 가짜 Claude
function fakeClient(responses, seen) {
  return () => ({ messages: { create: async req => { seen.push(req); return responses.shift(); } } });
}

const C = 'c_test1';
function baseData() {
  return { companies: { [C]: {
    profile: { name: '장성렌트' },
    aiSettings: { key: 'sk-ant-test-000000000000000000' },
    customerChat: { settings: { enabled: true, priceGuide: '카니발 하루 10만원' } },
    members: { u1: { role: 'owner', pushToken: 'tok1' }, u2: { role: 'staff' } },
    vehicles: [
      { plate: '12가3456', model: '카니발', cls: '승합', fuel: '경유', status: '대기', customerName: '비밀고객' },
    ],
  } } };
}

test('예약 요청 → 문의 목록, 현황판 일정, 직원 채팅, 푸시까지', async () => {
  const db = fakeDb(baseData());
  const pushes = []; const seen = [];
  const reply = await answer({ c: C, sid: 's_abcdef', text: '카니발 10월 3일부터 2박3일 예약할게요. 홍길동 010-1234-5678' }, {
    db, messaging: { sendEachForMulticast: async m => pushes.push(m) },
    makeClient: fakeClient([
      { stop_reason: 'tool_use', content: [{ type: 'tool_use', id: 't1', name: 'request_booking',
        input: { name: '홍길동', phone: '010-1234-5678', model: '카니발', startDate: '2099-10-03', startTime: '10:00', endDate: '2099-10-05', endTime: '18:00', period: '2박3일',
          rentalType: '단기대여', rentalRegion: '광주', birthdate: '1990-01-01', note: '', agreeMarketing: true } }] },
      { stop_reason: 'end_turn', content: [{ type: 'text', text: '예약 요청 접수했어요! 담당자가 확인 후 연락드릴게요.' }] },
    ], seen),
  });
  assert.match(reply, /접수/);
  const co = db.root.companies[C];
  const lead = Object.values(co.customerChat.leads)[0];
  assert.equal(lead.type, 'booking');
  assert.equal(lead.status, 'pending');
  const sched = co.schedules[lead.scheduleKey];
  assert.equal(sched.date, '2099-10-03');
  assert.equal(sched.title, '📅 예약요청 10/3 10시 ~ 10/5 18시 카니발 · 홍길동');
  assert.equal(lead.scheduleTitle, sched.title);
  assert.equal(lead.birthdate, '19900101');
  assert.equal(lead.agreeMarketing, true);
  assert.match(Object.values(co.chat)[0].text, /새 예약 요청/);
  assert.deepEqual(pushes[0].tokens, ['tok1']);
  assert.equal(co.customerChat.sessions.s_abcdef.hasBooking, true);
  // 두 번째 호출에 도구 결과가 들어갔고, 안내문에 고객 정보는 없음
  assert.equal(seen[1].messages.at(-1).content[0].type, 'tool_result');
  assert.ok(!seen[0].system.includes('비밀고객') && !seen[0].system.includes('12가3456'));
});

test('지난 날짜 예약은 거절하고 Claude에게 다시 묻게 함', async () => {
  const db = fakeDb(baseData()); const seen = [];
  await answer({ c: C, sid: 's_abcdef', text: '예약' }, {
    db, messaging: { sendEachForMulticast: async () => {} },
    makeClient: fakeClient([
      { stop_reason: 'tool_use', content: [{ type: 'tool_use', id: 't1', name: 'request_booking',
        input: { name: 'a', phone: '010-1234-5678', model: '카니발', startDate: '2000-01-01', startTime: '', endDate: '', endTime: '', period: '',
          rentalType: '단기대여', rentalRegion: '', birthdate: '', note: '', agreeMarketing: false } }] },
      { stop_reason: 'end_turn', content: [{ type: 'text', text: '날짜를 다시 알려주세요.' }] },
    ], seen),
  });
  assert.equal(seen[1].messages.at(-1).content[0].is_error, true);
  assert.equal(db.root.companies[C].schedules, undefined);
});

test('채팅이 꺼져 있으면 403', async () => {
  const data = baseData(); data.companies[C].customerChat.settings.enabled = false;
  await assert.rejects(answer({ c: C, sid: 's_abcdef', text: '안녕' }, { db: fakeDb(data), messaging: {}, makeClient: () => ({}) }),
    e => e instanceof ChatError && e.status === 403);
});

const SHEET = 'https://script.google.com/macros/s/AKfycbTESTtest1234567890/exec';
const consultInput = { rentalType: '장기대여', rentalPeriod: '12개월', startWhen: '다음 달 초', carClass: '소형 SUV', budget: '월 50만원 이하',
  rentalRegion: '나주', name: '김철수', phone: '010-9876-5432', birthdate: '19850315', note: '출퇴근용', agreeMarketing: true };

test('AI 상담 신청 → 문의 목록, 현황판 일정, 직원 알림, 고객 명단 시트', async () => {
  const data = baseData(); data.companies[C].customerChat.settings.sheetUrl = SHEET;
  const db = fakeDb(data); const seen = []; const posts = []; const pushes = [];
  const reply = await answer({ c: C, sid: 's_consult1', text: '네 문자 받을게요' }, {
    db, messaging: { sendEachForMulticast: async m => pushes.push(m) },
    fetchImpl: async (url, opt) => { posts.push({ url, row: JSON.parse(opt.body) }); return { status: 200, json: async () => ({ success: true }) }; },
    makeClient: fakeClient([
      { stop_reason: 'tool_use', content: [{ type: 'tool_use', id: 't1', name: 'request_consult', input: consultInput }] },
      { stop_reason: 'end_turn', content: [{ type: 'text', text: '상담 신청 접수했어요!' }] },
    ], seen),
  });
  assert.match(reply, /접수/);
  const co = db.root.companies[C];
  const lead = Object.values(co.customerChat.leads)[0];
  assert.equal(lead.type, 'consult');
  assert.equal(lead.budget, '월 50만원 이하');
  assert.equal(lead.sheet, 'saved');
  const sched = co.schedules[lead.scheduleKey];
  assert.equal(sched.title, '📝 상담신청 장기 12개월 소형 SUV · 김철수');
  assert.match(sched.memo, /예산 월 50만원 이하/);
  assert.match(sched.memo, /AI상담 상담신청/);
  assert.match(Object.values(co.chat)[0].text, /AI상담 상담신청.*예산 월 50만원 이하.*010-9876-5432/);
  assert.equal(pushes.length, 1);
  assert.equal(posts.length, 1);
  assert.equal(posts[0].url, SHEET);
  assert.equal(posts[0].row.via, 'AI상담');
  assert.equal(posts[0].row.kind, '상담신청');
  assert.equal(posts[0].row.budget, '월 50만원 이하');
  assert.equal(posts[0].row.agreeMarketing, '동의');
  assert.equal(co.customerChat.sessions.s_consult1.hasLead, true);
  // 안내문에 상담 신청·문자 수신 동의 규칙이 있음
  assert.match(seen[0].system, /request_consult/);
  assert.match(seen[0].system, /이벤트 소식을 문자로/);
});

test('시트 저장이 실패해도 접수는 되고 실패로 표시', async () => {
  const data = baseData(); data.companies[C].customerChat.settings.sheetUrl = SHEET;
  const db = fakeDb(data);
  await answer({ c: C, sid: 's_consult2', text: '신청' }, {
    db, messaging: { sendEachForMulticast: async () => {} },
    fetchImpl: async () => { throw new Error('network'); },
    makeClient: fakeClient([
      { stop_reason: 'tool_use', content: [{ type: 'tool_use', id: 't1', name: 'request_consult', input: consultInput }] },
      { stop_reason: 'end_turn', content: [{ type: 'text', text: '접수했어요' }] },
    ], []),
  });
  const lead = Object.values(db.root.companies[C].customerChat.leads)[0];
  assert.equal(lead.sheet, 'failed');
  assert.ok(lead.scheduleKey);
});

test('시트 주소가 없거나 구글 주소가 아니면 보내지 않음', async () => {
  for (const url of [undefined, 'https://evil.example.com/exec']) {
    const data = baseData(); if (url) data.companies[C].customerChat.settings.sheetUrl = url;
    const db = fakeDb(data); let called = 0;
    await answer({ c: C, sid: 's_consult3', text: '신청' }, {
      db, messaging: { sendEachForMulticast: async () => {} },
      fetchImpl: async () => { called++; return { json: async () => ({ success: true }) }; },
      makeClient: fakeClient([
        { stop_reason: 'tool_use', content: [{ type: 'tool_use', id: 't1', name: 'request_consult', input: consultInput }] },
        { stop_reason: 'end_turn', content: [{ type: 'text', text: '접수했어요' }] },
      ], []),
    });
    assert.equal(called, 0);
    assert.equal(Object.values(db.root.companies[C].customerChat.leads)[0].sheet, undefined);
  }
});

test('생년월일이 이상하면 Claude에게 다시 확인하게 함', async () => {
  const db = fakeDb(baseData()); const seen = [];
  await answer({ c: C, sid: 's_consult4', text: '신청' }, {
    db, messaging: { sendEachForMulticast: async () => {} },
    makeClient: fakeClient([
      { stop_reason: 'tool_use', content: [{ type: 'tool_use', id: 't1', name: 'request_consult', input: { ...consultInput, birthdate: '850315' } }] },
      { stop_reason: 'end_turn', content: [{ type: 'text', text: '생년월일 8자리로 알려주세요' }] },
    ], seen),
  });
  assert.equal(seen[1].messages.at(-1).content[0].is_error, true);
  assert.equal(db.root.companies[C].customerChat.leads, undefined);
});

// ── Gemini 키 (사장님 현황판 AI 설정) ──
const GEMINI_KEY = 'AQ.test-gemini-key-000000000000';
function geminiData() { const d = baseData(); d.companies[C].aiSettings.key = GEMINI_KEY; d.companies[C].customerChat.settings.sheetUrl = SHEET; return d; }
function fakeGemini(replies, calls) {
  return async (url, opt) => {
    if (url.startsWith('https://script.google.com/')) return { status: 200, json: async () => ({ success: true }) };
    calls.push({ url, headers: opt.headers, body: JSON.parse(opt.body) });
    const r = replies.shift();
    return { status: r.status || 200, json: async () => r.data };
  };
}

test('Gemini 키: 상담 신청 도구를 부르고 결과를 돌려준 뒤 답함 (바쁜 모델은 건너뜀)', async () => {
  const db = fakeDb(geminiData()); const calls = [];
  const reply = await answer({ c: C, sid: 's_gem1', text: '네 문자 받을게요' }, {
    db, messaging: { sendEachForMulticast: async () => {} },
    makeClient: () => { throw new Error('Claude를 부르면 안 됨'); },
    fetchImpl: fakeGemini([
      { status: 429, data: { error: { message: 'busy' } } },
      { data: { candidates: [{ finishReason: 'STOP', content: { role: 'model', parts: [{ functionCall: { name: 'request_consult', args: consultInput }, thoughtSignature: 'sig1' }] } }] } },
      { data: { candidates: [{ finishReason: 'STOP', content: { role: 'model', parts: [{ text: '상담 신청 접수했어요! 담당자가 연락드릴게요.' }] } }] } },
    ], calls),
  });
  assert.equal(reply, '상담 신청 접수했어요! 담당자가 연락드릴게요.');
  // 첫 모델이 바빠서 두 번째 모델로, 도구 결과 뒤에도 같은 모델로 이어감
  assert.match(calls[0].url, /gemini-3\.1-flash-lite:generateContent$/);
  assert.match(calls[1].url, /gemini-3\.5-flash-lite:generateContent$/);
  assert.equal(calls[2].url, calls[1].url);
  assert.equal(calls[1].headers['x-goog-api-key'], GEMINI_KEY);
  // 안내문·도구가 같이 가고, 도구 정의는 Gemini 형식
  const body = calls[1].body;
  assert.match(body.systemInstruction.parts[0].text, /request_consult/);
  const decl = body.tools[0].functionDeclarations.find(f => f.name === 'request_consult');
  assert.equal(decl.parameters.type, 'OBJECT');
  assert.equal(decl.parameters.properties.agreeMarketing.type, 'BOOLEAN');
  assert.equal(decl.parameters.additionalProperties, undefined);
  // 두 번째 호출: 모델 답(서명 포함)을 그대로 + 도구 결과
  const c2 = calls[2].body.contents;
  assert.equal(c2.at(-2).parts[0].thoughtSignature, 'sig1');
  assert.equal(c2.at(-1).parts[0].functionResponse.name, 'request_consult');
  assert.match(c2.at(-1).parts[0].functionResponse.response.result, /접수/);
  const lead = Object.values(db.root.companies[C].customerChat.leads)[0];
  assert.equal(lead.type, 'consult');
  assert.equal(lead.sheet, 'saved');
  assert.deepEqual(db.root.companies[C].customerChat.sessions.s_gem1.messages.map(m => m.role), ['user', 'assistant']);
});

test('Gemini 키: 이전 대화는 user/model 로 이어 붙임', async () => {
  const d = geminiData();
  d.companies[C].customerChat.sessions = { s_gem2: { messages: [{ role: 'user', text: '카니발 돼요?' }, { role: 'assistant', text: '언제 필요하세요?' }] } };
  const db = fakeDb(d); const calls = [];
  await answer({ c: C, sid: 's_gem2', text: '토요일이요' }, {
    db, messaging: { sendEachForMulticast: async () => {} },
    fetchImpl: fakeGemini([{ data: { candidates: [{ content: { parts: [{ text: '토요일 가능해요.' }] } }] } }], calls),
  });
  assert.deepEqual(calls[0].body.contents.map(x => [x.role, x.parts[0].text]),
    [['user', '카니발 돼요?'], ['model', '언제 필요하세요?'], ['user', '토요일이요']]);
});

test('Gemini 키: 모든 모델이 실패하면 고객에게 잠시 후 다시 안내', async () => {
  const db = fakeDb(geminiData()); const calls = [];
  await assert.rejects(answer({ c: C, sid: 's_gem3', text: '안녕' }, {
    db, messaging: {},
    fetchImpl: fakeGemini(Array(5).fill({ status: 503, data: { error: { message: 'overloaded' } } }), calls),
  }), e => e instanceof ChatError && e.status === 502);
  assert.equal(calls.length, 5);
});

const GEMINI_KEY2 = 'AQ.test-gemini-key-222222222222';

test('두 번째 키: 첫 번째 키가 모두 실패하면 두 번째 키로 답함', async () => {
  const d = geminiData(); d.companies[C].aiSettings.key2 = GEMINI_KEY2;
  const db = fakeDb(d); const calls = [];
  const reply = await answer({ c: C, sid: 's_key2a', text: '안녕' }, {
    db, messaging: {},
    fetchImpl: fakeGemini([
      ...Array(5).fill({ status: 429, data: { error: { message: 'quota' } } }),
      { data: { candidates: [{ content: { parts: [{ text: '안녕하세요!' }] } }] } },
    ], calls),
  });
  assert.equal(reply, '안녕하세요!');
  assert.equal(calls.length, 6);
  assert.equal(calls[4].headers['x-goog-api-key'], GEMINI_KEY);
  assert.equal(calls[5].headers['x-goog-api-key'], GEMINI_KEY2);
});

test('두 번째 키: 첫 번째 키로 이미 접수됐으면 다시 하지 않고 접수 안내', async () => {
  const d = geminiData(); d.companies[C].aiSettings.key2 = GEMINI_KEY2;
  const db = fakeDb(d); const calls = [];
  const reply = await answer({ c: C, sid: 's_key2b', text: '네 문자 받을게요' }, {
    db, messaging: { sendEachForMulticast: async () => {} },
    fetchImpl: fakeGemini([
      { data: { candidates: [{ content: { role: 'model', parts: [{ functionCall: { name: 'request_consult', args: consultInput } }] } }] } },
      { status: 503, data: { error: { message: 'overloaded' } } },
    ], calls),
  });
  assert.match(reply, /접수했어요/);
  assert.equal(calls.length, 2);
  assert.ok(calls.every(x => x.headers['x-goog-api-key'] === GEMINI_KEY));
  assert.equal(Object.values(db.root.companies[C].customerChat.leads).length, 1);
});

test('AI 키가 없으면 준비 중 안내', async () => {
  const d = baseData(); delete d.companies[C].aiSettings;
  await assert.rejects(answer({ c: C, sid: 's_gem4', text: '안녕' }, { db: fakeDb(d), messaging: {} }),
    e => e instanceof ChatError && e.status === 503);
});
