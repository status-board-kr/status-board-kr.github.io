/**
 * 홈페이지(pangcar/index.html) 상담 신청 폼 접수
 *  1. 입력값 검사 (필수 항목, 연락처·생년월일 형식, 개인정보 동의)
 *  2. customerChat/leads 에 type:'form' 으로 저장 → inquiry-admin.html 에서 확인
 *  3. 현황판 일정(schedules)에 오늘 날짜로 "📝 상담신청 …" 등록 (할 일로 보이게)
 *  4. 현황판 직원 메신저 기록 + 직원 폰 푸시
 * 기존처럼 구글 시트(Apps Script)로 보내는 건 홈페이지가 따로 합니다.
 */
import { logger } from 'firebase-functions';
import { getDatabase } from 'firebase-admin/database';
import { getMessaging } from 'firebase-admin/messaging';
import { isValidId, validPhone, todayKST } from './chat-core.js';
import { ChatError, notifyStaff } from './answer.js';

export const DEFAULT_DAILY_FORM_LIMIT = 100;
const clip = (v, n) => String(v ?? '').trim().slice(0, n);

/** 폼 입력을 검사하고 저장할 모양으로 정리. 잘못되면 ChatError. */
export function cleanForm(f = {}) {
  const x = {
    rentalType: clip(f.rentalType, 10),
    rentalPeriod: clip(f.rentalPeriod, 20),
    rentalRegion: clip(f.rentalRegion, 20),
    carClass: clip(f.carClass, 20),
    name: clip(f.name, 30),
    phone: clip(f.phone, 20),
    birthdate: clip(f.birthdate, 8),
    userMsg: clip(f.userMsg, 300),
    agreeMarketing: f.agreeMarketing === true || f.agreeMarketing === '동의',
  };
  if (f.agreePrivacy !== true) throw new ChatError(400, '개인정보 수집·이용에 동의해주세요.');
  if (!['단기대여', '장기대여'].includes(x.rentalType)) throw new ChatError(400, '대여 유형을 선택해주세요.');
  if (!x.rentalPeriod || !x.rentalRegion || !x.name) throw new ChatError(400, '필수 항목을 모두 입력해주세요.');
  if (!validPhone(x.phone)) throw new ChatError(400, '연락처를 확인해주세요.');
  if (x.birthdate && !/^\d{8}$/.test(x.birthdate)) throw new ChatError(400, '생년월일 8자리를 확인해주세요.');
  return x;
}

export function periodText(x) {
  const unit = x.rentalType === '단기대여' ? '일' : '개월';
  return /^\d+$/.test(x.rentalPeriod) ? x.rentalPeriod + unit : x.rentalPeriod;
}

export function formSchedule(x, today) {
  const kind = x.rentalType === '단기대여' ? '단기' : '장기';
  const memo = [
    `${x.name} ${x.phone}`,
    `${x.rentalType} ${periodText(x)}`,
    x.carClass ? `희망 ${x.carClass}` : '',
    `지역 ${x.rentalRegion}`,
    x.userMsg ? `요청: ${x.userMsg}` : '',
    '홈페이지 상담신청·연락 필요',
  ].filter(Boolean).join(' · ');
  return {
    title: `📝 상담신청 ${kind} ${periodText(x)}${x.carClass ? ' ' + x.carClass : ''} · ${x.name}`.slice(0, 100),
    date: today,
    repeat: false,
    memo,
    source: 'homepageForm',
  };
}

export function formAlertText(x) {
  return `📝 홈페이지 상담신청: ${x.rentalType} ${periodText(x)}${x.carClass ? ' · ' + x.carClass : ''} · ${x.rentalRegion} · ${x.name} ${x.phone}`;
}

/** 홈페이지 상담 폼 한 건 접수 */
export async function submitForm({ c, form }, deps = {}) {
  const { db = getDatabase(), messaging = getMessaging() } = deps;
  if (!isValidId(c)) throw new ChatError(400, '잘못된 요청이에요');
  const x = cleanForm(form);
  const base = `companies/${c}`;
  const settings = (await db.ref(`${base}/customerChat/settings`).get()).val() || {};
  if (!settings.enabled) throw new ChatError(403, '지금은 온라인 접수를 받지 않아요. 전화로 문의해주세요.');

  const today = todayKST();
  const count = await db.ref(`${base}/customerChat/usage/form-${today}`).transaction(n => (n || 0) + 1);
  if (count.snapshot.val() > (Number(settings.dailyFormLimit) || DEFAULT_DAILY_FORM_LIMIT)) {
    throw new ChatError(429, '오늘 접수가 많아요. 전화로 문의해주세요.');
  }

  const leadRef = db.ref(`${base}/customerChat/leads`).push();
  const schedRef = db.ref(`${base}/schedules`).push();
  const sched = formSchedule(x, today);
  const now = new Date().toISOString();
  await Promise.all([
    leadRef.set({ ...x, type: 'form', channel: 'homepage', createdAt: now, done: false, scheduleKey: schedRef.key }),
    schedRef.set({ ...sched, leadKey: leadRef.key }),
  ]);
  await notifyStaff(db, messaging, base, formAlertText(x));
  logger.info('홈페이지 상담신청 접수', { c });
  return { ok: true };
}
