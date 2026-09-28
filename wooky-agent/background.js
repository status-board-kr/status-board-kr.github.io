/*
 * 팡팡 우기 자동입력 - 사무실 PC 크롬 확장 프로그램 (백그라운드)
 * ─────────────────────────────────────────────
 * 30초마다 현황판(Firebase)의 wooky/jobs 를 보고 (새 현황판은 companies/{업체}/wooky/jobs, P 현황판은 맨 위 wooky/jobs),
 * status 가 'waiting' 인 계약서 작업이 있으면 우기 탭에서 임대차계약서를 열어 채웁니다.
 * 우기 화면 조작은 agent.js 가 합니다 (우기 페이지 안에서 실행).
 * 저장까지 할지는 설정의 mode ('fill' = 채우기만, 'save' = 저장까지) 로 정합니다.
 */
const FB_KEY = 'AIzaSyB0snoSwJ0wTOxI8vRf2GU27VF05zN0c7k'; // index.html firebaseConfig.apiKey 와 같은 값
const DB = 'https://fleet-board-f2345-default-rtdb.asia-southeast1.firebasedatabase.app';
const WOOKY = 'http://pprentcar.wooky.co.kr/';
// P 현황판(pang-rent.github.io/P, 구글 로그인): 이 크롬에 열어 둔 P 탭이 로그인한 그대로
// 그 페이지 안의 Firebase(window._fb)로 작업을 읽고 씁니다. 로그인 정보는 꺼내지 않아요.
const P_SITE = 'https://pang-rent.github.io/';
const VERSION = chrome.runtime.getManifest().version;

const sleep = ms => new Promise(r => setTimeout(r, ms));
class AgentError extends Error {}

// ── 기록 (팝업에 보여줄 최근 20줄) ─────────────────────────
async function log(text, ok) {
  const { logs = [] } = await chrome.storage.local.get('logs');
  logs.unshift({ at: new Date().toISOString(), text, ok: ok !== false });
  await chrome.storage.local.set({ logs: logs.slice(0, 20) });
}

// 인터넷 요청: 15초 넘게 답이 없거나 연결이 안 되면 어디서 막혔는지 알려줌
async function fetchT(url, opt, what) {
  const ac = new AbortController();
  const t = setTimeout(() => ac.abort(), 15000);
  try {
    return await fetch(url, Object.assign({ signal: ac.signal }, opt));
  } catch (e) {
    throw new AgentError(ac.signal.aborted
      ? `${what}에서 15초 동안 답이 없어요. 인터넷이나 백신·보안 프로그램을 확인해 주세요.`
      : `${what}에 연결하지 못했어요 (${(e && e.message) || e}).`);
  } finally { clearTimeout(t); }
}
async function readJson(r) { try { return await r.json(); } catch (e) { return null; } }

