'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { consumeQuota, handleStaff } = require('../ai-service');
const { handle, deps, _hits } = require('../index');
function database(initial = {}) {
  const values = structuredClone(initial);
  return { values, ref(path = '') { return {
    async once() { return { val: () => values[path] || null }; },
    async transaction(fn) { const next = fn(structuredClone(values[path] || null)); if(next === undefined) return { committed: false }; values[path] = next; return { committed: true }; },
    async update(data) { for(const [key, value] of Object.entries(data)) values[key] = value; },
    child(key) { return { once: async () => ({ val: () => values[path + '/' + key] || null }) }; }
  }; } };
  // Child isn't used by these proxy tests.
}
function response() { return { code: 200, headers: {}, status(n) { this.code=n; return this; }, json(data) { this.data=data; return this; }, set(k,v) { this.headers[k]=v; }, send(data) { this.data=data; return this; } }; }
const request = body => ({ headers: { authorization: 'Bearer test-token' }, body });
function options(db, uid = 'u1') { return { db: () => db, deps: { now: () => 60000, verifyIdToken: async () => ({ uid }), fetch: async () => { throw Error('must not call AI'); } }, admin: {}, geminiModels: ['test'], grokModels: ['test'] }; }
test('AI status never returns keys; owner and staff can check presence', async () => {
  for(const role of ['owner', 'staff']) {
    const db = database({ 'companies/company1/members/u1': { role }, 'privateAiSettings/company1': { geminiKey: 'secret', grokKey: 'other-secret' } });
    const res = response(); await handleStaff(request({ companyId: 'company1', operation: 'ai-status' }), res, options(db));
    assert.deepEqual(res.data, { gemini: true, grok: true });
  }
});
test('missing token, invalid token, non-member and staff configuration rejected', async () => {
  const db = database({ 'companies/company1/members/u1': { role: 'staff' } });
  for(const [req, opts, expected] of [
    [{ headers: {}, body: { companyId: 'company1' } }, options(db), 401],
    [request({ companyId: 'company1' }), { ...options(db), deps: { verifyIdToken: async () => { throw Error(); } } }, 401],
    [request({ companyId: 'company1' }), options(db, 'other'), 403],
    [request({ companyId: 'company1', operation: 'ai-config' }), options(db), 403]
  ]) { const res = response(); await handleStaff(req, res, opts); assert.equal(res.code, expected); }
});
test('owner writes private settings and removes old public location atomically', async () => {
  const db = database({ 'companies/company1/members/u1': { role: 'owner' } }); const res=response();
  await handleStaff(request({ companyId:'company1', operation:'ai-config', geminiKey:'a'.repeat(30), grokKey:'' }),res,options(db));
  assert.equal(db.values['privateAiSettings/company1'].geminiKey,'a'.repeat(30));
  assert.equal(db.values['companies/company1/aiSettings'],null);
  assert.deepEqual(res.data,{gemini:true,grok:false});
});
test('shared counters enforce IP and company limits across sessions', async () => {
  const db=database();
  for(let n=0;n<12;n++) assert.equal(await consumeQuota(db,'company1','ip:one',60000,{publicChat:true,sessionId:'s'+n}),true);
  assert.equal(await consumeQuota(db,'company1','ip:one',60000,{publicChat:true,sessionId:'new'}),false);
  for(let n=0;n<48;n++) assert.equal(await consumeQuota(db,'company1','ip:'+n,60000),true);
  assert.equal(await consumeQuota(db,'company1','ip:fresh',60000),false);
  assert.equal(await consumeQuota(db,'company1','ip:fresh',120000),true);
});
test('session count remains authoritative when client truncates messages', async () => {
  const db=database();
  for(let n=0;n<40;n++) assert.equal(await consumeQuota(db,'company1','ip:one',n*60000,{publicChat:true,sessionId:'same'}),true);
  assert.equal(await consumeQuota(db,'company1','ip:one',41*60000,{publicChat:true,sessionId:'same'}),false);
});
test('daily cap survives minute changes and resets on next day', async () => {
  const db=database();
  for(let n=0;n<1000;n++) assert.equal(await consumeQuota(db,'company1','user:one',n*60000),true);
  assert.equal(await consumeQuota(db,'company1','user:one',1001*60000),false);
  assert.equal(await consumeQuota(db,'company1','user:one',86400000),true);
});
test('oversized image request rejected before provider call', async () => {
  const db=database({'companies/company1/members/u1':{role:'staff'},'privateAiSettings/company1':{geminiKey:'secret'}}),res=response();
  await handleStaff(request({companyId:'company1',operation:'ai-vision',prompt:'read',images:['AAAA'.repeat(140000)]}),res,options(db));
  assert.equal(res.code,400);
});
test('public chat rejects absent Origin and checks turn count before truncation', async () => {
  _hits.clear();
  let res=response(); await handle({method:'POST',headers:{},body:{}},res);assert.equal(res.code,403);
  res=response();await handle({method:'POST',headers:{origin:'https://status-board-kr.github.io'},body:{companyId:'company1',sessionId:'session123',messages:Array.from({length:41},()=>({role:'user',text:'hello'}))}},res);
  assert.equal(res.data.done,true);
});
test('replacing one key preserves the other key', async () => {
  const db=database({'companies/company1/members/u1':{role:'owner'},'privateAiSettings/company1':{geminiKey:'a'.repeat(30),grokKey:'b'.repeat(30)}}),res=response();
  await handleStaff(request({companyId:'company1',operation:'ai-config',geminiKey:'c'.repeat(30)}),res,options(db));
  assert.equal(db.values['privateAiSettings/company1'].grokKey,'b'.repeat(30));
});
test('vision falls back between models on server and never exposes provider keys', async () => {
  const db=database({'companies/company1/members/u1':{role:'staff'},'privateAiSettings/company1':{geminiKey:'secret-key'}}),res=response(),opts=options(db);let calls=0;
  opts.geminiModels=['bad','good'];opts.deps.fetch=async()=>{calls++;return calls===1?{status:404}:{status:200,json:async()=>({candidates:[{content:{parts:[{text:'{"plates":[]}' }]}}]})};};
  await handleStaff(request({companyId:'company1',operation:'ai-vision',prompt:'read photos',images:['AAAA']}),res,opts);
  assert.equal(calls,2);assert.deepEqual(res.data,{text:'{"plates":[]}',reason:''});assert.equal(JSON.stringify(res.data).includes('secret-key'),false);
});
