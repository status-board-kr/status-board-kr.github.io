import { test } from 'node:test';
import assert from 'node:assert/strict';
import { summarizeFleet, fleetText, buildSystemPrompt, validPhone, isValidId, todayKST } from '../chat-core.js';

const vehicles = [
  { plate: '12가3456', model: '아반떼', cls: '중형', fuel: '휘발유', status: '대기', customerName: '홍길동', amount: 55 },
  { plate: '12가3457', model: '아반떼', cls: '중형', fuel: '휘발유', status: '', returnDate: '2026-10-05', customerName: '김철수', amount: 60 },
  { plate: '12가3458', model: '아반떼', cls: '중형', fuel: '휘발유', status: '', returnDate: '2026-10-02' },
  { plate: '34나1111', model: '카니발', cls: '승합', fuel: '경유', status: '준비중' },
  { plate: '34나2222', model: '쏘렌토', cls: '중형', fuel: '경유', status: '', type: '장기', customerName: '이영희', amount: 80 },
];

test('차종별로 가능 대수와 가장 빠른 반납일을 요약', () => {
  const s = summarizeFleet(vehicles);
  const avante = s.find(g => g.model === '아반떼');
  assert.equal(avante.available, 1);
  assert.equal(avante.rented, 2);
  assert.equal(avante.nextReturn, '2026-10-02');
  assert.equal(s.find(g => g.model === '카니발').preparing, 1);
  assert.equal(s.find(g => g.model === '쏘렌토').longterm, 1);
});

test('고객에게 가는 안내문에 차량번호·고객명·금액이 없음', () => {
  const p = buildSystemPrompt({ businessName: '테스트렌트', settings: { priceGuide: '아반떼 하루 5만원' }, summary: summarizeFleet(vehicles), today: '2026-09-28' });
  for (const secret of ['12가3456', '34나2222', '홍길동', '김철수', '이영희', '55', '80만']) assert.ok(!p.includes(secret), secret);
  assert.match(p, /아반떼 \(중형, 휘발유\): 바로 가능 1대/);
  assert.match(p, /아반떼 하루 5만원/);
});

test('객체 형태 차량 목록과 빈 목록도 처리', () => {
  assert.equal(summarizeFleet({ a: vehicles[0] }).length, 1);
  assert.equal(fleetText(summarizeFleet(null)), '(등록된 차량이 없습니다)');
});

test('입력 검증', () => {
  assert.ok(validPhone('010-1234-5678'));
  assert.ok(!validPhone('1234'));
  assert.ok(isValidId('c_abc123'));
  assert.ok(!isValidId('../x'));
  assert.equal(todayKST(new Date('2026-09-28T16:00:00Z')), '2026-09-29');
});
