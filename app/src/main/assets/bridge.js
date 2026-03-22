/**
 * XWare Android Bridge v3.3
 * 백그라운드 재생: pauseVideo 인터셉트 + visibilityState 오버라이드
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
     핵심 1: Page Visibility 완전 차단
     - document.visibilityState 항상 'visible'
     - visibilitychange 이벤트 리스너 차단
     - pagehide / freeze 이벤트 차단
  ════════════════════════════════════════════════ */
  try {
    Object.defineProperty(document, 'hidden', {
      get: function () { return false; },
      configurable: true
    });
    Object.defineProperty(document, 'visibilityState', {
      get: function () { return 'visible'; },
      configurable: true
    });
  } catch(e) {}

  /* ★ addEventListener 오버라이드를 즉시 (YouTube API 로드 전에) 실행 */
  var _origDocAdd = document.addEventListener.bind(document);
  document.addEventListener = function (type, fn, opts) {
    if (type === 'visibilitychange') {
      /* YouTube의 visibilitychange 콜백을 래핑: 항상 'visible'로 속임 */
      var wrappedFn = function (e) {
        var fakeEvent = new Event('visibilitychange');
        Object.defineProperty(fakeEvent, 'target', {
          get: function () {
            return Object.assign(Object.create(e.target), {
              hidden: false,
              visibilityState: 'visible'
            });
          }
        });
        /* 콜백 자체는 호출하지 않음 — YouTube가 일시정지하는 것을 방지 */
      };
      return _origDocAdd(type, wrappedFn, opts);
    }
    return _origDocAdd(type, fn, opts);
  };

  /* pagehide / freeze 차단 */
  window.addEventListener('pagehide',  function(e){ e.stopImmediatePropagation(); }, true);
  window.addEventListener('freeze',    function(e){ e.stopImmediatePropagation(); }, true);

  /* ════════════════════════════════════════════════
     app.js 로드 후 초기화
  ════════════════════════════════════════════════ */
  window.addEventListener('load', function () {

    /* ── 비트 이펙트 비활성화 ────────────────────── */
    if (typeof BG !== 'undefined') {
      try {
        Object.defineProperty(BG, 'beat', {
          get: function(){ return 0; }, set: function(){}
        });
        BG.orbs = []; BG.energyLevel = 0; BG.tEnergyLevel = 0;
      } catch(e) { try { BG.beat = 0; } catch(_) {} }
    }
    window.triggerBeat     = function(){};
    window.startBeatTimer  = function(){};
    window.stopBeatTimer   = function(){};
    window.spawnParticles  = function(){};
    window.renderParticles = function(){};
    window.updateSpectrum  = function(){};

    /* ════════════════════════════════════════════
       핵심 2: YT Player의 pauseVideo 인터셉트
       백그라운드 전환 시 YouTube가 pauseVideo()를
       직접 호출하는 경우도 차단
    ════════════════════════════════════════════ */
    var _xwUserPaused = false; /* 사용자가 직접 일시정지했는지 추적 */

    function installYTPauseHook() {
      if (!window.S || !S.ytPlayer) return;
      var player = S.ytPlayer;
      if (player._xwHooked) return;
      player._xwHooked = true;

      var _origPause = player.pauseVideo.bind(player);
      player.pauseVideo = function () {
        /* 사용자가 직접 누른 경우만 허용 */
        if (_xwUserPaused) {
          _xwUserPaused = false;
          return _origPause();
        }
        /* 백그라운드에서 자동 호출된 경우 → 무시 */
        console.log('[Bridge] pauseVideo 자동 호출 차단 (백그라운드)');
      };

      /* 사용자 일시정지 버튼 클릭 시 플래그 설정 */
      var origToggle = window.togglePlay;
      if (typeof origToggle === 'function') {
        window.togglePlay = function () {
          if (typeof S !== 'undefined' && S.playing) {
            _xwUserPaused = true;
          }
          return origToggle.apply(this, arguments);
        };
      }
      console.log('[Bridge] YT pauseVideo 훅 설치 완료');
    }

    /* ytPlayer가 준비될 때까지 대기 후 훅 설치 */
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
