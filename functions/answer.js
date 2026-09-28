/**
 * 고객 응대 핵심 로직 - 모든 채널(웹 채팅, 이후 카카오톡·인스타)이 같은 answer() 를 씁니다.
 *
 *  1. 업체가 고객 응대를 켜뒀는지 확인 (companies/{id}/customerChat/settings/enabled)
 *  2. 현황판 차량 데이터를 "차종별 가능 대수" 요약으로 바꿈 (차량번호·고객정보·금액 제외)
 *  3. Claude가 요약 + 가격 안내를 보고 답변
 *  4. 상담 요청 → customerChat/leads 저장 + 직원 알림
 *     예약 요청 → customerChat/leads 저장 + 현황판 일정(schedules)에 "예약요청" 등록 + 직원 알림 + 고객 명단 시트
 *     상담 신청 → 홈페이지 신청서와 같은 내용을 대화로 받아 leads + 일정 "상담신청" + 직원 알림 + 고객 명단 시트
 *  5. 대화는 customerChat/sessions/{대화방ID} 에 저장 → inquiry-admin.html 에서 확인
 *
 * 직원 알림 = 현황판 직원 채팅에 시스템 메시지 + 직원 폰(앱)으로 푸시.
 * Claude 키는 현황판 "🤖 AI 설정"에 넣은 업체 공용 Claude 키(aiSettings)를 그대로 씁니다.
 */
import { logger } from 'firebase-functions';
import { getDatabase } from 'firebase-admin/database';
import { getMessaging } from 'firebase-admin/messaging';
import Anthropic from '@anthropic-ai/sdk';
import {
  MAX_TEXT, MAX_TURNS, DEFAULT_DAILY_LIMIT, TOOLS,
  isValidId, summarizeFleet, buildSystemPrompt, validPhone, validDate, validTime, todayKST,
  bookingSchedule, staffAlertText, upcomingReservations,
  consultSchedule, consultAlertText, validBirthdate, validSheetUrl, sheetRow,
} from './chat-core.js';

// 현황판 index.html 의 CLAUDE_MODEL 과 같은 모델
const MODEL = 'claude-sonnet-5';
const LONG_TERM_BRANCH = '장기'; // index.html APP_CONFIG.longTermBranch 와 같게
const NOTI_CHANNEL = 'fleet_alerts_v2'; // index.html NOTI_CHANNEL 과 같게 (안드로이드 알림 채널)

/** 고객 응대 중 생기는 오류. status 는 HTTP 코드, message 는 고객에게 그대로 보여줄 문구. */
export class ChatError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

/** 직원 알림: 현황판 직원 채팅에 시스템 메시지 + 앱 푸시. 실패해도 고객 응대는 계속. */
export async function notifyStaff(db, messaging, base, text) {
  try {
    await db.ref(`${base}/chat`).push({ text, uid: 'system', email: '현황판', at: new Date().toISOString(), type: 'system' });
  } catch (e) { logger.warn('직원 채팅 알림 실패', { message: e?.message }); }
  try {
    const members = (await db.ref(`${base}/members`).get()).val() || {};
    const tokens = [...new Set(Object.values(members).map(m => m && m.pushToken).filter(t => typeof t === 'string' && t))];
    if (!tokens.length) return;
    await messaging.sendEachForMulticast({
      tokens,
      notification: { title: '💬 고객 문의', body: text.slice(0, 120) },
      android: { priority: 'high', notification: { channelId: NOTI_CHANNEL } },
      data: { chat: 'true', source: 'customerChat' },
    });
  } catch (e) { logger.warn('푸시 알림 실패', { message: e?.message }); }
}

/**
 * 고객 명단 구글 시트(관리 화면에 넣은 Apps Script 주소)에 한 줄 추가.
 * 주소가 없으면 건너뜀. 실패해도 접수는 그대로 두고, 문의 목록에 결과(sheet: saved|failed)만 남김.
 */
export async function saveToSheet(settings, leadRef, row, fetchImpl = fetch) {
  const url = String(settings.sheetUrl || '').trim();
  if (!validSheetUrl(url)) return;
  let ok = false;
  try {
    const r = await fetchImpl(url, { method: 'POST', body: JSON.stringify(row), signal: AbortSignal.timeout(10_000) });
    const res = await r.json().catch(() => ({}));
    ok = res.success === true;
    if (!ok) logger.warn('고객 명단 시트 저장 실패', { status: r.status, message: res.message });
  } catch (e) { logger.warn('고객 명단 시트 연결 실패', { message: e?.message }); }
  try { await leadRef.update({ sheet: ok ? 'saved' : 'failed' }); } catch (e) { /* 기록 실패는 무시 */ }
}

/**
 * 고객 메시지 하나에 답합니다.
 * @param {{c:string, sid:string, text:string, channel?:string}} input
 * @returns {Promise<string>} 고객에게 보낼 답변
 */
