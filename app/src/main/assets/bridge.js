/**
 * XWare Android Bridge v3.2
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

  /* ════ 핵심: Page Visibility 완전 차단 ═══════════
     YouTube IFrame은 visibilityState를 감지해 백그라운드에서
     자동으로 영상을 일시정지함.
     메인 document + 모든 iframe 에 동시에 적용해야 함.
  ═══════════════════════════════════════════════ */
  function overrideVisibility(doc) {
    try {
      Object.defineProperty(doc, 'hidden', {
        get: function() { return false; },
        configurable: true
      });
      Object.defineProperty(doc, 'visibilityState', {
        get: function() { return 'visible'; },
        configurable: true
      });
      // visibilitychange 이벤트 리스너 등록 자체를 막음
      var _orig = doc.addEventListener.bind(doc);
      doc.addEventListener = function(type, fn, opts) {
        if (type === 'visibilitychange') return;
        return _orig(type, fn, opts);
      };
    } catch(e) {}
  }

  // 메인 document에 즉시 적용
  overrideVisibility(document);

  // ★ YouTube IFrame이 나중에 추가될 때도 적용
  // MutationObserver로 iframe 감지 → 로드 후 override 주입
  var iframeObserver = new MutationObserver(function(mutations) {
    mutations.forEach(function(m) {
      m.addedNodes.forEach(function(node) {
        if (node.tagName === 'IFRAME') {
          node.addEventListener('load', function() {
            try {
              overrideVisibility(node.contentDocument);
            } catch(e) {}
          });
        }
      });
    });
  });
  iframeObserver.observe(document.body || document.documentElement,
    { childList: true, subtree: true });

  // ★ pagehide / freeze 이벤트도 차단 (Samsung One UI 추가 절전)
  window.addEventListener('pagehide', function(e) {
    e.stopImmediatePropagation();
  }, true);
  window.addEventListener('freeze', function(e) {
    e.stopImmediatePropagation();
  }, true);
  document.addEventListener('pause', function(e) {
    e.stopImmediatePropagation();
  }, true);

  /* ════ app.js 로드 후 초기화 ════════════════════ */
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

    console.log('[Bridge] 초기화 완료 | origin=' + location.origin);
  });

})();