// ── 현황판 로그인 (비밀번호는 저장하지 않고 refreshToken 만 보관) ──
async function signIn(email, password) {
  await chrome.storage.local.set({ loginStep: '로그인 서버에 확인 중...' });
  const r = await fetchT(`https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${FB_KEY}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password, returnSecureToken: true }),
  }, '현황판 로그인 서버');
  const j = await readJson(r) || {};
  if (!r.ok) {
    const code = (j.error && j.error.message) || ('HTTP ' + r.status);
    throw new AgentError(/INVALID_LOGIN_CREDENTIALS|INVALID_PASSWORD|EMAIL_NOT_FOUND|INVALID_EMAIL|MISSING_PASSWORD/.test(code)
      ? '이메일이나 비밀번호가 틀려요.'
      : /TOO_MANY_ATTEMPTS/.test(code) ? '여러 번 틀려서 잠시 막혔어요. 몇 분 뒤 다시 해 주세요.'
      : '현황판 로그인 실패 (' + code + ')');
  }
  const token = j.idToken;
  await chrome.storage.local.set({ loginStep: '업체 정보 확인 중...' });
  const ir = await fetchT(`${DB}/userIndex/${j.localId}.json?auth=${token}`, {}, '현황판 데이터 서버');
  const idx = await readJson(ir);
  if (!ir.ok) throw new AgentError('업체 정보를 읽지 못했어요 (HTTP ' + ir.status + ').');
  if (!idx || !idx.companyId) throw new AgentError('이 계정의 업체를 찾지 못했어요. 현황판에서 업체를 먼저 만들어 주세요.');
  await chrome.storage.local.set({
    fb: { email, uid: j.localId, companyId: idx.companyId, refreshToken: j.refreshToken, token, exp: Date.now() + (Number(j.expiresIn) - 120) * 1000 },
  });
  return idx.companyId;
}

async function fbToken() {
  const { fb } = await chrome.storage.local.get('fb');
  if (!fb) return null;
  if (fb.site === 'p') return fb; // P 현황판은 열린 탭의 로그인을 씀
  if (fb.token && Date.now() < fb.exp) return fb;
  const r = await fetchT(`https://securetoken.googleapis.com/v1/token?key=${FB_KEY}`, {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: 'grant_type=refresh_token&refresh_token=' + encodeURIComponent(fb.refreshToken),
  }, '현황판 로그인 서버');
  const j = await readJson(r);
  if (!r.ok || !j) throw new AgentError('현황판 연결이 끊겼어요. 확장 프로그램에서 다시 연결해 주세요.');
  Object.assign(fb, { token: j.id_token, refreshToken: j.refresh_token, exp: Date.now() + (Number(j.expires_in) - 120) * 1000 });
  await chrome.storage.local.set({ fb });
  return fb;
}

// P 탭 찾기 (없으면 열고, 잠든 탭이면 깨움) → 로그인 끝날 때까지 기다림
async function pTab() {
  let tab = (await chrome.tabs.query({ url: P_SITE + '*' }))[0];
  if (!tab) tab = await chrome.tabs.create({ url: P_SITE + 'P/', active: false, pinned: true });
  else if (tab.discarded) await chrome.tabs.reload(tab.id);
  for (let i = 0; i < 40; i++) {
    const [r] = await chrome.scripting.executeScript({ target: { tabId: tab.id }, world: 'MAIN',
      func: () => ({ ready: !!(window._fb && window._fbReady), email: window._fbUser ? window._fbUser.email : '' }) }).catch(() => [null]);
    if (r && r.result && r.result.ready) return { id: tab.id, email: r.result.email };
    await sleep(500);
  }
  throw new AgentError('P 현황판 탭에 로그인이 안 돼 있어요. 크롬에서 pang-rent.github.io/P 탭을 열어 구글로 로그인해 두세요.');
}
async function fbP(method, sub, body) {
  const t = await pTab();
  const [r] = await chrome.scripting.executeScript({
    // body 는 글자로 넘김 (그냥 넘기면 null 값(=지우기)이 빠져요)
    target: { tabId: t.id }, world: 'MAIN', args: [method, sub, JSON.stringify(body === undefined ? null : body)],
    func: async (m, path, json) => {
      const F = window._fb, r = F.ref(F.db, path), b = JSON.parse(json);
      try {
        if (m === 'GET') return { ok: true, val: (await F.get(r)).val() };
        await F.update(r, b);
        return { ok: true, val: b };
      } catch (e) { return { ok: false, err: String((e && e.message) || e) }; }
    },
  });
  const x = r && r.result;
  if (!x || !x.ok) throw new AgentError(/permission/i.test((x && x.err) || '')
    ? `P 현황판이 막았어요 (${method}). 보안 규칙에서 wooky 경로 허용이 필요하거나, 이 구글 계정이 허용 목록에 없어요.`
    : `P 현황판 ${method} 실패 (${(x && x.err) || '응답 없음'}).`);
  return x.val;
}

