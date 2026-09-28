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

import { bookingSchedule, staffAlertText, validDate, validTime, TOOLS } from '../chat-core.js';

test('예약 요청은 현황판 일정 모양으로 변환 (제목에 기간·시간)', () => {
  const s = bookingSchedule({ name: '홍길동', phone: '010-1234-5678', model: '카니발', startDate: '2026-10-03', startTime: '10:00', endDate: '2026-10-05', endTime: '18:30', period: '2박3일', note: '' });
  assert.equal(s.date, '2026-10-03');
  assert.equal(s.repeat, false);
  assert.equal(s.title, '📅 예약요청 10/3 10시 ~ 10/5 18시30분 카니발 · 홍길동');
  assert.ok(!s.memo.includes('\n'));
  assert.match(s.memo, /010-1234-5678/);
  // 시간 모르면 날짜만, 장기면 기간
  assert.equal(bookingSchedule({ name: 'a', phone: '1', model: 'K5', startDate: '2026-11-01', startTime: '', endDate: '', endTime: '', period: '6개월', note: '' }).title, '📅 예약요청 11/1 ~ 6개월 K5 · a');
  assert.match(staffAlertText('booking', { model: '카니발', startDate: '2026-10-03', startTime: '', endDate: '', endTime: '', period: '', name: '홍길동', phone: '010' }), /새 예약 요청: 카니발 10\/3 · 홍길동/);
  assert.ok(validTime('09:30') && validTime('') && !validTime('25:00') && !validTime('9시'));
});

test('날짜 검증', () => {
  assert.ok(validDate('2026-10-03', '2026-09-28'));
  assert.ok(!validDate('2026-09-01', '2026-09-28'));
  assert.ok(!validDate('2026-02-30'));
  assert.ok(!validDate('10/3'));
});

test('도구 정의가 strict 스키마 규칙을 지킴', () => {
  for (const t of TOOLS) {
    assert.equal(t.input_schema.additionalProperties, false);
    assert.deepEqual([...t.input_schema.required].sort(), Object.keys(t.input_schema.properties).sort());
  }
});

import { upcomingReservations, fleetText as fleetText2, summarizeFleet as sf2 } from '../chat-core.js';

test('지난 예약·취소·일반 일정은 빼고, 남은 예약만 AI에게 알려줌', () => {
  const schedules = {
    a: { title: '일반 일정', date: '2026-10-01' },
    b: { resvStatus: 'confirmed', resvModel: '카니발', resvPlate: '34나1111', resvStart: '2026-10-03', resvStartTime: '10:00', resvEnd: '2026-10-05', resvEndTime: '18:00' },
    c: { resvStatus: 'pending', resvModel: '카니발', resvStart: '2026-09-01', resvEnd: '2026-09-02' },
    d: { resvStatus: 'pending', resvModel: 'K5', resvStart: '2026-11-01', resvEnd: '', resvPeriod: '6개월' },
  };
  const r = upcomingReservations(schedules, '2026-09-28');
  assert.deepEqual(r.map(x => x.model), ['카니발', 'K5']);
  assert.equal(r[0].plate, '34나1111');
  const text = fleetText2(sf2([{ model: '카니발', status: '대기' }]), r);
  assert.match(text, /이미 예약 잡힌 기간\(1건\): 10\/3 10시 ~ 10\/5 18시/);
  assert.ok(!text.includes('34나1111'));
});

test('고객 명단 시트 한 줄: 예약 요청도 같은 칸으로, 번호 모양 통일', async () => {
  const { sheetRow, validSheetUrl, validBirthdate, formatPhone } = await import('../chat-core.js');
  const r = sheetRow({ type: 'booking', model: '카니발', startDate: '2026-10-03', startTime: '10:00', endDate: '2026-10-05', endTime: '18:00',
    period: '2박3일', rentalType: '단기대여', rentalRegion: '광주', name: '홍길동', phone: '01012345678', birthdate: '19900101', note: '카시트', agreeMarketing: false },
    'AI상담', new Date('2026-09-28T06:04:00Z'));
  assert.deepEqual(r, { at: '2026-09-28 15:04', via: 'AI상담', kind: '예약요청', rentalType: '단기대여', rentalPeriod: '2박3일',
    startWhen: '10/3 10시 ~ 10/5 18시', carClass: '카니발', budget: '', rentalRegion: '광주', name: '홍길동', phone: '010-1234-5678',
    birthdate: '19900101', request: '카시트', agreeMarketing: '미동의' });
  assert.equal(formatPhone('010 123 4567'), '010-123-4567');
  assert.ok(validSheetUrl('https://script.google.com/macros/s/AKfycbzsNRvT1XHpNUKltdaavQ/exec'));
  assert.ok(!validSheetUrl('https://script.google.com.evil.io/macros/s/AKfycbzsNRvT1XHpNUKltdaavQ/exec'));
  assert.ok(!validSheetUrl('http://script.google.com/macros/s/AKfycbzsNRvT1XHpNUKltdaavQ/exec'));
  assert.ok(validBirthdate('19900101') && !validBirthdate('19901301') && !validBirthdate('900101'));
});
