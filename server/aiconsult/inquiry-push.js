'use strict';
async function sendInquiryPush(messaging, members, companyId, messageId, line) {
  if (!messaging) return;
  const legacy = new Set(), native = new Set();
  for (const member of Object.values(members || {})) {
    if (!member || typeof member !== 'object') continue;
    if (typeof member.pushToken === 'string' && member.pushToken) legacy.add(member.pushToken);
    for (const [id, item] of Object.entries(member.nativePushTokens || {})) {
      if (/^[\w-]{1,80}$/.test(id) && item?.platform === 'android-native' && typeof item.token === 'string' && item.token) native.add(item.token);
    }
  }
  for (const token of native) legacy.delete(token);
  for (const [isNative, tokens] of [[false, [...legacy]], [true, [...native]]]) {
    for (let start = 0; start < tokens.length; start += 500) {
      const payload = { tokens: tokens.slice(start, start + 500), android: { priority: 'high' } };
      if (isNative) payload.data = { kind: 'inquiry', companyId, messageId };
      else {
        payload.notification = { title: '📞 새 상담 신청', body: line.slice(0, 100) };
        payload.android.notification = { channelId: 'fleet_alerts_v2', sound: 'default' };
      }
      await messaging.sendEachForMulticast(payload);
    }
  }
}
module.exports = { sendInquiryPush };
