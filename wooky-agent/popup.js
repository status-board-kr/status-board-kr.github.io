const $ = id => document.getElementById(id);
const send = msg => new Promise(r => chrome.runtime.sendMessage(msg, r));

async function render() {
  const { fb, mode = 'fill', logs = [] } = await chrome.storage.local.get(['fb', 'mode', 'logs']);
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
  $('loginMsg').textContent = '연결 중...';
  const r = await send({ type: 'login', email: $('email').value.trim(), password: $('pw').value });
  $('pw').value = '';
  $('loginMsg').textContent = r && r.ok ? '' : (r && r.error) || '연결 실패';
  if (r && r.ok) send({ type: 'pollNow' });
  render();
};
$('logoutBtn').onclick = async () => { await send({ type: 'logout' }); render(); };
$('testBtn').onclick = async () => { await send({ type: 'test' }); $('testBtn').textContent = '시험 중... 우기 화면을 보세요'; setTimeout(() => { $('testBtn').textContent = '가짜 손님으로 시험'; }, 8000); };
document.querySelectorAll('input[name=mode]').forEach(r => r.onchange = async () => {
  if (r.value === 'save' && !confirm('저장까지 자동으로 할까요?\n채우기만 모드로 충분히 확인한 뒤에 켜 주세요.')) { render(); return; }
  await chrome.storage.local.set({ mode: r.value });
});
chrome.storage.onChanged.addListener(render);
render();
