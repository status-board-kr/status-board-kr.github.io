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
    aiSettings: { key: 'sk-ant-test' },
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
        input: { name: '홍길동', phone: '010-1234-5678', model: '카니발', startDate: '2099-10-03', endDate: '2099-10-05', period: '2박3일', note: '' } }] },
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
  assert.match(sched.title, /예약요청 카니발/);
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
        input: { name: 'a', phone: '010-1234-5678', model: '카니발', startDate: '2000-01-01', endDate: '', period: '', note: '' } }] },
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
