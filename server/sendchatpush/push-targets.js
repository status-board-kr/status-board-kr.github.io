'use strict';

function targets(members, sender) {
  const legacy = new Map(), native = new Map();
  for (const [uid, member] of Object.entries(members || {})) {
    if (uid === sender || !member || typeof member !== 'object') continue;
    if (typeof member.pushToken === 'string' && member.pushToken.length > 0)
      legacy.set(member.pushToken, { uid, path: 'pushToken', token: member.pushToken });
    for (const [installation, item] of Object.entries(member.nativePushTokens || {})) {
      if (!/^[\w-]{1,80}$/.test(installation) || !item || item.platform !== 'android-native' || typeof item.token !== 'string' || !item.token) continue;
      native.set(item.token, { uid, path: 'nativePushTokens/' + installation, token: item.token });
    }
  }
  for (const token of native.keys()) legacy.delete(token);
  return { legacy: [...legacy.values()], native: [...native.values()] };
}
function messages(group, native, { companyId, title, text, messageId }) {
  const payload = { tokens: group.map(x => x.token), android: { priority: 'high' }, data: { kind: 'chat', companyId, messageId } };
  if (!native) {
    payload.notification = { title: '\uD83D\uDCAC ' + title, body: text };
    payload.android.notification = { channelId: 'fleet_alerts_v2', sound: 'default', icon: 'ic_tracking' };
  }
  // Native messages are data-only. Android verifies current membership before displaying them.
  return payload;
}
module.exports = { targets, messages };
