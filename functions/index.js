/**
 * 고객 응대 서버 (Firebase Cloud Functions) - 채널별 입구
 * ─────────────────────────────────────────────
 * 실제 답변 로직은 answer.js 에 있고, 여기는 채널별로 요청을 받아 넘기기만 합니다.
 *  - inquiryChat: 웹 채팅(chat.html)
 *  - inquiryForm: 홈페이지 상담 신청 폼 (pangcar/index.html)
 */
import { onRequest } from 'firebase-functions/v2/https';
import { logger } from 'firebase-functions';
import { initializeApp } from 'firebase-admin/app';
import { answer, ChatError } from './answer.js';
import { submitForm } from './homepage.js';

initializeApp({ databaseURL: 'https://fleet-board-f2345-default-rtdb.asia-southeast1.firebasedatabase.app' });

// 웹 채팅(chat.html)용
export const inquiryChat = onRequest(
  { region: 'asia-northeast3', cors: true, timeoutSeconds: 60, memory: '256MiB', maxInstances: 5 },
  async (req, res) => {
    if (req.method !== 'POST') return res.status(405).json({ error: 'POST만 가능해요' });
    const { c, sid, text } = req.body || {};
    try {
      res.json({ reply: await answer({ c, sid, text, channel: 'web' }) });
    } catch (e) {
      if (e instanceof ChatError) return res.status(e.status).json({ error: e.message });
      logger.error('처리 실패', { message: e?.message });
      res.status(500).json({ error: '잠시 후 다시 시도해주세요.' });
    }
  },
);

function sendError(res, e) {
  if (e instanceof ChatError) return res.status(e.status).json({ error: e.message });
  logger.error('처리 실패', { message: e?.message });
  res.status(500).json({ error: '잠시 후 다시 시도해주세요.' });
}

// 홈페이지 상담 신청 폼 (POST {c, form})
export const inquiryForm = onRequest(
  { region: 'asia-northeast3', cors: true, timeoutSeconds: 30, memory: '256MiB', maxInstances: 5 },
  async (req, res) => {
    if (req.method !== 'POST') return res.status(405).json({ error: 'POST만 가능해요' });
    try {
      const { c, form } = req.body || {};
      res.json(await submitForm({ c, form }));
    } catch (e) { sendError(res, e); }
  },
);
