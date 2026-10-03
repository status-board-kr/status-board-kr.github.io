'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { initializeTestEnvironment, assertSucceeds, assertFails } = require('@firebase/rules-unit-testing');

(async () => {
  // No production app id, credentials, network endpoint, or actual company data.
  const env = await initializeTestEnvironment({ projectId: 'demo-native-fleet', database: {
    host: '127.0.0.1', port: 9000, rules: fs.readFileSync('../reference/firebase-rules-2026-10-03.json', 'utf8')
  } });
  try {
    await env.withSecurityRulesDisabled(async context => {
      await context.database().ref().set({ companies: { example: {
        members: { admin: { role: 'owner', email: 'admin@example.invalid' }, staff: { role: 'staff', email: 'staff@example.invalid', pushToken: 'old-app-token' } },
        vehicles: { '0': { plate: '예시1234', state: '운행중' } },
        wookyJobs: { failed: { plate: '예시1234', status: 'done', result: 'fail' } },
        chat: { wooky_old: { uid: 'system', text: '종결 예시', at: '2026-10-01T00:00:00.000Z' },
          '-new-user': { uid: 'admin', text: '최근 대화 예시', at: '2026-10-03T00:00:00.000Z' } }
      }, other: { members: { outsider: { role: 'owner', email: 'other@example.invalid' } } } } });
    });
    const admin = env.authenticatedContext('admin').database();
    const staff = env.authenticatedContext('staff').database();
    const outsider = env.authenticatedContext('outsider').database();
    const company = database => database.ref('companies/example');
    await assertSucceeds(company(staff).child('members/staff/nativePushTokens/installation').set({ token: 'native-example', platform: 'android-native', at: 'now' }));
    assert.equal((await company(staff).child('members/staff/pushToken').get()).val(), 'old-app-token');
    await assertFails(company(staff).child('members/staff/role').set('owner'));
    await assertFails(company(outsider).child('vehicles').get());
    await assertFails(company(staff).child('profile/company').set('forbidden'));
    await assertSucceeds(company(admin).child('profile/company').set('예시 회사'));

    // Reproduce the parent-write failure and validate the native per-job fix.
    await assertFails(company(admin).child('wookyJobs').transaction(value => ({ ...value, failed: { plate: '예시1234', ...value?.failed, status: 'pending' } })));
    await assertSucceeds(company(admin).child('wookyJobs/failed').transaction(value => ({ plate: '예시1234', ...value, status: 'pending', retryCount: 1 })));
    await assertSucceeds(company(staff).child('chat/native-example').set({ uid: 'system', text: '후속 처리 예시', at: '2026-10-03T01:00:00.000Z' }));
    await assertFails(company(staff).child('chat/native-example/text').set('시스템 글 덮어쓰기'));
    const replay = await assertSucceeds(company(staff).child('chat/native-example').transaction(() => undefined));
    assert.equal(replay.committed, false);
    const latest = await assertSucceeds(company(staff).child('chat').orderByChild('at').limitToLast(1).get());
    assert.equal(Object.keys(latest.val())[0], 'native-example');
    const previous = await assertSucceeds(company(staff).child('chat').orderByChild('at').endBefore('2026-10-03T01:00:00.000Z', 'native-example').limitToLast(1).get());
    assert.equal(Object.keys(previous.val())[0], '-new-user');

    await assertSucceeds(company(staff).update({ 'vehicles/0/_nativeOperations/example': { kind: 'recall' }, 'schedules/example': { plate: '예시1234', done: true }, 'history/example': { savedAt: 'now' }, 'generalSales/example': { plate: '예시1234', amount: 10 } }));
    await assertSucceeds(company(staff).child('photos/example').set({ uid: 'staff', data: 'data:image/jpeg;base64,EXAMPLE', at: 'now' }));
    await assertFails(company(staff).child('photos/too-large').set({ uid: 'staff', data: 'data:image/jpeg;base64,' + 'A'.repeat(600000), at: 'now' }));
    await assertSucceeds(admin.ref('companyDocs/example').set({ forms: { quote: { fields: { plate: '예시1234' } } } }));
    await assertFails(staff.ref('companyDocs/example').get());
    await assertSucceeds(company(admin).child('members/staff').remove());
    await assertFails(company(staff).child('vehicles').get());
    await assertFails(company(staff).child('members/staff/nativePushTokens/installation').set({ token: 'revoked' }));
    console.log('PASS: existing rules, native token isolation, job recovery, replay, chronological chat, company/role isolation, financial paths, photos, documents and revocation');
  } finally { await env.cleanup(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