async function connectP() {
  await chrome.storage.local.set({ loginStep: 'P 현황판 탭 확인 중...' });
  const t = await pTab();
  await chrome.storage.local.set({ fb: { site: 'p', email: t.email } });
  await chrome.storage.local.set({ loginStep: 'wooky 칸 권한 확인 중...' });
  try { await fbP('GET', 'wooky/agent'); } catch (e) { await chrome.storage.local.remove('fb'); throw e; }
  return t.email;
}

async function fb(method, sub, body) {
  const { fb: saved } = await chrome.storage.local.get('fb');
  if (saved && saved.site === 'p') return fbP(method, sub, body);
  const f = await fbToken();
  if (!f) return null;
  const r = await fetchT(`${DB}/companies/${f.companyId}/${sub}.json?auth=${f.token}`, {
    method, headers: { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body),
  }, '현황판 데이터 서버');
  if (!r.ok) throw new AgentError(r.status === 401
    ? `현황판이 막았어요 (${method} 401). 보안 규칙에서 wooky 경로 허용이 필요해요.`
    : `현황판 ${method} 실패 (${r.status}).`);
  return r.json();
}

// ── 우기 탭 ───────────────────────────────────────────
// 계약서가 새 창(팝업)으로 열려도 찾을 수 있게, 우기 주소의 모든 탭·창을 대상으로 합니다.
async function wookyTabs() {
  return chrome.tabs.query({ url: 'http://pprentcar.wooky.co.kr/*' });
}
async function ensureWooky() {
  let tabs = await wookyTabs();
  if (!tabs.length) tabs = [await chrome.tabs.create({ url: WOOKY + 'main.asp', active: false })];
  for (let i = 0; i < 40; i++) {
    const all = await wookyTabs();
    if (all.length && all.every(t => t.status === 'complete')) return;
    await sleep(500);
  }
}

const withTimeout = (p, ms) => Promise.race([p, sleep(ms).then(() => [])]);

// agent.js 를 우기 탭들의 모든 프레임에 넣고 step 실행 → 할 일이 있었던 프레임들의 결과
async function call(step, arg, onlyTab) {
  const out = [];
  for (const tab of await wookyTabs()) {
    if (onlyTab && tab.id !== onlyTab) continue;
    const target = { tabId: tab.id, allFrames: true };
    try {
      await withTimeout(chrome.scripting.executeScript({ target, world: 'MAIN', files: ['agent.js'] }), 8000);
      const res = await withTimeout(chrome.scripting.executeScript({
        target, world: 'MAIN', args: [step, arg === undefined ? null : arg],
        func: (s, a) => (window.__pangAgent ? window.__pangAgent.run(s, a) : { did: false }),
      }), 8000);
      for (const r of res) if (r && r.result && r.result.did) out.push(Object.assign({ tabId: tab.id }, r.result));
    } catch (e) { /* 닫히는 중인 창 등은 건너뜀 */ }
  }
  return out;
}
async function one(step, arg) { return (await call(step, arg))[0] || null; }
async function waitFor(test, ms, err) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    const p = await call('probe');
    if (p.some(test)) return p;
    await sleep(500);
  }
  throw new AgentError(err);
}

/**
 * 계약서 한 건 작성. d = {name, birth, hp, zip, addr, addr2, lic, lictype, licexp, start, end, place, memo, car, rentType}
 * opt.save 가 true 면 마지막에 [저장]까지 누름.
 */
