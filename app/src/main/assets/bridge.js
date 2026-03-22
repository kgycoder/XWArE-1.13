/**
 * XWare Android Bridge v3.1
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

  /* ── 뒤로가기 ────────────────────────────────────── */
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

  /* ════ app.js 로드 후 초기화 ════════════════════ */
  window.addEventListener('load', function () {

    /* ── ★ 핵심: Page Visibility API 오버라이드 ──────
       YouTube IFrame은 document.visibilityState 를 감지해서
       앱이 백그라운드로 가면 자동으로 영상을 일시정지함.
       항상 'visible'을 반환하도록 오버라이드해서 방지.
    ─────────────────────────────────────────────── */
    try {
      Object.defineProperty(document, 'hidden', {
        get: function() { return false; },
        configurable: true
      });
      Object.defineProperty(document, 'visibilityState', {
        get: function() { return 'visible'; },
        configurable: true
      });
      // visibilitychange 이벤트 발생 자체를 막음
      var _origAddEventListener = document.addEventListener.bind(document);
      document.addEventListener = function(type, listener, options) {
        if (type === 'visibilitychange') {
          console.log('[Bridge] visibilitychange 이벤트 차단');
          return;
        }
        return _origAddEventListener(type, listener, options);
      };
      console.log('[Bridge] Page Visibility API 오버라이드 완료');
    } catch(e) {
      console.warn('[Bridge] Visibility 오버라이드 실패: ' + e);
    }

    /* ── 비트 이펙트 비활성화 ────────────────────── */
    if (typeof BG !== 'undefined') {
      try {
        Object.defineProperty(BG, 'beat', { get: function(){ return 0; }, set: function(){} });
        BG.orbs = []; BG.energyLevel = 0; BG.tEnergyLevel = 0;
      } catch(e) { try { BG.beat = 0; } catch(_) {} }
    }
    window.triggerBeat    = function(){};
    window.startBeatTimer = function(){};
    window.stopBeatTimer  = function(){};
    window.spawnParticles = function(){};
    window.renderParticles= function(){};
    window.updateSpectrum = function(){};

    /* ── 오버레이 모드 ───────────────────────────── */
    var _androidOverlayOn = false;
    window.toggleOverlay = function() {
      _androidOverlayOn = !_androidOverlayOn;
      document.getElementById('bt-overlay-btn')?.classList.toggle('on', _androidOverlayOn);
      document.getElementById('np-overlay-btn')?.classList.toggle('on', _androidOverlayOn);
      try {
        window.chrome.webview.postMessage(JSON.stringify({
          type: 'overlayMode', active: _androidOverlayOn
        }));
      } catch(e) {}
      if (typeof toast === 'function')
        toast(_androidOverlayOn ? '🫧 오버레이 켜짐' : '오버레이 꺼짐');
    };

    /* ── 재생 상태 동기화 ────────────────────────── */
    var _origUpdPlay = window.updPlay;
    if (typeof _origUpdPlay === 'function') {
      window.updPlay = function() {
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
      window.playTrack = function(t, idx) {
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

    console.log('[Bridge] Android 초기화 완료 | origin=' + location.origin);
  });

})();
