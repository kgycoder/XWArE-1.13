/**
 * XWare Android Bridge v3.4
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
     YouTube IFrame 이 백그라운드 감지 시 자동 일시정지 방지
  ════════════════════════════════════════════════ */
  try {
    Object.defineProperty(document, 'hidden', {
      get: function () { return false; }, configurable: true
    });
    Object.defineProperty(document, 'visibilityState', {
      get: function () { return 'visible'; }, configurable: true
    });
  } catch(e) {}

  /* visibilitychange 리스너 등록 자체를 차단 */
  var _origDocAdd = document.addEventListener.bind(document);
  document.addEventListener = function (type, fn, opts) {
    if (type === 'visibilitychange') return;
    return _origDocAdd(type, fn, opts);
  };

  /* pagehide / freeze 차단 */
  window.addEventListener('pagehide', function(e){ e.stopImmediatePropagation(); }, true);
  window.addEventListener('freeze',   function(e){ e.stopImmediatePropagation(); }, true);

  /* ════ app.js 로드 후 초기화 ════════════════════ */
  window.addEventListener('load', function () {

    /* ════════════════════════════════════════════
       비트 이펙트 + bgLoop 완전 정지
       bgLoop: requestAnimationFrame 기반 canvas 렌더링
       → 매 프레임 메인 스레드 점유 → 가사 jank + 싱크 오차
    ════════════════════════════════════════════ */
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

    /* ★ requestAnimationFrame 오버라이드: bgLoop 차단 */
    var _origRAF = window.requestAnimationFrame;
    window.requestAnimationFrame = function(cb) {
      var fnStr = cb ? cb.toString().substring(0, 150) : '';
      if (fnStr.indexOf('bgLoop')   !== -1 ||
          fnStr.indexOf('BG.f++')   !== -1 ||
          fnStr.indexOf('BG.orbs')  !== -1 ||
          fnStr.indexOf('BG.beat')  !== -1) {
        return 0;
      }
      return _origRAF(cb);
    };

    /* ★ setInterval 오버라이드: 50ms 이하 BG 관련 타이머 차단 */
    var _origSetInterval = window.setInterval;
    window.setInterval = function(fn, delay) {
      if (delay <= 50) {
        var fnStr = fn ? fn.toString().substring(0, 150) : '';
        if (fnStr.indexOf('updateNpColor') !== -1 ||
            fnStr.indexOf('BG.')           !== -1 ||
            fnStr.indexOf('_moodH')        !== -1) {
          return 0;
        }
      }
      return _origSetInterval.apply(window, arguments);
    };

    /* ════════════════════════════════════════════
       YT Player pauseVideo 인터셉트
       백그라운드 전환 시 YouTube 자동 일시정지 차단
    ════════════════════════════════════════════ */
    var _xwUserPaused = false;

    function installYTPauseHook() {
      if (!window.S || !S.ytPlayer) return;
      var player = S.ytPlayer;
      if (player._xwHooked) return;
      player._xwHooked = true;

      var _origPause = player.pauseVideo.bind(player);
      player.pauseVideo = function () {
        if (_xwUserPaused) {
          _xwUserPaused = false;
          return _origPause();
        }
        console.log('[Bridge] pauseVideo 자동 호출 차단');
      };

      var origToggle = window.togglePlay;
      if (typeof origToggle === 'function') {
        window.togglePlay = function () {
          if (typeof S !== 'undefined' && S.playing) _xwUserPaused = true;
          return origToggle.apply(this, arguments);
        };
      }
      console.log('[Bridge] YT pauseVideo 훅 설치 완료');
    }

    var hookTimer = setInterval(function () {
      if (window.S && S.ytPlayer && S.ytReady) {
        installYTPauseHook();
        clearInterval(hookTimer);
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
