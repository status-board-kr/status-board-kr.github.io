/**
 * 고객 문의 채팅 서버 (Firebase Cloud Functions)
 * ─────────────────────────────────────────────
 * chat.html(고객용 채팅 페이지)이 이 함수를 부릅니다.
 *
 * 하는 일:
 *  1. 업체가 고객 문의 채팅을 켜뒀는지 확인 (companies/{id}/customerChat/settings/enabled)
 *  2. 현황판 차량 데이터를 읽어서 "차종별 가능 대수" 요약만 만듦 (차량번호·고객정보·금액 제외)
 *  3. Claude가 요약 + 가격 안내를 보고 답변
 *  4. 고객이 상담/예약을 원하면 연락처를 받아 문의 목록(customerChat/leads)에 저장
 *  5. 대화는 customerChat/sessions/{대화방ID} 에 저장 → inquiry-admin.html 에서 확인
 *
 * Claude 키는 현황판 "🤖 AI 설정"에 넣은 업체 공용 Claude 키(aiSettings)를 그대로 씁니다.
 */
import { onRequest } from 'firebase-functions/v2/https';
import { logger } from 'firebase-functions';
import { initializeApp } from 'firebase-admin/app';
import { getDatabase } from 'firebase-admin/database';
import Anthropic from '@anthropic-ai/sdk';
import {
  MAX_TEXT, MAX_TURNS, DEFAULT_DAILY_LIMIT, CALLBACK_TOOL,
  isValidId, summarizeFleet, buildSystemPrompt, validPhone, todayKST,
} from './chat-core.js';

initializeApp({ databaseURL: 'https://fleet-board-f2345-default-rtdb.asia-southeast1.firebasedatabase.app' });

// 현황판 index.html 의 CLAUDE_MODEL 과 같은 모델
const MODEL = 'claude-sonnet-5';
const LONG_TERM_BRANCH = '장기'; // index.html APP_CONFIG.longTermBranch 와 같게

const fail = (res, status, message) => res.status(status).json({ error: message });

export const inquiryChat = onRequest(
  { region: 'asia-northeast3', cors: true, timeoutSeconds: 60, memory: '256MiB', maxInstances: 5 },
  async (req, res) => {
    if (req.method !== 'POST') return fail(res, 405, 'POST만 가능해요');
    const { c, sid, text } = req.body || {};
    if (!isValidId(c) || !isValidId(sid)) return fail(res, 400, '잘못된 요청이에요');
    const msg = typeof text === 'string' ? text.trim() : '';
    if (!msg) return fail(res, 400, '메시지를 입력해주세요');
    if (msg.length > MAX_TEXT) return fail(res, 400, `메시지는 ${MAX_TEXT}자까지 보낼 수 있어요`);

    const db = getDatabase();
    const base = `companies/${c}`;
    const [settingsSnap, nameSnap, aiSnap, vehiclesSnap, sessionSnap] = await Promise.all([
      db.ref(`${base}/customerChat/settings`).get(),
      db.ref(`${base}/profile/name`).get(),
      db.ref(`${base}/aiSettings/key`).get(),
      db.ref(`${base}/vehicles`).get(),
      db.ref(`${base}/customerChat/sessions/${sid}`).get(),
    ]);
    const settings = settingsSnap.val() || {};
    if (!settings.enabled) return fail(res, 403, '지금은 채팅 상담을 운영하지 않아요. 전화로 문의해주세요.');
    const apiKey = aiSnap.val();
    if (typeof apiKey !== 'string' || !apiKey.startsWith('sk-ant-')) {
      logger.warn('Claude 키 없음', { c });
      return fail(res, 503, '상담 준비 중이에요. 잠시 후 다시 시도해주세요.');
    }

    const session = sessionSnap.val() || {};
    const history = Array.isArray(session.messages) ? session.messages : [];
    if (history.filter(m => m.role === 'user').length >= MAX_TURNS) {
      return fail(res, 429, '대화가 너무 길어졌어요. 새로고침 후 새 대화로 문의해주세요.');
    }

    // 업체당 하루 답변 수 제한 (요금 폭탄 방지)
    const limit = Number(settings.dailyLimit) || DEFAULT_DAILY_LIMIT;
    const today = todayKST();
    const usage = await db.ref(`${base}/customerChat/usage/${today}`).transaction(n => (n || 0) + 1);
    if (usage.snapshot.val() > limit) return fail(res, 429, '오늘 채팅 상담이 많아 마감됐어요. 전화로 문의해주세요.');

    const system = buildSystemPrompt({
      businessName: settings.businessName || nameSnap.val(),
      settings,
      summary: summarizeFleet(vehiclesSnap.val(), { longTermBranch: LONG_TERM_BRANCH }),
      today,
    });

    const client = new Anthropic({ apiKey, timeout: 45_000, maxRetries: 1 });
    const messages = [
      ...history.map(m => ({ role: m.role, content: m.text })),
      { role: 'user', content: msg },
    ];
    let reply = '';
    let lead = null;
    try {
      // 도구 호출이 있으면 결과를 돌려주고 한 번 더 (최대 3회)
      for (let i = 0; i < 3; i++) {
        const response = await client.messages.create({
          model: MODEL,
          max_tokens: 4000,
          output_config: { effort: 'low' },
          system,
          tools: [CALLBACK_TOOL],
          messages,
        });
        if (response.stop_reason === 'refusal') { reply = '죄송해요, 그 문의는 도와드리기 어려워요.'; break; }
        const text = response.content.filter(b => b.type === 'text').map(b => b.text).join('').trim();
        const calls = response.content.filter(b => b.type === 'tool_use');
        if (response.stop_reason !== 'tool_use' || !calls.length) { reply = text; break; }

        messages.push({ role: 'assistant', content: response.content });
        const results = [];
        for (const call of calls) {
          const input = call.input || {};
          if (call.name !== CALLBACK_TOOL.name || !validPhone(input.phone)) {
            results.push({ type: 'tool_result', tool_use_id: call.id, is_error: true,
              content: '연락처가 올바르지 않아요. 고객에게 전화번호를 다시 확인하세요.' });
            continue;
          }
          lead = {
            name: String(input.name || '').slice(0, 50),
            phone: String(input.phone).slice(0, 30),
            request: String(input.request || '').slice(0, 500),
            sid, createdAt: new Date().toISOString(), done: false,
          };
          await db.ref(`${base}/customerChat/leads`).push(lead);
          results.push({ type: 'tool_result', tool_use_id: call.id, content: '담당자에게 전달했습니다.' });
        }
        messages.push({ role: 'user', content: results });
      }
    } catch (e) {
      logger.error('Claude 호출 실패', { c, status: e?.status, message: e?.message });
      return fail(res, 502, '답변을 만드는 중 문제가 생겼어요. 잠시 후 다시 시도해주세요.');
    }
    if (!reply) reply = lead ? '담당자에게 전달했어요. 곧 연락드릴게요!' : '죄송해요, 다시 한 번 말씀해주시겠어요?';

    const now = new Date().toISOString();
    await db.ref(`${base}/customerChat/sessions/${sid}`).update({
      messages: [...history, { role: 'user', text: msg, at: now }, { role: 'assistant', text: reply, at: now }],
      updatedAt: now,
      ...(session.createdAt ? {} : { createdAt: now }),
      ...(lead ? { hasLead: true } : {}),
    });
    res.json({ reply });
  },
);
