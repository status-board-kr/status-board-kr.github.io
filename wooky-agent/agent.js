/*
 * 우기(pprentcar.wooky.co.kr) 화면을 대신 조작하는 코드.
 * 확장 프로그램(background.js)이 우기 탭의 모든 프레임에 이 코드를 넣고 단계별로 window.__pangAgent.run(step, arg) 을 부릅니다.
 * 각 단계는 자기 프레임에서 할 일이 있을 때만 움직이고, 결과를 { did, ... } 로 돌려줍니다.
 * [저장(F2)]은 'save' 단계에서만 누르고, 그 단계는 저장까지 자동 모드일 때만 불립니다.
 */
(function () {
  var VERSION = 1;
  if (window.__pangAgent && window.__pangAgent.v >= VERSION) return;

  var HL = 'rgb(255,236,110)';
  function norm(s) { return String(s || '').replace(/[\s?:]/g, ''); }
  function isField(e) {
    var t = e.tagName;
    if (t == 'SELECT' || t == 'TEXTAREA') return true;
    if (t != 'INPUT') return false;
    t = (e.getAttribute('type') || 'text').toLowerCase();
    return /^(text|tel|date|number|search)$/.test(t);
  }
  function fields(e, tag) {
    var a = e.getElementsByTagName('*'), r = [];
    for (var i = 0; i < a.length; i++) if (isField(a[i]) && (!tag || a[i].tagName == tag)) r.push(a[i]);
    return r;
  }
  function vis(e) { return !!(e && (e.offsetWidth || e.offsetHeight || e.getClientRects().length)); }
  function after(c, tag) {
    var r = [], s = c.nextElementSibling;
    while (s) {
      if (!fields(s).length && norm(s.textContent).length >= 2) break;
      r = r.concat(fields(s, tag || 'INPUT'));
      s = s.nextElementSibling;
    }
    return r;
  }
  function labels(el, t) {
    var a = el.getElementsByTagName('*'), r = [];
    for (var i = 0; i < a.length; i++) {
      var c = a[i];
      if ((c.tagName == 'TD' || c.tagName == 'TH') && norm(c.textContent) == t && !fields(c).length && vis(c) && fields(c.nextElementSibling || c).length) r.push(c);
    }
    return r;
  }
  function depth(x) { var n = 0; while (x) { n++; x = x.parentNode; } return n; }
  function lca(a, b) {
    var up = [], x, y;
    for (x = a; x; x = x.parentNode) up.push(x);
    for (y = b; y; y = y.parentNode) if (up.indexOf(y) >= 0) return y;
    return null;
  }
  // 이 프레임에 열려 있는 임대차계약서 입력 칸 묶음
  function formRoot() {
    var body = document.body; if (!body) return null;
    var A = labels(body, '면허번호'), B = labels(body, '출발일시');
    if (!A.length || !B.length) return null;
    var best = null;
    for (var j = 0; j < B.length; j++) { var l = lca(A[0], B[j]); if (!best || depth(l) > depth(best)) best = l; }
    return best;
  }
  function fire(e, t) { try { var ev = document.createEvent('HTMLEvents'); ev.initEvent(t, true, false); e.dispatchEvent(ev); } catch (x) {} }
  function put(e, v) { e.value = v; e.style.backgroundColor = HL; fire(e, 'input'); fire(e, 'change'); }
  function clickEl(e) {
    try { e.scrollIntoView({ block: 'center' }); } catch (x) {}
    ['mousedown', 'mouseup'].forEach(function (t) { try { var ev = document.createEvent('MouseEvents'); ev.initEvent(t, true, true); e.dispatchEvent(ev); } catch (x) {} });
    e.click();
  }
  // 글자가 정확히 t 인 누를 수 있는 것 (a, button, input[type=button], span/li/div/td with onclick)
  function clickable(scope, t, onlyVisible) {
    var a = scope.getElementsByTagName('*'), hit = null;
    for (var i = 0; i < a.length; i++) {
      var e = a[i], tag = e.tagName, txt;
      if (tag == 'INPUT') { if (!/button|submit|image/i.test(e.type)) continue; txt = e.value || e.alt || ''; }
      else if (tag == 'IMG') txt = e.alt || e.title || '';
      else txt = e.textContent;
      if (norm(txt) != norm(t)) continue;
      if (onlyVisible && !vis(e)) continue;
      var ok = tag == 'A' || tag == 'BUTTON' || tag == 'INPUT' || e.onclick || e.getAttribute('onclick');
      if (ok) return e;
      if (!hit) hit = e; // 글자만 맞는 제일 바깥 요소 (li, span 등)
    }
    return hit;
  }

  // 우기가 띄우는 알림창(alert/confirm)을 잠깐 가로채서 멈추지 않게 함
  var msgs = [];
  function hookDialogs(allowConfirm) {
    if (!window.__pangOrig) window.__pangOrig = { alert: window.alert, confirm: window.confirm };
    window.alert = function (m) { msgs.push(String(m)); };
    window.confirm = function (m) { msgs.push('[확인창] ' + m); return !!allowConfirm; };
  }
  function unhookDialogs() {
    if (window.__pangOrig) { window.alert = window.__pangOrig.alert; window.confirm = window.__pangOrig.confirm; }
  }

  function dt(x) { var p = String(x || '').split(/[ T]/), hm = (p[1] || '').split(':'); return [p[0], hm[0], hm[1]]; }

  // 차량조회 창: 머리글에 '차량번호'와 '선택'이 있는 표
  function carTable() {
    var ts = document.getElementsByTagName('table');
    for (var i = 0; i < ts.length; i++) {
      var t = ts[i]; if (!vis(t)) continue;
      var heads = t.querySelectorAll('th, thead td'), h = [];
      for (var k = 0; k < heads.length; k++) h.push(norm(heads[k].textContent));
      if (h.indexOf('차량번호') >= 0 && h.indexOf('선택') >= 0) return t;
    }
    return null;
  }
  // 차량조회 창의 검색칸: '차량번호' 글자 바로 뒤의 입력칸 (계약서 칸 제외)
  function carSearchInput(root) {
    var ins = document.getElementsByTagName('input'), best = null;
    for (var i = 0; i < ins.length; i++) {
      var e = ins[i];
      if (!isField(e) || !vis(e) || (root && root.contains(e))) continue;
      var prev = e.previousSibling, txt = '';
      for (var n = 0; prev && n < 3; n++, prev = prev.previousSibling) txt = (prev.textContent || '') + txt;
      if (norm(txt).indexOf('차량번호') >= 0) return e;
      var cell = e.closest ? e.closest('td,th') : null;
      if (cell && cell.previousElementSibling && norm(cell.previousElementSibling.textContent).indexOf('차량번호') >= 0) best = best || e;
    }
    return best;
  }

  var steps = {
    // 이 프레임의 상태
    probe: function () {
      return {
        did: true, url: location.href,
        form: !!formRoot(),
        menu: !!clickable(document, '임대차 계약서'),
        login: !!document.querySelector('input[type=password]') && !formRoot(),
        carTable: !!carTable(),
        msgs: msgs.slice(-5),
      };
    },
    // 대여관리 → 임대차 계약서
    openForm: function () {
      if (formRoot()) return { did: true, already: true };
      var m = clickable(document, '임대차 계약서');
      if (!m) return { did: false };
      hookDialogs(false);
      clickEl(m);
      return { did: true };
    },
    fill: function (v) {
      var root = formRoot(); if (!root) return { did: false };
      hookDialogs(false);
      var ok = [], bad = [];
      function fill(t, name, idx, vals) {
        var c = labels(root, t)[0]; if (!c) { bad.push(name); return; }
        var f = after(c), n = 0;
        for (var k = 0; k < vals.length; k++) if (vals[k] != null && vals[k] !== '' && f[idx + k]) { put(f[idx + k], vals[k]); n++; }
        (n ? ok : bad).push(name);
      }
      function pick(t, name, want) {
        var c = labels(root, t)[0], s = c && after(c, 'SELECT')[0];
        if (!s) { bad.push(name); return; }
        var w = norm(want);
        for (var i = 0; i < s.options.length; i++) {
          var o = norm(s.options[i].text);
          if (o && o.indexOf('선택') < 0 && (o == w || o.indexOf(w) >= 0 || w.indexOf(o) >= 0)) {
            s.selectedIndex = i; s.style.backgroundColor = HL; fire(s, 'change'); ok.push(name); return;
          }
        }
        bad.push(name + '(' + want + ')');
      }
      if (v.rentType) pick('대여현황', '대여현황', v.rentType);
      if (v.name) fill('성명', '성명', 0, [v.name]);
      if (v.birth) fill('생년월일', '생년월일', 0, [v.birth]);
      if (v.hp) fill('휴대폰', '휴대폰', 0, [v.hp]);
      if (v.addr || v.addr2 || v.zip) fill('현주소', '주소', 0, [v.zip || '', v.addr || '', v.addr2 || '']);
      if (v.lic) fill('면허번호', '면허번호', 0, String(v.lic).split('-'));
      if (v.lictype) pick('면허구분', '면허구분', v.lictype);
      if (v.licexp) fill('유효기간', '유효기간', 0, [v.licexp]);
      if (v.start) fill('출발일시', '출발일시', 0, dt(v.start));
      if (v.end) fill('도착예정', '도착예정', 0, dt(v.end));
      if (v.place) fill('배차장소', '배차장소', 0, [v.place]);
      if (v.memo) { var ta = root.getElementsByTagName('textarea'); if (ta.length) { put(ta[0], v.memo); ok.push('비고'); } else bad.push('비고'); }
      return { did: true, ok: ok, bad: bad };
    },
    openCarSearch: function () {
      var root = formRoot(); if (!root) return { did: false };
      var b = clickable(root, '차량조회');
      if (!b) return { did: true, error: '계약서에서 [차량조회] 버튼을 못 찾았어요' };
      hookDialogs(false);
      clickEl(b);
      return { did: true };
    },
    carSearch: function (plate) {
      var t = carTable(); if (!t) return { did: false };
      var inp = carSearchInput(formRoot());
      var digits = String(plate).replace(/\D/g, '').slice(-4);
      if (!inp) return { did: true, searched: false };
      hookDialogs(false);
      put(inp, digits);
      var scope = inp.parentNode, btn = null;
      for (var up = 0; scope && up < 4 && !btn; up++, scope = scope.parentNode) btn = clickable(scope, '조회', true);
      if (btn) clickEl(btn);
      return { did: true, searched: !!btn };
    },
    carPick: function (plate) {
      var t = carTable(); if (!t) return { did: false };
      var want = norm(plate), last4 = want.replace(/\D/g, '').slice(-4), rows = t.getElementsByTagName('tr'), cand = [];
      for (var i = 0; i < rows.length; i++) {
        var cells = rows[i].getElementsByTagName('td'); if (!cells.length) continue;
        var no = norm(cells[0].textContent);
        if (no == want) { cand = [rows[i]]; break; }
        if (last4 && no.slice(-4) == last4) cand.push(rows[i]);
      }
      if (cand.length != 1) return { did: true, picked: false, found: cand.length };
      var sel = clickable(cand[0], '선택');
      if (!sel) return { did: true, picked: false, found: 1, error: '[선택] 버튼이 없어요' };
      var car = norm(cand[0].getElementsByTagName('td')[0].textContent);
      hookDialogs(false);
      clickEl(sel);
      return { did: true, picked: true, car: car };
    },
    // 계약서에 들어간 차량번호/차종 읽기
    readCar: function () {
      var root = formRoot(); if (!root) return { did: false };
      var c = labels(root, '차량번호')[0], k = labels(root, '차종')[0];
      var p = c && after(c)[0], m = k && after(k)[0];
      return { did: true, plate: p ? p.value : '', model: m ? m.value : '' };
    },
    save: function () {
      var root = formRoot(); if (!root) return { did: false };
      var b = clickable(root.ownerDocument, '저장(F2)', true) || clickable(root.ownerDocument, '저장', true);
      if (!b) return { did: true, error: '[저장] 버튼을 못 찾았어요' };
      hookDialogs(true);
      clickEl(b);
      return { did: true, saved: true };
    },
    banner: function (a) {
      if (a.onlyFormFrame && !formRoot()) return { did: false };
      if (a.topOnly && window !== window.top) return { did: false };
      try {
        var old = document.getElementById('pang-agent-banner'); if (old) old.parentNode.removeChild(old);
        var b = document.createElement('div');
        b.id = 'pang-agent-banner';
        b.style.cssText = 'position:fixed;top:0;left:0;right:0;z-index:2147483647;padding:14px;font-size:16px;line-height:1.5;color:black;white-space:pre-line;border-bottom:3px solid black;cursor:pointer;background:' + (a.ok ? 'rgb(200,255,200)' : 'rgb(255,200,200)');
        b.appendChild(document.createTextNode(a.text + '\n(이 알림은 클릭하면 닫혀요)'));
        b.onclick = function () { b.parentNode.removeChild(b); };
        document.body.appendChild(b);
      } catch (x) {}
      return { did: true };
    },
    done: function () { unhookDialogs(); var m = msgs.slice(); msgs = []; return { did: true, msgs: m }; },
  };

  window.__pangAgent = {
    v: VERSION,
    run: function (step, arg) {
      try { return steps[step](arg); } catch (e) { return { did: true, error: String(e && e.message || e) }; }
    },
  };
})();
