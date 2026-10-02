'use strict';
const { createHash } = require('node:crypto');
const hash = value => createHash('sha256').update(String(value)).digest('hex');
const status = keys => ({ gemini: !!keys.geminiKey, grok: !!keys.grokKey });

// One bounded record per company; all instances share these counters.
async function consumeQuota(db, companyId, identity, now, { publicChat = false, sessionId = '' } = {}) {
  const minute = Math.floor(now / 60000), day = Math.floor(now / 86400000);
  const result = await db.ref('privateAiUsage/' + companyId).transaction(current => {
    const v = current || {};
    const daily = v.day === day ? (v.daily || 0) : 0;
    const hits = v.minute === minute ? (v.hits || {}) : {};
    const total = v.minute === minute ? (v.total || 0) : 0;
    const id = hash(identity);
    // Per-user/IP, company/minute and company/day caps cannot be bypassed by creating sessions.
    if((hits[id] || 0) >= 12 || total >= 60 || daily >= 1000) return;
    const sessions = Object.fromEntries(Object.entries(v.sessions || {}).filter(([, s]) => s.at > now - 86400000));
    if(publicChat) {
      const sid = hash(sessionId), old = sessions[sid];
      if(old && old.turns >= 40) return;
      if(!old && Object.keys(sessions).length >= 1000) return;
      sessions[sid] = { turns: (old ? old.turns : 0) + 1, at: now };
    }
    return { day, daily: daily + 1, minute, hits: { ...hits, [id]: (hits[id] || 0) + 1 }, total: total + 1, sessions };
  });
  return result.committed;
}

async function getKeys(db, companyId) {
  return (await db.ref('privateAiSettings/' + companyId).once('value')).val() || {};
}
async function fetchJson(deps, url, options, timeoutMs = 15000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), Math.max(1, Math.min(15000, timeoutMs)));
  try {
    const r = await deps.fetch(url, { ...options, signal: controller.signal });
    if(!r.ok && !(r.status >= 200 && r.status < 300)) throw new Error('provider failed');
    return await r.json();
  } finally { clearTimeout(timer); }
}
async function vision(deps, keys, prompt, images, geminiModels, grokModels) {
  // Preserve provider/model fallback, within one bounded 50-second request.
  const deadline = Date.now() + 50000;
  if(keys.geminiKey) {
    for(const model of geminiModels) {
    if(Date.now() >= deadline) break;
    try {
      const d = await fetchJson(deps, 'https://generativelanguage.googleapis.com/v1beta/models/' + model + ':generateContent', {
        method: 'POST', headers: { 'x-goog-api-key': keys.geminiKey, 'content-type': 'application/json' },
        body: JSON.stringify({ contents: [{ parts: [...images.map(data => ({ inline_data: { mime_type: 'image/jpeg', data } })), { text: prompt }] }],
          generationConfig: { responseMimeType: 'application/json', temperature: 0, maxOutputTokens: 1200 } })
      }, deadline - Date.now());
      const text = (d.candidates?.[0]?.content?.parts || []).map(p => p.text || '').join('');
      if(text) return { text, reason: '' };
    } catch { /* Try the next model without exposing provider diagnostics or keys. */ }
    }
  }
  if(keys.grokKey) {
    for(const model of grokModels) {
    if(Date.now() >= deadline) break;
    try {
      const d = await fetchJson(deps, 'https://api.x.ai/v1/chat/completions', {
        method: 'POST', headers: { Authorization: 'Bearer ' + keys.grokKey, 'content-type': 'application/json' },
        body: JSON.stringify({ model, max_tokens: 1200, response_format: { type: 'json_object' }, temperature: 0,
          messages: [{ role: 'user', content: [...images.map(data => ({ type: 'image_url', image_url: { url: 'data:image/jpeg;base64,' + data } })), { type: 'text', text: prompt }] }] })
      }, deadline - Date.now());
      const text = d.choices?.[0]?.message?.content;
      if(text) return { text, reason: '' };
    } catch { /* Try the next model. */ }
    }
  }
  return { text: null, reason: '사진 자동 인식에 실패했어요. 잠시 후 다시 시도해주세요.' };
}
async function handleStaff(req, res, { db, deps, admin, geminiModels, grokModels }) {
  const body = req.body || {}, companyId = String(body.companyId || '');
  const fail = (code, reply) => res.status(code).json({ reply });
  if(!/^[A-Za-z0-9_-]{4,64}$/.test(companyId)) return fail(400, '업체 정보를 확인해주세요.');
  const token = String(req.headers?.authorization || '').match(/^Bearer (.+)$/i)?.[1];
  if(!token) return fail(401, '로그인이 필요해요.');
  let user;
  try { db(); user = await (deps.verifyIdToken || (t => admin.auth().verifyIdToken(t, true)))(token); }
  catch { return fail(401, '다시 로그인해주세요.'); }
  const database = db();
  const member = (await database.ref('companies/' + companyId + '/members/' + user.uid).once('value')).val();
  if(!member) return fail(403, '업체 접근 권한이 없어요.');
  if(body.operation === 'ai-config') {
    if(member.role !== 'owner') return fail(403, '관리자만 AI 설정을 바꿀 수 있어요.');
    const existing = await getKeys(database, companyId);
    const keys = { geminiKey: body.geminiKey === undefined ? (existing.geminiKey || '') : body.geminiKey, grokKey: body.grokKey === undefined ? (existing.grokKey || '') : body.grokKey };
    if(Object.values(keys).some(k => typeof k !== 'string' || (k && (k.length < 20 || k.length > 300 || /\s/.test(k))))) return fail(400, '키 형식을 확인해주세요.');
    await database.ref().update({ ['privateAiSettings/' + companyId]: { ...keys, updatedAt: new Date(deps.now()).toISOString(), updatedBy: user.uid },
      ['companies/' + companyId + '/aiSettings']: null });
    return res.json(status(keys));
  }
  const keys = await getKeys(database, companyId);
  if(body.operation === 'ai-status') return res.json(status(keys));
  if(body.operation !== 'ai-vision') return fail(400, '지원하지 않는 요청이에요.');
  if(typeof body.prompt !== 'string' || !body.prompt.trim() || body.prompt.length > 12000 ||
    !Array.isArray(body.images) || body.images.length < 1 || body.images.length > 4 ||
    body.images.some(s => typeof s !== 'string' || s.length > 550000 || !/^[A-Za-z0-9+/]+={0,2}$/.test(s))) return fail(400, '사진과 요청 내용을 확인해주세요.');
  if(!keys.geminiKey && !keys.grokKey) return res.json({ text: null, reason: 'nokey' });
  if(!await consumeQuota(database, companyId, 'user:' + user.uid, deps.now())) return fail(429, 'AI 사용량 한도에 도달했어요. 잠시 후 다시 시도해주세요.');
  return res.json(await vision(deps, keys, body.prompt, body.images, geminiModels, grokModels));
}
module.exports = { consumeQuota, getKeys, handleStaff, vision };
