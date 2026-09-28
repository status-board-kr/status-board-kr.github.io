const $ = id => document.getElementById(id);
// 프로그램(백그라운드)에 요청. 답이 없거나 오류면 그 이유를 돌려줌
const send = (msg, ms) => new Promise(r => {
  const t = setTimeout(() => r({ ok: false, error: '프로그램이 ' + ((ms || 25000) / 1000) + '초 동안 답하지 않아요. chrome://extensions 에서 이 프로그램의 ↻ 버튼을 누르고 다시 해 주세요.' }), ms || 25000);
  try {
    chrome.runtime.sendMessage(msg, res => {
      clearTimeout(t);
      const err = chrome.runtime.lastError;
      r(res || { ok: false, error: '프로그램과 연결이 안 돼요' + (err ? ' (' + err.message + ')' : '') });
    });
  } catch (e) { clearTimeout(t); r({ ok: false, error: '프로그램과 연결이 안 돼요 (' + e.message + ')' }); }
});

let busy = false;
async function render() {
  const { fb, mode = 'fill', logs = [], loginStep } = await chrome.storage.local.get(['fb', 'mode', 'logs', 'loginStep']);
  if (busy && loginStep) $('loginMsg').textContent = loginStep;
  $('loginBox').classList.toggle('hidden', !!fb);
  $('linked').classList.toggle('hidden', !fb);
  if (fb) $('linkedText').textContent = '✓ ' + fb.email + ' 계정과 연결됨';
  document.querySelectorAll('input[name=mode]').forEach(r => { r.checked = r.value === mode; });
  $('logs').innerHTML = '';
  if (!logs.length) $('logs').textContent = '아직 없어요.';
  for (const l of logs) {
    const d = document.createElement('div');
    d.className = 'log ' + (l.ok ? '' : 'bad');
    d.textContent = new Date(l.at).toLocaleString('ko-KR', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) + '  ' + l.text;
    $('logs').appendChild(d);
  }
}

$('loginBtn').onclick = async () => {
  if (!$('email').value.trim() || !$('pw').value) { $('loginMsg').textContent = '이메일과 비밀번호를 넣어 주세요.'; return; }
  $('loginMsg').textContent = '연결 중...';
  $('loginBtn').disabled = true; busy = true;
  const r = await send({ type: 'login', email: $('email').value.trim(), password: $('pw').value }, 40000);
  $('loginBtn').disabled = false; busy = false;
  $('loginMsg').textContent = r && r.ok ? '' : '⚠ ' + ((r && r.error) || '연결 실패');
  if (r && r.ok) send({ type: 'pollNow' });
  render();
};
$('logoutBtn').onclick = async () => { await send({ type: 'logout' }); render(); };
$('testBtn').onclick = async () => { await send({ type: 'test' }); $('testBtn').textContent = '시험 중... 우기 화면을 보세요'; setTimeout(() => { $('testBtn').textContent = '가짜 손님으로 시험'; }, 8000); };
document.querySelectorAll('input[name=mode]').forEach(r => r.onchange = async () => {
  if (r.value === 'save' && !confirm('저장까지 자동으로 할까요?\n채우기만 모드로 충분히 확인한 뒤에 켜 주세요.')) { render(); return; }
  await chrome.storage.local.set({ mode: r.value });
});
$('ver').textContent = '버전 ' + chrome.runtime.getManifest().version;
chrome.storage.onChanged.addListener(render);
render();
