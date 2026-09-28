import { test } from 'node:test';
import assert from 'node:assert/strict';
import { cleanForm, formSchedule, submitForm } from '../homepage.js';
import { ChatError } from '../answer.js';
import { buildSystemPrompt, summarizeFleet } from '../chat-core.js';

const good = { rentalType: '장기대여', rentalPeriod: '12', rentalRegion: '장성', carClass: '소형',
  name: '홍길동', phone: '010-1234-5678', birthdate: '19900101', userMsg: '빨리 연락주세요', agreePrivacy: true, agreeMarketing: false };

test('폼 검사: 동의·필수값·연락처', () => {
  assert.equal(cleanForm(good).name, '홍길동');
  assert.throws(() => cleanForm({ ...good, agreePrivacy: false }), ChatError);
  assert.throws(() => cleanForm({ ...good, phone: '123' }), ChatError);
  assert.throws(() => cleanForm({ ...good, rentalType: '아무거나' }), ChatError);
  assert.throws(() => cleanForm({ ...good, birthdate: '1990' }), ChatError);
});

test('폼 → 오늘 날짜 현황판 일정', () => {
  const s = formSchedule(cleanForm(good), '2026-09-28');
  assert.equal(s.date, '2026-09-28');
  assert.equal(s.title, '📝 상담신청 장기 12개월 소형 · 홍길동');
  assert.match(s.memo, /010-1234-5678/);
  assert.ok(!s.memo.includes('\n'));
});

test('폼 접수 → 문의 목록 + 일정 + 메신저 + 푸시', async () => {
  const root = { companies: { c_test1: { customerChat: { settings: { enabled: true } }, members: { u1: { pushToken: 't' } } } } };
  let n = 0;
  const parts = p => p.split('/').filter(Boolean);
  const read = p => parts(p).reduce((o, k) => o?.[k], root);
  const write = (p, v) => { const ks = parts(p); let o = root; for (const k of ks.slice(0, -1)) o = (o[k] ??= {}); o[ks.at(-1)] = v; };
  const ref = p => ({ key: parts(p).at(-1), get: async () => ({ val: () => read(p) ?? null }), set: async v => write(p, v),
    push: v => { const r = ref(p + '/k' + (++n)); return v === undefined ? r : r.set(v).then(() => r); },
    transaction: async fn => { const v = fn(read(p)); write(p, v); return { snapshot: { val: () => v } }; } });
  const pushes = [];
  await submitForm({ c: 'c_test1', form: good }, { db: { ref }, messaging: { sendEachForMulticast: async m => pushes.push(m) } });
  const co = root.companies.c_test1;
  const lead = Object.values(co.customerChat.leads)[0];
  assert.equal(lead.type, 'form');
  assert.match(co.schedules[lead.scheduleKey].title, /상담신청 장기 12개월/);
  assert.match(Object.values(co.chat)[0].text, /홈페이지 상담신청/);
  assert.equal(pushes.length, 1);
});

test('AI 안내문: 대수를 말하지 말라는 규칙 포함', () => {
  const p = buildSystemPrompt({ businessName: 'x', summary: summarizeFleet([{ model: 'K5', status: '대기' }]), today: '2026-09-28' });
  assert.match(p, /대수나 남은 대수.*절대 말하지 않습니다/);
});
