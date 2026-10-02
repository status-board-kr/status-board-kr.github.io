'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const { JSDOM } = require('jsdom');
const path = require('node:path');
const root = path.join(__dirname,'..');
const index = fs.readFileSync(path.join(root,'index.html'),'utf8');
function functionSource(name) {
  const start = index.indexOf('async function '+name+'(');
  const end = index.indexOf('\n}',start)+2;
  return index.slice(start,end);
}
test('failed vehicle write prevents dependent schedule and sales writes', async () => {
  const calls=[];
  const vehicle={plate:'123가4567',type:'장기',amount:null,returnDate:null};
  const fields=new Proxy({}, {get:()=>({value:'',checked:false})});
  const ctx={window:{_editingPlate:vehicle.plate},vehicles:[vehicle],document:{getElementById:id=>fields[id]},LONG_BRANCH:'장기',HOME_BRANCH:'본점',AS_YEARS_DEFAULT:3,
    closeModal(){},render(){},persist:async()=>{calls.push('vehicle');return false;},syncReturnSchedule:async()=>calls.push('schedule'),syncGeneralSale:async()=>calls.push('sale')};
  vm.createContext(ctx);vm.runInContext(functionSource('saveModal'),ctx);await ctx.saveModal();
  assert.deepEqual(calls,['vehicle']);
});
test('persist reports failure, resets suppression and refreshes rather than reporting success', async () => {
  const messages=[];let refreshed=0;
  const ctx={window:{_fb:{ref:()=>({}),db:{},set:async()=>{throw Error('denied');}}},vehicles:[],_suppressNextRemote:false,snapshotHistory:async()=>{},loadData:async()=>refreshed++,render(){},showToast:m=>messages.push(m),console:{error(){}}};
  vm.createContext(ctx);vm.runInContext(functionSource('persist'),ctx);
  assert.equal(await ctx.persist(),false);assert.equal(ctx._suppressNextRemote,false);assert.equal(refreshed,1);
  assert.equal(messages.some(m=>m.startsWith('저장됨')),false);
});
test('document generation strips executable HTML while retaining layout and Korean text', () => {
  const html=fs.readFileSync(path.join(root,'docs.html'),'utf8');
  const dom=new JSDOM(html.replace(/<style[\s\S]*?<\/style>/g,''),{runScripts:'outside-only',url:'https://status-board-kr.github.io/docs.html'}),w=dom.window;
  w.eval(fs.readFileSync(path.join(root,'vendor/purify.min.js'),'utf8'));
  w.confirm=()=>true;w.scrollTo=()=>{};w.alert=m=>{throw Error(m);};
  for(const script of w.document.querySelectorAll('script')) if(!script.src && script.type!=='module') w.eval(script.textContent);
  w._silentGen=true;
  w.document.getElementById('c_name').value='홍길동<img src=x onerror="window.pwned=1">';
  w.document.getElementById('c_model').value='<svg onload="window.pwned=2"></svg>쏘나타';
  for(const [id,value] of Object.entries({c_phone:'01012345678',c_price:'500000',c_start:'2026-10-02',c_end:'2026-11-02'})) w.document.getElementById(id).value=value;
  w.genContract();
  const content=w.document.getElementById('contractContent');
  assert.match(content.textContent,/홍길동/);assert.match(content.textContent,/쏘나타/);
  assert.equal(content.querySelector('svg,script,iframe'),null);
  for(const el of content.querySelectorAll('*')) for(const attr of el.attributes) {
    assert.equal(/^on/i.test(attr.name),false);
    assert.equal(/^javascript:/i.test(attr.value),false);
  }
  assert.ok(content.querySelector('table'));assert.match(content.innerHTML,/style=/);
  assert.equal(w.pwned,undefined);dom.window.close();
});
test('sanitizer failure blocks document insertion', () => {
  const html=fs.readFileSync(path.join(root,'docs.html'),'utf8');
  const start=html.indexOf('function showDoc('),end=html.indexOf('\n}',start)+2;
  const dom=new JSDOM('<div id="contractContent"></div>',{runScripts:'outside-only'});
  dom.window.eval(html.slice(start,end));
  assert.throws(()=>dom.window.showDoc('contractDoc','contractEmpty','<img onerror=alert(1)>'),/보안 모듈/);
  assert.equal(dom.window.document.getElementById('contractContent').innerHTML,'');dom.window.close();
});