async function writeContract(d, opt) {
  await ensureWooky();
  const p = await call('probe');
  if (!p.length) throw new AgentError('우기 화면을 읽지 못했어요. 우기 탭을 새로고침해 주세요.');
  if (p.some(r => r.login)) throw new AgentError('우기 로그인이 풀려 있어요. 사무실 PC 크롬에서 우기에 로그인해 주세요.');
  if (p.some(r => r.form) && !opt.useOpenForm) throw new AgentError('BUSY');

  if (!p.some(r => r.form)) {
    const m = p.find(r => r.menu);
    const o = m ? await call('openForm', null, m.tabId) : [];
    if (!o.length) throw new AgentError('[대여관리 → 임대차 계약서] 메뉴를 못 찾았어요. 우기 첫 화면을 열어 두세요.');
    await waitFor(r => r.form, 15000, '임대차계약서 창이 열리지 않았어요.');
  }

  const f = await one('fill', d);
  if (!f) throw new AgentError('계약서 칸을 못 찾았어요.');
  if (f.error) throw new AgentError('칸 채우기 오류: ' + f.error);
  const result = { ok: f.ok, bad: f.bad.slice() };

  if (d.car) {
    const o = await one('openCarSearch');
    if (o && o.error) throw new AgentError(o.error);
    await waitFor(r => r.carTable, 10000, '[차량조회] 창이 열리지 않았어요.');
    await one('carSearch', d.car);
    let pick = null;
    for (let i = 0; i < 16; i++) {
      await sleep(600);
      pick = await one('carPick', d.car);
      if (pick && (pick.picked || pick.error)) break;
    }
    if (!pick || !pick.picked) {
      result.bad.push('차량(' + d.car + ')');
      result.carError = pick && pick.found > 1 ? `차량 ${d.car} 이(가) 여러 대 나와서 못 골랐어요.` : `차량 ${d.car} 을(를) 차량조회 목록에서 못 찾았어요.`;
    } else {
      await sleep(1200);
      const rc = await one('readCar');
      result.car = rc && rc.plate ? `${rc.plate} ${rc.model || ''}`.trim() : pick.car;
      result.ok.push('차량');
    }
  }

  if (opt.save) {
    if (result.bad.length) throw new AgentError('못 채운 칸이 있어서 저장하지 않았어요: ' + result.bad.join(', '));
    const s = await one('save');
    if (s && s.error) throw new AgentError(s.error);
    await sleep(3000);
    // 저장이 되면 우기가 계약서 창을 닫거나 알림을 띄움. 창이 그대로면 직원이 확인하도록 남겨둠
    const after = await call('probe');
    result.saved = !after.some(r => r.form);
    if (!result.saved) result.saveUnclear = true;
  }

  const dn = await call('done');
  result.msgs = [].concat(...dn.map(x => x.msgs || []));
  const title = result.saved ? '계약서 저장 완료' : result.saveUnclear ? '저장 버튼을 눌렀는데 계약서 창이 그대로예요. 우기 알림을 확인해 주세요' : '계약서 채우기 완료';
  const text = title + (opt.test ? ' (가짜 손님 시험)' : '') +
    `\n채운 칸: ${result.ok.join(', ') || '없음'}` +
    (result.bad.length ? `\n못 채운 칸: ${result.bad.join(', ')}` : '') +
    (result.carError ? `\n${result.carError}` : '') +
    (result.msgs.length ? `\n우기 알림: ${result.msgs.join(' / ')}` : '') +
    (result.saved ? '' : '\n노란 칸을 확인한 뒤 [저장(F2)]을 눌러 주세요.');
  const ok = !result.bad.length && !result.saveUnclear;
  if (!(await call('banner', { text, ok, onlyFormFrame: true })).length) await call('banner', { text, ok, topOnly: true });
  result.text = text;
  return result;
}

// ── 가짜 손님 시험 (팝업 버튼) ─────────────────────────────
function testData() {
  const d = n => { const t = new Date(); t.setDate(t.getDate() + n); return t.toISOString().slice(0, 10); };
  return {
    name: '홍길동', birth: '1990-01-01', hp: '010-0000-0000', addr: '광주광역시 테스트주소',
    lic: '25-12-345678-90', lictype: '1종보통', licexp: '2030-12-31',
    start: d(1) + ' 10:00', end: d(2) + ' 10:00', place: '사무실', car: '105허9596',
    memo: '자동입력 시험입니다. 저장하지 마세요.',
  };
}

let running = false;
async function runTest() {
  if (running) return;
  running = true;
  try {
    await log('가짜 손님으로 시험을 시작했어요.');
    const r = await writeContract(testData(), { save: false, test: true, useOpenForm: true });
    await log(r.text, !r.bad.length);
  } catch (e) {
    await log('시험 실패: ' + (e instanceof AgentError ? e.message : (e && e.message) || e), false);
  } finally { running = false; }
}

