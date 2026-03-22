/**
 * XWare Android Bridge v3.5
 */
(function () {
  'use strict';

  var isAndroid = typeof window.AndroidBridge !== 'undefined'
               || /Android/i.test(navigator.userAgent);

  /* ── Chrome webview 폴리필 ──────────────────────── */
  if (!window.chrome)         window.chrome         = {};
  if (!window.chrome.webview) window.chrome.webview = {
    postMessage: function (msg) {
      try {
        if (window.AndroidBridge) window.AndroidBridge.postMessage(msg);
        else console.warn('[Bridge] AndroidBridge 없음');
      } catch (e) { console.error('[Bridge] 오류: ' + e); }
    }
  };

  if (!isAndroid) return;

  /* ── Android 클래스 + 안전 영역 ──────────────────── */
  document.documentElement.classList.add('android');
  var root = document.documentElement;
  root.style.setProperty('--sat', 'env(safe-area-inset-top,0px)');
  root.style.setProperty('--sab', 'env(safe-area-inset-bottom,0px)');

  /* ── 터치 위치 추적 ────────────────────────────── */
  document.addEventListener('touchstart', function (e) {
    if (e.touches && e.touches[0])
      window._lastMouseEvt = { clientX: e.touches[0].clientX, clientY: e.touches[0].clientY };
  }, { passive: true });

  /* ── 뒤로가기 ─────────────────────────────────── */
  window.xwareHandleBack = function () {
    var np = document.getElementById('np');
    if (np && np.classList.contains('on')) {
      if (typeof closeNP === 'function') { closeNP(); return true; }
    }
    var home = document.getElementById('v-home');
    if (home && !home.classList.contains('on')) {
      if (typeof gv === 'function') {
        gv('home', document.querySelector('[data-v="home"]'));
        return true;
      }
    }
    return false;
  };

  /* ════════════════════════════════════════════════
     Page Visibility 완전 차단
  ════════════════════════════════════════════════ */
  try {
    Object.defineProperty(document, 'hidden', {
      get: function () { return false; }, configurable: true
    });
    Object.defineProperty(document, 'visibilityState', {
      get: function () { return 'visible'; }, configurable: true
    });
  } catch(e) {}

  var _origDocAdd = document.addEventListener.bind(document);
  document.addEventListener = function (type, fn, opts) {
    if (type === 'visibilitychange') return;
    return _origDocAdd(type, fn, opts);
  };

  window.addEventListener('pagehide', function(e){ e.stopImmediatePropagation(); }, true);
  window.addEventListener('freeze',   function(e){ e.stopImmediatePropagation(); }, true);

  /* ════ app.js 로드 후 초기화 ════════════════════ */
  window.addEventListener('load', function () {

    /* ── 비트 이펙트 + bgLoop 완전 정지 ─────────── */
    if (typeof BG !== 'undefined') {
      try {
        Object.defineProperty(BG, 'beat', {
          get: function(){ return 0; }, set: function(){}
        });
        BG.orbs = []; BG.energyLevel = 0; BG.tEnergyLevel = 0;
        BG.playing = false;
      } catch(e) { try { BG.beat = 0; } catch(_) {} }
    }
    window.triggerBeat     = function(){};
    window.startBeatTimer  = function(){};
    window.stopBeatTimer   = function(){};
    window.spawnParticles  = function(){};
    window.renderParticles = function(){};
    window.updateSpectrum  = function(){};

    /* ★ requestAnimationFrame: bgLoop 차단 */
    var _origRAF = window.requestAnimationFrame;
    window.requestAnimationFrame = function(cb) {
      var fnStr = cb ? cb.toString().substring(0, 150) : '';
      if (fnStr.indexOf('bgLoop')  !== -1 ||
          fnStr.indexOf('BG.f++')  !== -1 ||
          fnStr.indexOf('BG.orbs') !== -1 ||
          fnStr.indexOf('BG.beat') !== -1) return 0;
      return _origRAF(cb);
    };

    /* ★ setInterval: 50ms 이하 BG 관련 타이머 차단 */
    var _origSetInterval = window.setInterval;
    window.setInterval = function(fn, delay) {
      if (delay <= 50) {
        var fnStr = fn ? fn.toString().substring(0, 150) : '';
        if (fnStr.indexOf('updateNpColor') !== -1 ||
            fnStr.indexOf('BG.')           !== -1 ||
            fnStr.indexOf('_moodH')        !== -1) return 0;
      }
      return _origSetInterval.apply(window, arguments);
    };

    /* ════════════════════════════════════════════
       ★ 플레이리스트 추가 버튼 주입
       참고 이미지처럼 오버레이 버튼 옆에 "+" 버튼 추가
    ════════════════════════════════════════════ */
    function injectAddButton() {
      var overlayBtn = document.getElementById('bt-overlay-btn');
      if (!overlayBtn || document.getElementById('bt-addpl-btn')) return;

      var btn = document.createElement('button');
      btn.id        = 'bt-addpl-btn';
      btn.className = 'bt-addpl-btn';
      btn.title     = '플레이리스트에 추가';
      btn.innerHTML =
        '<svg width="17" height="17" viewBox="0 0 17 17" fill="none" ' +
        'stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">' +
        '<circle cx="8.5" cy="8.5" r="7"/>' +
        '<line x1="8.5" y1="5.2" x2="8.5" y2="11.8"/>' +
        '<line x1="5.2" y1="8.5" x2="11.8" y2="8.5"/>' +
        '</svg>';

      btn.addEventListener('click', function (e) {
        e.stopPropagation();
        try {
          /* 현재 재생 중인 트랙을 플레이리스트에 추가 */
          if (typeof S !== 'undefined' && S.track) {
            if (typeof plAddTrack === 'function') {
              /* 플레이리스트 선택 모달 오픈 */
              plAddTrack(S.track);
            } else if (typeof ctxOpen === 'function') {
              ctxOpen(S.track, S.qIdx !== undefined ? S.qIdx : 0, 'q');
            } else if (typeof plAddModalOpen === 'function') {
              plAddModalOpen();
            }
          } else {
            if (typeof toast === 'function') toast('재생 중인 곡이 없습니다');
          }
        } catch(err) { console.warn('[Bridge] addpl:', err); }
      });

      /* 오버레이 버튼 바로 다음에 삽입 */
      overlayBtn.parentNode.insertBefore(btn, overlayBtn.nextSibling);
    }

    /* DOM 준비 후 삽입 */
    if (document.getElementById('bt-overlay-btn')) {
      injectAddButton();
    } else {
      var obs = new MutationObserver(function() {
        if (document.getElementById('bt-overlay-btn')) {
          injectAddButton(); obs.disconnect();
        }
      });
      obs.observe(document.body, { childList: true, subtree: true });
    }

    /* ── YT pauseVideo 인터셉트 ──────────────────── */
    var _xwUserPaused = false;
    function installYTPauseHook() {
      if (!window.S || !S.ytPlayer) return;
      var player = S.ytPlayer;
      if (player._xwHooked) return;
      player._xwHooked = true;
      var _origPause = player.pauseVideo.bind(player);
      player.pauseVideo = function () {
        if (_xwUserPaused) { _xwUserPaused = false; return _origPause(); }
        console.log('[Bridge] pauseVideo 자동 호출 차단');
      };
      var origToggle = window.togglePlay;
      if (typeof origToggle === 'function') {
        window.togglePlay = function () {
          if (typeof S !== 'undefined' && S.playing) _xwUserPaused = true;
          return origToggle.apply(this, arguments);
        };
      }
    }
    var hookTimer = setInterval(function () {
      if (window.S && S.ytPlayer && S.ytReady) {
        installYTPauseHook(); clearInterval(hookTimer);
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
      } catch(e) {}
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
            type: 'playState',
            playing: (typeof S !== 'undefined') ? !!S.playing : false
          }));
        } catch(e) {}
      };
    }

    /* ── 트랙 변경 동기화 ────────────────────────── */
    var _origPlayTrack = window.playTrack;
    if (typeof _origPlayTrack === 'function') {
      window.playTrack = function (t, idx) {
        _origPlayTrack.apply(this, arguments);
        try {
          window.chrome.webview.postMessage(JSON.stringify({
            type: 'trackChanged',
            title:   (t && t.title)   ? t.title   : '',
            channel: (t && t.channel) ? t.channel : '',
            thumb:   (t && t.thumb)   ? t.thumb   : ''
          }));
        } catch(e) {}
      };
    }

    console.log('[Bridge] 초기화 완료 | origin=' + location.origin);
  });

})();
