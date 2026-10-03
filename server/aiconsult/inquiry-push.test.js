'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { sendInquiryPush } = require('./inquiry-push');
test('legacy keeps its message while native gets a private-data-free inquiry route', async () => {
  const sent = [];
  await sendInquiryPush({ sendEachForMulticast: async value => sent.push(value) }, {
    a: { pushToken: 'old', nativePushTokens: { install: { token: 'new', platform: 'android-native' } } },
    b: { pushToken: 'new', nativePushTokens: { duplicate: { token: 'new', platform: 'android-native' }, bad: { token: 'ignored', platform: 'other' } } }
  }, 'example', 'inquiry_example', '예시 상담');
  assert.deepEqual(sent[0].tokens, ['old']);
  assert.deepEqual(sent[0].notification, { title: '📞 새 상담 신청', body: '예시 상담' });
  assert.deepEqual(sent[1].tokens, ['new']);
  assert.deepEqual(sent[1].data, { kind: 'inquiry', companyId: 'example', messageId: 'inquiry_example' });
  assert.equal(sent[1].notification, undefined);
});
test('large companies split messages at the provider batch limit', async () => {
  const sizes = [];
  const members = Object.fromEntries(Array.from({ length: 501 }, (_, id) => [id, { pushToken: 'token-' + id }]));
  await sendInquiryPush({ sendEachForMulticast: async value => sizes.push(value.tokens.length) }, members, 'example', 'id', 'example');
  assert.deepEqual(sizes, [500, 1]);
});