// ── 현황판 작업 확인 (30초마다) ────────────────────────────
async function poll() {
  if (running) return;
  const f = await fbToken().catch(async e => { await log(e.message, false); return null; });
  if (!f) return;
  const { mode = 'fill' } = await chrome.storage.local.get('mode');
  running = true;
  try {
    await fb('PATCH', 'wooky/agent', { lastSeen: new Date().toISOString(), version: VERSION, mode });
    const jobs = (await fb('GET', 'wooky/jobs')) || {};
    const now = Date.now();
    const next = Object.entries(jobs)
      .filter(([, j]) => j && (j.status === 'waiting' || (j.status === 'working' && now - Date.parse(j.startedAt || 0) > 5 * 60000)))
      .sort((a, b) => String(a[1].createdAt).localeCompare(String(b[1].createdAt)))[0];
    if (!next) return;
    const [key, job] = next;
    await fb('PATCH', `wooky/jobs/${key}`, { status: 'working', startedAt: new Date().toISOString() });
    try {
      const r = await writeContract(job.data || {}, { save: mode === 'save' });
      await fb('PATCH', `wooky/jobs/${key}`, {
        status: r.saved ? 'saved' : 'filled', finishedAt: new Date().toISOString(), data: null, // 개인정보는 다 쓰면 지움
        result: { ok: r.ok, bad: r.bad, car: r.car || '', msgs: r.msgs, carError: r.carError || '', saveUnclear: !!r.saveUnclear },
      });
      await log(`${job.data && job.data.name || '손님'}: ${r.text}`, !r.bad.length);
    } catch (e) {
      if (e instanceof AgentError && e.message === 'BUSY') {
        // 직원이 다른 계약서를 쓰는 중 → 창이 닫힐 때까지 기다림
        await fb('PATCH', `wooky/jobs/${key}`, { status: 'waiting', note: '우기 계약서 창이 열려 있어서 기다리는 중' });
        return;
      }
      const msg = e instanceof AgentError ? e.message : String((e && e.message) || e);
      await fb('PATCH', `wooky/jobs/${key}`, { status: 'error', finishedAt: new Date().toISOString(), error: msg, data: null });
      await log(`${job.data && job.data.name || '손님'}: 실패 - ${msg}`, false);
    }
  } catch (e) {
    await log((e && e.message) || String(e), false);
  } finally { running = false; }
}

chrome.runtime.onInstalled.addListener(() => chrome.alarms.create('poll', { periodInMinutes: 0.5 }));
chrome.runtime.onStartup.addListener(() => chrome.alarms.create('poll', { periodInMinutes: 0.5 }));
chrome.alarms.onAlarm.addListener(a => { if (a.name === 'poll') poll(); });

chrome.runtime.onMessage.addListener((msg, sender, reply) => {
  if (msg.type === 'test') { runTest(); reply({ started: true }); return; }
  if (msg.type === 'login') {
    signIn(msg.email, msg.password)
      .then(async c => { await log('현황판과 연결됐어요.'); reply({ ok: true, companyId: c }); })
      .catch(e => reply({ ok: false, error: e instanceof AgentError ? e.message : '연결 오류: ' + ((e && e.message) || e) }))
      .finally(() => chrome.storage.local.remove('loginStep'));
    return true;
  }
  if (msg.type === 'connectP') {
    connectP()
      .then(async email => { await log('P 현황판(' + email + ')과 연결됐어요.'); reply({ ok: true }); })
      .catch(e => reply({ ok: false, error: e instanceof AgentError ? e.message : '연결 오류: ' + ((e && e.message) || e) }))
      .finally(() => chrome.storage.local.remove('loginStep'));
    return true;
  }
  if (msg.type === 'logout') { chrome.storage.local.remove('fb').then(() => reply({ ok: true })); return true; }
  if (msg.type === 'pollNow') { poll(); reply({ started: true }); }
});
