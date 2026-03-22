/**
 * XWare Android Bridge v4.0
 */
(function () {
  'use strict';

  var isAndroid = typeof window.AndroidBridge !== 'undefined'
               || /Android/i.test(navigator.userAgent);

  if (!window.chrome)         window.chrome         = {};
  if (!window.chrome.webview) window.chrome.webview = {
    postMessage: function (msg) {
      try { if (window.AndroidBridge) window.AndroidBridge.postMessage(msg); } catch (e) {}
    }
  };

  if (!isAndroid) return;

  document.documentElement.classList.add('android');
  document.documentElement.style.setProperty('--sat', 'env(safe-area-inset-top,0px)');
  document.documentElement.style.setProperty('--sab', 'env(safe-area-inset-bottom,0px)');

  /* ★ tap highlight 전역 제거 */
  (function () {
    var s = document.createElement('style');
    s.textContent = '* { -webkit-tap-highlight-color: transparent !important; outline: none !important; }';
    (document.head || document.documentElement).appendChild(s);
  })();

  /* ── 터치 위치 추적 ─────────────────────────── */
  document.addEventListener('touchstart', function (e) {
    if (e.touches && e.touches[0])
      window._lastMouseEvt = { clientX: e.touches[0].clientX, clientY: e.touches[0].clientY };
  }, { passive: true });

  /* ── 뒤로가기 ───────────────────────────────── */
  window.xwareHandleBack = function () {
    if (document.getElementById('xw-pl-modal'))    { _closePlModal();    return true; }
    if (document.getElementById('xw-input-modal')) { _closeInputModal(); return true; }
    var np = document.getElementById('np');
    if (np && np.classList.contains('on')) {
      if (typeof closeNP === 'function') { closeNP(); return true; }
    }
    var home = document.getElementById('v-home');
    if (home && !home.classList.contains('on')) {
      if (typeof gv === 'function') {
        gv('home', document.querySelector('[data-v="home"]')); return true;
      }
    }
    return false;
  };

  /* ════ Page Visibility 차단 ═════════════════════ */
  try {
    Object.defineProperty(document, 'hidden',
      { get: function () { return false; }, configurable: true });
    Object.defineProperty(document, 'visibilityState',
      { get: function () { return 'visible'; }, configurable: true });
  } catch (e) {}
  var _origDocAdd = document.addEventListener.bind(document);
  document.addEventListener = function (type, fn, opts) {
    if (type === 'visibilitychange') return;
    return _origDocAdd(type, fn, opts);
  };
  window.addEventListener('pagehide', function (e) { e.stopImmediatePropagation(); }, true);
  window.addEventListener('freeze',   function (e) { e.stopImmediatePropagation(); }, true);

  /* ════ app.js 로드 후 초기화 ════════════════════ */
  window.addEventListener('load', function () {

    /* ── bgLoop 완전 정지 ─────────────────────── */
    if (typeof BG !== 'undefined') {
      try {
        Object.defineProperty(BG, 'beat', { get: function(){ return 0; }, set: function(){} });
        BG.orbs = []; BG.energyLevel = 0; BG.tEnergyLevel = 0; BG.playing = false;
      } catch (e) {}
    }
    window.triggerBeat = window.startBeatTimer = window.stopBeatTimer =
    window.spawnParticles = window.renderParticles = window.updateSpectrum = function(){};

    var _origRAF = window.requestAnimationFrame;
    window.requestAnimationFrame = function (cb) {
      var s = cb ? cb.toString().substring(0, 150) : '';
      if (s.indexOf('bgLoop') !== -1 || s.indexOf('BG.f++') !== -1 ||
          s.indexOf('BG.orbs') !== -1) return 0;
      return _origRAF(cb);
    };
    var _origSI = window.setInterval;
    window.setInterval = function (fn, delay) {
      if (delay <= 50) {
        var s = fn ? fn.toString().substring(0, 150) : '';
        if (s.indexOf('updateNpColor') !== -1 || s.indexOf('BG.') !== -1) return 0;
      }
      return _origSI.apply(window, arguments);
    };

    /* ════════════════════════════════════════════
       ★ 트랙 정규화
       앱이 사용하는 정확한 포맷: dur (duration 아님)
    ════════════════════════════════════════════ */
    function _normTrack(t) {
      return {
        id:      t.id      || t.videoId   || '',
        title:   t.title   || '',
        channel: t.channel || t.artist    || '',
        dur:     t.dur     || t.duration  || 0,   // ★ 'dur' 사용
        thumb:   t.thumb   || t.thumbnail || ''
      };
    }

    /* ════════════════════════════════════════════
       ★ 플레이리스트 추가 — 앱 함수 직접 호출
       plAddTrack(plId, track):
         - PL.lists 인메모리 업데이트
         - localStorage 'xw_pl' 저장
         - 토스트 표시
         - 플레이리스트 상세 뷰 재렌더
    ════════════════════════════════════════════ */
    function _addToPL(pl, track) {
      var t = _normTrack(track);

      /* ★ 앱 네이티브 함수 직접 호출 — 즉시 반영 보장 */
      if (typeof plAddTrack === 'function') {
        var ok = plAddTrack(pl.id, t);
        /* plAddTrack이 grid를 렌더하지 않으므로 추가로 호출 */
        if (typeof plRenderGrid === 'function') plRenderGrid();
        return ok !== false;
      }

      /* ── Fallback: PL 직접 조작 ── */
      if (typeof PL !== 'undefined' && PL.lists) {
        var target = null;
        for (var i = 0; i < PL.lists.length; i++) {
          if (PL.lists[i].id === pl.id) { target = PL.lists[i]; break; }
        }
        if (!target) { PL.lists.push(pl); target = pl; }
        if (!target.tracks) target.tracks = [];
        for (var j = 0; j < target.tracks.length; j++) {
          if (target.tracks[j].id === t.id) {
            if (typeof toast === 'function') toast('이미 추가된 곡이에요');
            return false;
          }
        }
        target.tracks.push(t);
        if (typeof plSave       === 'function') plSave();
        if (typeof plRenderGrid === 'function') plRenderGrid();
        if (typeof toast        === 'function') toast('✦ "' + pl.name + '"에 추가됨');
        return true;
      }

      /* ── 최후 Fallback: localStorage 직접 ── */
      try {
        var raw = JSON.parse(localStorage.getItem('xw_pl') || '[]');
        var tgt = null;
        for (var k = 0; k < raw.length; k++) {
          if (raw[k].id === pl.id) { tgt = raw[k]; break; }
        }
        if (!tgt) { raw.push(pl); tgt = pl; }
        if (!tgt.tracks) tgt.tracks = [];
        tgt.tracks.push(t);
        localStorage.setItem('xw_pl', JSON.stringify(raw));
        if (typeof toast === 'function') toast('✦ "' + pl.name + '"에 추가됨');
        return true;
      } catch (e) {}
      return false;
    }

    /* ★ 새 플레이리스트 생성 + 트랙 추가 */
    function _createAndAdd(name, track) {
      var t    = _normTrack(track);
      var newPl = {
        id:      'pl_' + Date.now(),
        name:    name,
        tracks:  [],
        created: Date.now()
      };

      if (typeof PL !== 'undefined' && PL.lists &&
          typeof plSave === 'function' && typeof plRenderGrid === 'function') {
        PL.lists.unshift(newPl);
        plSave();
        /* plAddTrack으로 트랙 추가 (토스트 + 상세 렌더 포함) */
        if (typeof plAddTrack === 'function') {
          plAddTrack(newPl.id, t);
        } else {
          newPl.tracks.push(t);
          plSave();
        }
        plRenderGrid();
        return;
      }

      /* Fallback: localStorage 직접 */
      try {
        var raw = JSON.parse(localStorage.getItem('xw_pl') || '[]');
        newPl.tracks.push(t);
        raw.unshift(newPl);
        localStorage.setItem('xw_pl', JSON.stringify(raw));
        if (typeof toast === 'function') toast('✦ "' + name + '"에 추가됨');
      } catch (e) {}
    }

    /* ════════════════════════════════════════════
       플레이리스트 목록 가져오기
    ════════════════════════════════════════════ */
    function _getPlaylists() {
      /* 앱 in-memory 배열 우선 */
      if (typeof PL !== 'undefined' && Array.isArray(PL.lists)) return PL.lists;
      /* localStorage fallback */
      try {
        var raw = localStorage.getItem('xw_pl');
        if (raw) return JSON.parse(raw);
      } catch (e) {}
      return [];
    }

    /* ════════════════════════════════════════════
       커스텀 입력 다이얼로그 (prompt 대체)
    ════════════════════════════════════════════ */
    function _xwPrompt(title, hint, onConfirm) {
      var old = document.getElementById('xw-input-modal');
      if (old) old.remove();

      var overlay = document.createElement('div');
      overlay.id = 'xw-input-modal';
      overlay.setAttribute('style', [
        'position:fixed','inset:0','z-index:999999',
        'background:rgba(0,0,0,0.72)',
        'display:flex','align-items:center','justify-content:center',
        'padding:24px','box-sizing:border-box',
        '-webkit-tap-highlight-color:transparent'
      ].join(';'));

      var dialog = document.createElement('div');
      dialog.setAttribute('style', [
        'background:#13131f','border-radius:20px','padding:24px 20px 16px',
        'width:100%','max-width:310px',
        'box-shadow:0 24px 64px rgba(0,0,0,0.85)',
        'border:1px solid rgba(255,255,255,0.08)',
        'transform:scale(0.92)','opacity:0'
      ].join(';'));

      var titleEl = document.createElement('div');
      titleEl.setAttribute('style',
        'font-size:15px;font-weight:700;color:rgba(255,255,255,0.92);' +
        'margin-bottom:16px;text-align:center;font-family:sans-serif');
      titleEl.textContent = title || '이름 입력';

      var input = document.createElement('input');
      input.type        = 'text';
      input.placeholder = hint || '플레이리스트 이름';
      input.setAttribute('style', [
        'width:100%','box-sizing:border-box',
        'background:rgba(255,255,255,0.07)',
        'border:1.5px solid rgba(255,255,255,0.12)','border-radius:12px',
        'padding:13px 14px','font-size:14px','font-weight:500',
        'color:rgba(255,255,255,0.92)','-webkit-appearance:none',
        'margin-bottom:20px','font-family:sans-serif','caret-color:#fa2d5a',
        'outline:none'
      ].join(';'));
      input.addEventListener('focus', function () {
        this.style.borderColor = 'rgba(250,45,90,0.6)';
      });
      input.addEventListener('blur', function () {
        this.style.borderColor = 'rgba(255,255,255,0.12)';
      });

      var btnRow = document.createElement('div');
      btnRow.setAttribute('style', 'display:flex;gap:10px');

      function _mkBtn(label, bg, color) {
        var b = document.createElement('button');
        b.textContent = label;
        b.setAttribute('style', [
          'flex:1','padding:13px 0','border-radius:12px',
          'background:' + bg,'color:' + color,
          'font-size:14px','font-weight:600',
          'border:none','-webkit-appearance:none',
          'cursor:pointer','font-family:sans-serif',
          '-webkit-tap-highlight-color:transparent'
        ].join(';'));
        b.addEventListener('touchstart', function(){ this.style.opacity='0.7'; }, {passive:true});
        b.addEventListener('touchend',   function(){ this.style.opacity='1';   }, {passive:true});
        return b;
      }

      var cancelBtn  = _mkBtn('취소',   'rgba(255,255,255,0.07)', 'rgba(255,255,255,0.55)');
      var confirmBtn = _mkBtn('만들기', 'var(--acc,#fa2d5a)',     '#fff');

      function _closeInput() {
        dialog.style.transition = 'transform .18s ease-in,opacity .18s';
        dialog.style.transform  = 'scale(0.94)';
        dialog.style.opacity    = '0';
        overlay.style.transition = 'opacity .18s';
        overlay.style.opacity    = '0';
        setTimeout(function () { if (overlay.parentNode) overlay.remove(); }, 200);
      }
      window._closeInputModal = _closeInput;

      function _doConfirm() {
        var val = input.value.trim();
        if (!val) { input.style.borderColor = '#fa2d5a'; input.focus(); return; }
        _closeInput(); onConfirm(val);
      }

      cancelBtn.addEventListener('click',  _closeInput);
      confirmBtn.addEventListener('click', _doConfirm);
      input.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' || e.keyCode === 13) _doConfirm();
      });
      overlay.addEventListener('click', function (e) {
        if (e.target === overlay) _closeInput();
      });

      btnRow.appendChild(cancelBtn);
      btnRow.appendChild(confirmBtn);
      dialog.appendChild(titleEl);
      dialog.appendChild(input);
      dialog.appendChild(btnRow);
      overlay.appendChild(dialog);
      document.body.appendChild(overlay);

      requestAnimationFrame(function () {
        requestAnimationFrame(function () {
          dialog.style.transition = 'transform .22s cubic-bezier(.34,1.4,.64,1),opacity .18s ease';
          dialog.style.transform  = 'scale(1)';
          dialog.style.opacity    = '1';
          setTimeout(function () { input.focus(); }, 80);
        });
      });
    }

    /* ════════════════════════════════════════════
       플레이리스트 선택 모달
    ════════════════════════════════════════════ */
    function _openPlModal(forcedTrack) {
      if (document.getElementById('xw-pl-modal')) { _closePlModal(); return; }
      var track = forcedTrack || ((typeof S !== 'undefined') ? S.track : null);
      if (!track) {
        if (typeof toast === 'function') toast('재생 중인 곡이 없습니다');
        return;
      }

      var pls = _getPlaylists();

      var modal = document.createElement('div');
      modal.id = 'xw-pl-modal';
      modal.setAttribute('style', [
        'position:fixed','inset:0','z-index:99999',
        'background:rgba(0,0,0,0.60)',
        'display:flex','flex-direction:column','justify-content:flex-end',
        '-webkit-tap-highlight-color:transparent'
      ].join(';'));

      var sheet = document.createElement('div');
      sheet.setAttribute('style', [
        'background:#0e0e1e','border-radius:22px 22px 0 0',
        'padding-bottom:calc(env(safe-area-inset-bottom,0px) + 12px)',
        'max-height:72vh','overflow-y:auto','overscroll-behavior:contain',
        '-webkit-overflow-scrolling:touch',
        'transform:translateY(100%)',
        'transition:transform .28s cubic-bezier(.32,0,.18,1)'
      ].join(';'));

      /* 헤더 */
      var header = document.createElement('div');
      header.setAttribute('style',
        'padding:14px 20px 12px;border-bottom:1px solid rgba(255,255,255,0.07)');
      header.innerHTML =
        '<div style="width:38px;height:4px;background:rgba(255,255,255,0.18);' +
        'border-radius:2px;margin:0 auto 14px"></div>' +
        '<div style="font-size:14px;font-weight:700;color:rgba(255,255,255,0.92);' +
        'font-family:sans-serif">플레이리스트에 추가</div>' +
        '<div style="font-size:11px;color:rgba(255,255,255,0.38);margin-top:4px;' +
        'white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-family:sans-serif">' +
        (track.title || '') + '</div>';
      sheet.appendChild(header);

      /* 기존 플레이리스트 목록 */
      pls.forEach(function (pl) {
        var count = pl.tracks ? pl.tracks.length : 0;
        var item  = document.createElement('div');
        item.setAttribute('style', [
          'display:flex','align-items:center','gap:14px','padding:13px 20px',
          'cursor:pointer','-webkit-tap-highlight-color:transparent',
          'border-bottom:1px solid rgba(255,255,255,0.04)',
          'transition:background .12s'
        ].join(';'));
        item.innerHTML =
          '<div style="width:44px;height:44px;flex-shrink:0;border-radius:9px;' +
          'background:rgba(255,255,255,0.07);display:flex;align-items:center;justify-content:center">' +
            '<svg width="20" height="20" viewBox="0 0 20 20" fill="none" ' +
            'stroke="rgba(255,255,255,0.55)" stroke-width="1.6" stroke-linecap="round">' +
              '<line x1="3" y1="5" x2="17" y2="5"/>' +
              '<line x1="3" y1="10" x2="13" y2="10"/>' +
              '<line x1="3" y1="15" x2="10" y2="15"/>' +
            '</svg>' +
          '</div>' +
          '<div style="flex:1;min-width:0">' +
            '<div style="font-size:13.5px;font-weight:600;color:rgba(255,255,255,0.88);' +
            'white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-family:sans-serif">' +
            (pl.name || '플레이리스트') + '</div>' +
            '<div style="font-size:11px;color:rgba(255,255,255,0.35);margin-top:2px;' +
            'font-family:sans-serif">' + count + '곡</div>' +
          '</div>' +
          '<svg width="16" height="16" viewBox="0 0 16 16" fill="none" ' +
          'stroke="rgba(255,255,255,0.2)" stroke-width="1.5" stroke-linecap="round">' +
            '<polyline points="6,3 11,8 6,13"/>' +
          '</svg>';

        item.addEventListener('touchstart',
          function(){ this.style.background='rgba(255,255,255,0.06)'; }, {passive:true});
        item.addEventListener('touchend',
          function(){ this.style.background=''; }, {passive:true});
        item.addEventListener('click', function () {
          _closePlModal();
          /* ★ 앱 네이티브 함수 직접 호출 → 즉시 반영 */
          _addToPL(pl, track);
        });
        sheet.appendChild(item);
      });

      /* 새 플레이리스트 만들기 */
      var newRow = document.createElement('div');
      newRow.setAttribute('style', [
        'display:flex','align-items:center','gap:14px','padding:14px 20px 10px',
        'cursor:pointer','-webkit-tap-highlight-color:transparent',
        'transition:background .12s'
      ].join(';'));
      newRow.innerHTML =
        '<div style="width:44px;height:44px;flex-shrink:0;border-radius:9px;' +
        'border:1.5px dashed rgba(250,45,90,0.5);display:flex;align-items:center;justify-content:center">' +
          '<svg width="20" height="20" viewBox="0 0 20 20" fill="none" ' +
          'stroke="#fa2d5a" stroke-width="2" stroke-linecap="round">' +
            '<line x1="10" y1="4" x2="10" y2="16"/>' +
            '<line x1="4" y1="10" x2="16" y2="10"/>' +
          '</svg>' +
        '</div>' +
        '<div style="font-size:13.5px;font-weight:600;color:#fa2d5a;' +
        'font-family:sans-serif">새 플레이리스트 만들기</div>';

      newRow.addEventListener('touchstart',
        function(){ this.style.background='rgba(250,45,90,0.06)'; }, {passive:true});
      newRow.addEventListener('touchend',
        function(){ this.style.background=''; }, {passive:true});
      newRow.addEventListener('click', function () {
        _closePlModal();
        setTimeout(function () {
          _xwPrompt('새 플레이리스트', '플레이리스트 이름', function (name) {
            /* ★ 앱 네이티브 함수로 즉시 반영 */
            _createAndAdd(name, track);
          });
        }, 260);
      });

      sheet.appendChild(newRow);
      modal.appendChild(sheet);
      document.body.appendChild(modal);

      requestAnimationFrame(function () {
        requestAnimationFrame(function () {
          sheet.style.transform = 'translateY(0)';
        });
      });
      modal.addEventListener('click', function (e) {
        if (e.target === modal) _closePlModal();
      });
    }

    function _closePlModal() {
      var modal = document.getElementById('xw-pl-modal');
      if (!modal) return;
      var sheet = modal.children[0];
      if (sheet) {
        sheet.style.transition = 'transform .22s ease-in';
        sheet.style.transform  = 'translateY(100%)';
      }
      setTimeout(function () { if (modal.parentNode) modal.remove(); }, 230);
    }
    window._closePlModal = _closePlModal;

    /* ── 미니플레이어 + 버튼 inject ─────────────── */
    function injectAddButton() {
      var overlayBtn = document.getElementById('bt-overlay-btn');
      if (!overlayBtn || document.getElementById('bt-addpl-btn')) return;
      var btn = document.createElement('button');
      btn.id        = 'bt-addpl-btn';
      btn.className = 'bt-addpl-btn';
      btn.title     = '플레이리스트에 추가';
      btn.innerHTML =
        '<svg width="17" height="17" viewBox="0 0 17 17" fill="none" ' +
        'stroke="currentColor" stroke-width="1.5" stroke-linecap="round">' +
        '<circle cx="8.5" cy="8.5" r="7"/>' +
        '<line x1="8.5" y1="5.5" x2="8.5" y2="11.5"/>' +
        '<line x1="5.5" y1="8.5" x2="11.5" y2="8.5"/></svg>';
      btn.addEventListener('click', function (e) {
        e.stopPropagation();
        _openPlModal();
      });
      overlayBtn.parentNode.insertBefore(btn, overlayBtn.nextSibling);
    }

    if (document.getElementById('bt-overlay-btn')) {
      injectAddButton();
    } else {
      var _obs = new MutationObserver(function () {
        if (document.getElementById('bt-overlay-btn')) {
          injectAddButton(); _obs.disconnect();
        }
      });
      _obs.observe(document.body || document.documentElement, { childList: true, subtree: true });
    }

    /* ── YT pauseVideo 인터셉트 ──────────────────── */
    var _xwUserPaused = false;
    function _installYTPauseHook() {
      if (!window.S || !S.ytPlayer || S.ytPlayer._xwHooked) return;
      S.ytPlayer._xwHooked = true;
      var _origPause = S.ytPlayer.pauseVideo.bind(S.ytPlayer);
      S.ytPlayer.pauseVideo = function () {
        if (_xwUserPaused) { _xwUserPaused = false; return _origPause(); }
      };
      var _origToggle = window.togglePlay;
      if (typeof _origToggle === 'function') {
        window.togglePlay = function () {
          if (typeof S !== 'undefined' && S.playing) _xwUserPaused = true;
          return _origToggle.apply(this, arguments);
        };
      }
    }
    var _hookTimer = setInterval(function () {
      if (window.S && S.ytPlayer && S.ytReady) {
        _installYTPauseHook(); clearInterval(_hookTimer);
      }
    }, 500);

    /* ── 오버레이 모드 ───────────────────────────── */
    var _androidOverlayOn = false;
    window.toggleOverlay = function () {
      _androidOverlayOn = !_androidOverlayOn;
      document.getElementById('bt-overlay-btn')
        ?.classList.toggle('on', _androidOverlayOn);
      document.getElementById('np-overlay-btn')
        ?.classList.toggle('on', _androidOverlayOn);
      try {
        window.chrome.webview.postMessage(JSON.stringify({
          type: 'overlayMode', active: _androidOverlayOn
        }));
      } catch (e) {}
      if (typeof toast === 'function')
        toast(_androidOverlayOn ? '오버레이 켜짐' : '오버레이 꺼짐');
    };

    /* ── 재생 상태 동기화 ────────────────────────── */
    var _origUpdPlay = window.updPlay;
    if (typeof _origUpdPlay === 'function') {
      window.updPlay = function () {
        _origUpdPlay.apply(this, arguments);
        try {
          window.chrome.webview.postMessage(JSON.stringify({
            type:    'playState',
            playing: (typeof S !== 'undefined') ? !!S.playing : false
          }));
        } catch (e) {}
      };
    }

    /* ── 트랙 변경 동기화 ────────────────────────── */
    var _origPlayTrack = window.playTrack;
    if (typeof _origPlayTrack === 'function') {
      window.playTrack = function (t, idx) {
        _origPlayTrack.apply(this, arguments);
        try {
          window.chrome.webview.postMessage(JSON.stringify({
            type:    'trackChanged',
            title:   (t && t.title)   ? t.title   : '',
            channel: (t && t.channel) ? t.channel : '',
            thumb:   (t && t.thumb)   ? t.thumb   : ''
          }));
        } catch (e) {}
      };
    }

    console.log('[Bridge] 초기화 완료 v4.0');
  });

})();