export async function answer({ c, sid, text, channel = 'web' }, deps = {}) {
  const { db = getDatabase(), messaging = getMessaging(), fetchImpl = fetch,
    makeClient = key => new Anthropic({ apiKey: key, timeout: 45_000, maxRetries: 1 }) } = deps;
  if (!isValidId(c) || !isValidId(sid)) throw new ChatError(400, '잘못된 요청이에요');
  const msg = typeof text === 'string' ? text.trim() : '';
  if (!msg) throw new ChatError(400, '메시지를 입력해주세요');
  if (msg.length > MAX_TEXT) throw new ChatError(400, `메시지는 ${MAX_TEXT}자까지 보낼 수 있어요`);

  const base = `companies/${c}`;
  const [settingsSnap, nameSnap, aiSnap, vehiclesSnap, sessionSnap, schedulesSnap] = await Promise.all([
    db.ref(`${base}/customerChat/settings`).get(),
    db.ref(`${base}/profile/name`).get(),
    db.ref(`${base}/aiSettings/key`).get(),
    db.ref(`${base}/vehicles`).get(),
    db.ref(`${base}/customerChat/sessions/${sid}`).get(),
    db.ref(`${base}/schedules`).get(),
  ]);
  const settings = settingsSnap.val() || {};
  if (!settings.enabled) throw new ChatError(403, '지금은 채팅 상담을 운영하지 않아요. 전화로 문의해주세요.');
  const apiKey = aiSnap.val();
  if (typeof apiKey !== 'string' || !apiKey.startsWith('sk-ant-')) {
    logger.warn('Claude 키 없음', { c });
    throw new ChatError(503, '상담 준비 중이에요. 잠시 후 다시 시도해주세요.');
  }

  const session = sessionSnap.val() || {};
  const history = Array.isArray(session.messages) ? session.messages : [];
  if (history.filter(m => m.role === 'user').length >= MAX_TURNS) {
    throw new ChatError(429, '대화가 너무 길어졌어요. 새로고침 후 새 대화로 문의해주세요.');
  }

  // 업체당 하루 답변 수 제한 (요금 폭탄 방지)
  const limit = Number(settings.dailyLimit) || DEFAULT_DAILY_LIMIT;
  const today = todayKST();
  const usage = await db.ref(`${base}/customerChat/usage/${today}`).transaction(n => (n || 0) + 1);
  if (usage.snapshot.val() > limit) throw new ChatError(429, '오늘 채팅 상담이 많아 마감됐어요. 전화로 문의해주세요.');

  const system = buildSystemPrompt({
    businessName: settings.businessName || nameSnap.val(),
    settings,
    summary: summarizeFleet(vehiclesSnap.val(), { longTermBranch: LONG_TERM_BRANCH }),
    reservations: upcomingReservations(schedulesSnap.val(), today),
    today,
  });

  // 도구 실행: 결과 문구(Claude에게 돌려줄 것)와 오류 여부를 돌려줌
  const leads = [];
  const clip = (v, n) => String(v ?? '').trim().slice(0, n);
  async function runTool(name, input) {
    if (!validPhone(input.phone)) return { error: '연락처가 올바르지 않아요. 고객에게 전화번호를 다시 확인하세요.' };
    const common = { name: String(input.name || '').slice(0, 50), phone: String(input.phone).slice(0, 30),
      sid, channel, createdAt: new Date().toISOString(), done: false };
    // 예약·상담 신청에서 같이 받는 정보 (생년월일은 숫자 8자리로)
    const birthdate = String(input.birthdate || '').replace(/\D/g, '');
    if (name !== 'request_callback') {
      if (birthdate && !validBirthdate(birthdate)) return { error: '생년월일은 8자리(예: 19900101)로 확인하세요. 고객이 말하기 싫어하면 빈 문자열로 두세요.' };
      if (!['단기대여', '장기대여'].includes(input.rentalType)) return { error: 'rentalType 은 단기대여 또는 장기대여여야 해요.' };
    }
    const extra = { rentalType: input.rentalType, rentalRegion: clip(input.rentalRegion, 30), birthdate,
      agreeMarketing: input.agreeMarketing === true };
    if (name === 'request_callback') {
      const lead = { ...common, type: 'callback', request: String(input.request || '').slice(0, 500) };
      await db.ref(`${base}/customerChat/leads`).push(lead);
      leads.push(lead);
      await notifyStaff(db, messaging, base, staffAlertText('callback', lead));
      return { ok: '담당자에게 전달했습니다.' };
    }
    if (name === 'request_booking') {
      if (!validDate(input.startDate, today)) return { error: `시작 날짜가 올바르지 않아요. 오늘(${today}) 이후 날짜를 YYYY-MM-DD로 확인하세요.` };
      if (input.endDate && (!validDate(input.endDate) || input.endDate < input.startDate)) return { error: '반납 날짜가 올바르지 않아요. 다시 확인하세요.' };
      if (!validTime(input.startTime || '') || !validTime(input.endTime || '')) return { error: '시간은 HH:MM(24시간) 형식이어야 해요. 다시 확인하세요.' };
      const b = { ...common, type: 'booking', status: 'pending',
        model: String(input.model || '').slice(0, 50), startDate: input.startDate, endDate: input.endDate || '',
        startTime: input.startTime || '', endTime: input.endTime || '',
        period: String(input.period || '').slice(0, 50), note: String(input.note || '').slice(0, 300), ...extra };
      const leadRef = db.ref(`${base}/customerChat/leads`).push();
      const schedRef = db.ref(`${base}/schedules`).push();
      const sched = bookingSchedule(b);
      await Promise.all([
        leadRef.set({ ...b, scheduleKey: schedRef.key, scheduleTitle: sched.title }),
        schedRef.set({ ...sched, leadKey: leadRef.key }),
      ]);
      leads.push(b);
      await Promise.all([
        notifyStaff(db, messaging, base, staffAlertText('booking', b)),
        saveToSheet(settings, leadRef, sheetRow(b, 'AI상담'), fetchImpl),
      ]);
      return { ok: '예약 요청을 접수했고 현황판 일정에 등록했습니다. 아직 확정은 아닙니다.' };
    }
    if (name === 'request_consult') {
      const x = { ...common, type: 'consult', ...extra,
        rentalPeriod: clip(input.rentalPeriod, 20) || '미정', startWhen: clip(input.startWhen, 30),
        carClass: clip(input.carClass, 30), budget: clip(input.budget, 30), userMsg: clip(input.note, 300) };
      const leadRef = db.ref(`${base}/customerChat/leads`).push();
      const schedRef = db.ref(`${base}/schedules`).push();
      const sched = consultSchedule(x, today, 'AI상담');
      await Promise.all([
        leadRef.set({ ...x, scheduleKey: schedRef.key, scheduleTitle: sched.title }),
        schedRef.set({ ...sched, leadKey: leadRef.key }),
      ]);
      leads.push(x);
      await Promise.all([
        notifyStaff(db, messaging, base, consultAlertText(x, 'AI상담')),
        saveToSheet(settings, leadRef, sheetRow(x, 'AI상담'), fetchImpl),
      ]);
      return { ok: '상담 신청을 접수했고 현황판과 담당자에게 전달했습니다.' };
    }
    return { error: '알 수 없는 도구예요.' };
  }

  const client = makeClient(apiKey);
  const messages = [
    ...history.map(m => ({ role: m.role, content: m.text })),
    { role: 'user', content: msg },
  ];
  let reply = '';
  try {
    // 도구 호출이 있으면 결과를 돌려주고 한 번 더 (최대 3회)
    for (let i = 0; i < 3; i++) {
      const response = await client.messages.create({
        model: MODEL,
        max_tokens: 4000,
        output_config: { effort: 'low' },
        system,
        tools: TOOLS,
        messages,
      });
      if (response.stop_reason === 'refusal') { reply = '죄송해요, 그 문의는 도와드리기 어려워요.'; break; }
      const out = response.content.filter(b => b.type === 'text').map(b => b.text).join('').trim();
      const calls = response.content.filter(b => b.type === 'tool_use');
      if (response.stop_reason !== 'tool_use' || !calls.length) { reply = out; break; }

      messages.push({ role: 'assistant', content: response.content });
      const results = [];
      for (const call of calls) {
        const r = await runTool(call.name, call.input || {});
        results.push({ type: 'tool_result', tool_use_id: call.id, content: r.ok || r.error, ...(r.error ? { is_error: true } : {}) });
      }
      messages.push({ role: 'user', content: results });
    }
  } catch (e) {
    logger.error('Claude 호출 실패', { c, status: e?.status, message: e?.message });
    throw new ChatError(502, '답변을 만드는 중 문제가 생겼어요. 잠시 후 다시 시도해주세요.');
  }
  if (!reply) reply = leads.length ? '접수했어요. 담당자가 곧 연락드릴게요!' : '죄송해요, 다시 한 번 말씀해주시겠어요?';

  const now = new Date().toISOString();
  await db.ref(`${base}/customerChat/sessions/${sid}`).update({
    messages: [...history, { role: 'user', text: msg, at: now }, { role: 'assistant', text: reply, at: now }],
    updatedAt: now,
    channel,
    ...(session.createdAt ? {} : { createdAt: now }),
    ...(leads.length ? { hasLead: true } : {}),
    ...(leads.some(l => l.type === 'booking') ? { hasBooking: true } : {}),
  });
  return reply;
}
