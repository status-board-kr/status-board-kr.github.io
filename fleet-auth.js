/**
 * 차량현황판 - 업체 가입/로그인 공통 모듈
 * ─────────────────────────────────────────────
 * index.html, payment.html 두 곳에서 같이 씁니다.
 *
 * 하는 일:
 *  1. 이메일/비밀번호 로그인·회원가입
 *  2. 업체(회사) 생성 → companies/{companyId} 만들고 본인을 owner로 등록
 *  3. 초대코드로 기존 업체에 직원(staff)으로 참여
 *  4. 로그인한 사용자가 어느 업체 소속인지 찾아서 window.COMPANY_ID 에 넣어줌
 *
 * 이 모듈이 끝나면 아래 값들이 준비됩니다:
 *   window.COMPANY_ID   - 소속 업체 ID (모든 데이터 경로의 기준)
 *   window.COMPANY_ROLE - 'owner' 또는 'staff'
 *   window.CURRENT_USER - Firebase user 객체
 */

window.FleetAuth = (function () {
  let _fb = null;      // { ref, get, set, update, db }
  let _auth = null;    // { auth, createUser..., signIn..., signOut }
  let _onReady = null; // 로그인+업체확인까지 끝났을 때 호출할 콜백

  // ── 유틸 ──────────────────────────────────────
  function el(id) { return document.getElementById(id); }

  function makeCompanyId() {
    return 'c_' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
  }

  function makeInviteCode() {
    // 헷갈리는 글자(0/O, 1/I) 빼고 6자리
    const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    let s = '';
    for (let i = 0; i < 6; i++) s += chars[Math.floor(Math.random() * chars.length)];
    return s;
  }

  let _currentPanel = 'authLogin';
  let _pendingError = ''; // 화면이 자동 전환돼도 사라지지 않게 남겨둘 에러 메시지

  // 패널마다 에러 표시 영역이 따로 있어서, 지금 보이는 패널의 것에 써줍니다.
  const ERR_IDS = {
    authLogin: 'authErr',
    authSignup: 'authErr2',
    authJoin: 'authErr3',
    authChoose: 'authErr4'
  };

  function showError(msg) {
    Object.keys(ERR_IDS).forEach(function (p) {
      const node = el(ERR_IDS[p]);
      if (node) node.textContent = (p === _currentPanel) ? msg : '';
    });
  }

  function friendlyAuthError(err) {
    const code = (err && err.code) || '';
    if (code === 'auth/email-already-in-use') return '이미 가입된 이메일이에요. 로그인해주세요.';
    if (code === 'auth/invalid-email') return '이메일 형식이 올바르지 않아요.';
    if (code === 'auth/weak-password') return '비밀번호는 6자 이상으로 해주세요.';
    if (code === 'auth/wrong-password' || code === 'auth/invalid-credential') return '이메일 또는 비밀번호가 맞지 않아요.';
    if (code === 'auth/user-not-found') return '가입되지 않은 이메일이에요.';
    if (code === 'auth/too-many-requests') return '시도가 너무 많았어요. 잠시 후 다시 해주세요.';
    if (code === 'auth/network-request-failed') return '네트워크 연결을 확인해주세요.';
    return '오류가 발생했어요: ' + (err.message || code || '알 수 없음');
  }

  // ── 화면 전환 ─────────────────────────────────
  function showPanel(name) {
    _currentPanel = name;
    ['authLogin', 'authSignup', 'authJoin', 'authChoose'].forEach(function (p) {
      const node = el(p);
      if (node) node.style.display = (p === name) ? 'flex' : 'none';
    });
    applyLoggedInMode(name);
    // 화면이 자동으로 넘어간 경우엔, 왜 그렇게 됐는지 알 수 있게 에러를 이어서 보여줌
    showError(_pendingError);
    _pendingError = '';
  }

  function currentAccount() {
    return window.CURRENT_USER || (_auth && _auth.auth && _auth.auth.currentUser) || null;
  }
  function isSignedInNoCompany() {
    return !!currentAccount() && !window.COMPANY_ID;
  }
  function applyLoggedInMode(name) {
    const on = isSignedInNoCompany();
    const sets = { authJoin: ['authJoinEmail', 'authJoinPw'], authSignup: ['authSignupEmail', 'authSignupPw'] };
    Object.keys(sets).forEach(function (panel) {
      sets[panel].forEach(function (id) {
        const node = el(id);
        if (node) node.style.display = on ? 'none' : '';
      });
    });
    const note = el('authAccountNote');
    if (note) {
      if (on && (name === 'authJoin' || name === 'authSignup')) {
        const acc = currentAccount();
        note.textContent = '현재 계정(' + ((acc && acc.email) || '') + ')으로 진행합니다. 코드만 입력하면 돼요.';
        note.style.display = 'block';
      } else { note.style.display = 'none'; }
    }
  }

  // ── 로그인/가입 동작 ──────────────────────────
  async function doLogin() {
    const email = el('authLoginEmail').value.trim();
    const pw = el('authLoginPw').value;
    if (!email || !pw) { showError('이메일과 비밀번호를 입력해주세요.'); return; }
    setBusy(true);
    try {
      await _auth.signInWithEmailAndPassword(_auth.auth, email, pw);
      // 이후 처리는 onAuthStateChanged가 이어서 함
    } catch (err) {
      console.error(err);
      showError(friendlyAuthError(err));
      setBusy(false);
    }
  }

  async function doSignupCompany() {
    const companyName = el('authCompanyName').value.trim();
    const already = isSignedInNoCompany() ? currentAccount() : null;
    const email = already ? (already.email || '') : el('authSignupEmail').value.trim();
    const pw = already ? '' : el('authSignupPw').value;
    if (!companyName) { showError('업체명을 입력해주세요.'); return; }
    if (!already) {
      if (!email || !pw) { showError('이메일과 비밀번호를 입력해주세요.'); return; }
      if (pw.length < 6) { showError('비밀번호는 6자 이상으로 해주세요.'); return; }
    }
    setBusy(true);
    try {
      const cred = already ? { user: already } : await _auth.createUserWithEmailAndPassword(_auth.auth, email, pw);
      const uid = cred.user.uid;
      const companyId = makeCompanyId();
      const { ref, set, db } = _fb;

      // 업체 생성: 본인을 owner로 함께 기록해야 보안 규칙을 통과함
      await set(ref(db, 'companies/' + companyId), {
        profile: {
          name: companyName,
          createdAt: new Date().toISOString(),
          ownerEmail: email,
          subscription: { status: 'trial', startedAt: new Date().toISOString() }
        },
        members: {
          [uid]: { role: 'owner', email: email, joinedAt: new Date().toISOString() }
        }
      });
      await set(ref(db, 'userIndex/' + uid), { companyId: companyId });
      if (already) await afterAuth(already);
    } catch (err) {
      console.error(err);
      showError(friendlyAuthError(err));
      setBusy(false);
    }
  }

  async function doJoinWithCode() {
    const code = el('authJoinCode').value.trim().toUpperCase();
    const already = isSignedInNoCompany() ? currentAccount() : null;
    const email = already ? (already.email || '') : el('authJoinEmail').value.trim();
    const pw = already ? '' : el('authJoinPw').value;
    if (!code) { showError('초대코드를 입력해주세요.'); return; }
    if (!already) {
      if (!email || !pw) { showError('이메일과 비밀번호를 입력해주세요.'); return; }
      if (pw.length < 6) { showError('비밀번호는 6자 이상으로 해주세요.'); return; }
    }
    setBusy(true);
    try {
      const cred = already ? { user: already } : await _auth.createUserWithEmailAndPassword(_auth.auth, email, pw);
      const uid = cred.user.uid;
      const { ref, get, set, db } = _fb;

      const snap = await get(ref(db, 'inviteIndex/' + code));
      if (!snap.exists()) {
        // 계정은 이미 만들어진 상태라 곧 '소속 업체 없음' 화면으로 자동 전환되는데,
        // 그때도 이유가 보이도록 _pendingError에 남겨둡니다.
        _pendingError = '초대코드 "' + code + '"를 찾을 수 없어요. 업체 관리자에게 다시 확인해주세요.';
        showError(_pendingError);
        setBusy(false);
        return;
      }
      const companyId = snap.val().companyId;

      await set(ref(db, 'companies/' + companyId + '/members/' + uid), {
        role: 'staff', email: email, joinedAt: new Date().toISOString(), viaCode: code
      });
      await set(ref(db, 'userIndex/' + uid), { companyId: companyId });
      if (already) await afterAuth(already);
    } catch (err) {
      console.error(err);
      showError(friendlyAuthError(err));
      setBusy(false);
    }
  }

  async function doLogout() {
    try { await _auth.signOut(_auth.auth); } catch (e) { console.error(e); }
    location.reload();
  }

  function setBusy(busy) {
    ['authLoginBtn', 'authSignupBtn', 'authJoinBtn'].forEach(function (id) {
      const b = el(id);
      if (b) b.disabled = busy;
    });
  }

  // ── 로그인 후: 소속 업체 찾기 ──────────────────
  async function resolveCompany(user) {
    const { ref, get, db } = _fb;
    const snap = await get(ref(db, 'userIndex/' + user.uid));
    if (!snap.exists()) return null;
    const companyId = snap.val().companyId;
    try {
      const mSnap = await get(ref(db, 'companies/' + companyId + '/members/' + user.uid));
      if (!mSnap.exists()) return null;
      return { companyId: companyId, role: mSnap.val().role || 'staff' };
    } catch (e) {
      // 업체에서 내보내진 경우, 보안규칙 때문에 읽기 자체가 거부됩니다.
      // 이건 오류가 아니라 "이제 소속이 없다"는 뜻이므로 그렇게 처리합니다.
      const msg = ((e && e.code) || '') + ' ' + ((e && e.message) || '');
      if (/permission[_-]?denied/i.test(msg)) {
        _pendingError = '이 업체에서 접근 권한이 해제되었어요. 관리자에게 문의해주세요.';
        return null;
      }
      throw e;
    }
  }

  // ── 초기화 ────────────────────────────────────
  let _entered = false;
  async function afterAuth(user) {
    if (!user) {
      _entered = false;
      window.CURRENT_USER = null;
      window.COMPANY_ID = null;
      el('authScreen').style.display = 'flex';
      showPanel('authLogin');
      setBusy(false);
      return;
    }
    window.CURRENT_USER = user;
    try {
      const info = await resolveCompany(user);
      if (!info) {
        el('authScreen').style.display = 'flex';
        showPanel('authChoose');
        setBusy(false);
        return;
      }
      window.COMPANY_ID = info.companyId;
      window.COMPANY_ROLE = info.role;
      el('authScreen').style.display = 'none';
      if (_entered) { location.reload(); return; }
      _entered = true;
      if (_onReady) _onReady(info);
    } catch (err) {
      console.error(err);
      el('authScreen').style.display = 'flex';
      showPanel('authLogin');
      showError('업체 정보를 불러오지 못했어요. 다시 로그인해주세요.');
      setBusy(false);
    }
  }

  function init(opts) {
    _fb = opts.fb;
    _auth = opts.auth;
    _onReady = opts.onReady;

    _auth.onAuthStateChanged(_auth.auth, function (user) { afterAuth(user); });
  }

  return {
    init: init,
    showPanel: showPanel,
    doLogin: doLogin,
    doSignupCompany: doSignupCompany,
    doJoinWithCode: doJoinWithCode,
    doLogout: doLogout,
    makeInviteCode: makeInviteCode
  };
})();
