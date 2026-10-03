const admin = require('firebase-admin');
if (!admin.apps.length) admin.initializeApp();
const functions = require('@google-cloud/functions-framework');
const { randomUUID } = require('node:crypto');
const { targets, messages } = require('./push-targets');

functions.http('helloHttp', async (req, res) => {
  res.set('Access-Control-Allow-Origin', '*');
  res.set('Access-Control-Allow-Headers', 'Content-Type, Authorization');
  res.set('Access-Control-Allow-Methods', 'POST, OPTIONS');
  if (req.method === 'OPTIONS') return res.status(204).send('');
  if (req.method !== 'POST') return res.status(405).json({ error: 'POST only' });
  try {
    const header = req.get('Authorization') || '';
    const idToken = header.startsWith('Bearer ') ? header.slice(7) : '';
    if (!idToken) return res.status(401).json({ error: 'no auth' });
    let decoded;
    try { decoded = await admin.auth().verifyIdToken(idToken); }
    catch (_) { return res.status(401).json({ error: 'invalid auth' }); }
    const uid = decoded.uid, body = req.body || {};
    const companyId = String(body.companyId || '');
    if (!/^[^.#$\[\]/]{1,160}$/.test(companyId)) return res.status(400).json({ error: 'invalid company' });
    const db = admin.database();
    const company = db.ref('companies/' + companyId);
    if (!(await company.child('members/' + uid).get()).exists()) return res.status(403).json({ error: 'not a member' });
    const members = (await company.child('members').get()).val() || {};
    const grouped = targets(members, uid);
    const messageId = typeof body.messageId === 'string' && /^[^.#$\[\]/]{1,160}$/.test(body.messageId) ? body.messageId : randomUUID();
    const content = { companyId, messageId, title: String(body.title || 'new message').slice(0, 40), text: String(body.body || '').slice(0, 120) };
    let sent = 0, failed = 0, cleaned = 0;
    for (const [native, group] of [[false, grouped.legacy], [true, grouped.native]]) {
      for (let start = 0; start < group.length; start += 500) {
        const batch = group.slice(start, start + 500);
        const result = await admin.messaging().sendEachForMulticast(messages(batch, native, content));
        sent += result.successCount; failed += result.failureCount;
        for (let i = 0; i < result.responses.length; i++) {
          const code = result.responses[i].error?.code;
          if (!['messaging/registration-token-not-registered', 'messaging/invalid-registration-token'].includes(code)) continue;
          const item = batch[i];
          // A refreshed token must not be removed by an older response.
          const outcome = await company.child('members/' + item.uid + '/' + item.path).transaction(value => {
            const token = native ? value?.token : value;
            return token === item.token ? null : undefined;
          });
          if (outcome.committed) cleaned++;
        }
      }
    }
    return res.json({ sent, failed, cleaned });
  } catch (_) {
    // Never include a registration token, chat text, or auth header in production logs.
    console.error('sendchatpush failed');
    return res.status(500).json({ error: 'push delivery failed' });
  }
});
