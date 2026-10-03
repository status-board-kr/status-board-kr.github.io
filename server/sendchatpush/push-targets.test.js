const test = require('node:test'), assert = require('node:assert/strict');
const { targets, messages } = require('./push-targets');
test('coinstalled apps both receive without including sender or duplicate tokens', () => {
  const value = targets({ a: { pushToken: 'sender', nativePushTokens: { x: { token: 'sender-native', platform: 'android-native' } } },
    b: { pushToken: 'old', nativePushTokens: { phone: { token: 'new', platform: 'android-native' } } },
    c: { pushToken: 'old', nativePushTokens: { bad: { token: 'ignored' } } } }, 'a');
  assert.deepEqual(value.legacy.map(x => x.token), ['old']); assert.deepEqual(value.native.map(x => x.token), ['new']);
});
test('native payload has routing only; legacy payload stays compatible', () => {
  const content = { companyId: 'synthetic', title: 'sender', text: 'private message', messageId: 'id' }, group = [{ token: 'mock' }];
  const native = messages(group, true, content), legacy = messages(group, false, content);
  assert.equal(native.notification, undefined); assert.equal(JSON.stringify(native).includes('private message'), false);
  assert.equal(legacy.notification.body, content.text); assert.equal(legacy.android.notification.channelId, 'fleet_alerts_v2');
});
