/*
 * 우기 화면이 새로 뜰 때마다(숨은 프레임 포함) 페이지 코드보다 먼저 들어가는 작은 코드.
 * 확장 프로그램이 [조회]/[저장]을 누른 직후 잠깐 동안(localStorage '__pangArm' 시각까지)만
 * 우기 알림창을 대신 [확인]하고 글자를 '__pangMsgs' 에 적어 둡니다. 그 외에는 원래대로 알림창이 뜹니다.
 */
(function () {
  if (window.__pangHook) return;
  window.__pangHook = 1;
  var A = window.alert, C = window.confirm;
  function armed() { try { return +localStorage.getItem('__pangArm') > Date.now(); } catch (e) { return false; } }
  function rec(m) {
    try {
      var a = JSON.parse(localStorage.getItem('__pangMsgs') || '[]');
      a.push(String(m));
      localStorage.setItem('__pangMsgs', JSON.stringify(a.slice(-30)));
    } catch (e) {}
  }
  window.alert = function (m) { if (armed()) { rec(m); return; } return A.call(window, m); };
  window.confirm = function (m) {
    if (armed() && localStorage.getItem('__pangArmConfirm') === '1') { rec('[확인창] ' + m); return true; }
    return C.call(window, m);
  };
})();
