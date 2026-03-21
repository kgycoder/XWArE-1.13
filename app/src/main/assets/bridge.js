/**
 * XWare Android Bridge v3.0
 * ─────────────────────────────────────────────────────────────
 * 1. Chrome webview 폴리필
 * 2. 비트 이펙트 비활성화 (깜빡임 방지)
 * 3. 오버레이 모드 Android 전용 처리
 * 4. 재생상태/트랙 정보 → 버블 동기화
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
    if (np && np.classList.contains('on')) { if (typeof closeNP==='function') { closeNP(); return true; } }
    var home = document.getElementById('v-home');
    if (home && !home.classList.contains('on')) { if (typeof gv==='function') { gv('home', document.querySelector('[data-v="home"]')); return true; } }
    return false;
  };

  /* ════ app.js 로드 후 초기화 ════════════════════ */
  window.addEventListener('load', function () {

    /* ── ① 비트 이펙트 비활성화 (깜빡임 방지) ────── */
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

    /* ── ② 오버레이 모드: Android 전용 처리 ─────── */
    // 원본 toggleOverlay 는 Windows 오버레이 바를 표시함
    // Android 에서는 그 동작 대신 버블 서비스만 켜고 끔
    var _androidOverlayOn = false;
    window.toggleOverlay = function() {
      _androidOverlayOn = !_androidOverlayOn;
      // 버튼 on/off 시각 표시
      document.getElementById('bt-overlay-btn')?.classList.toggle('on', _androidOverlayOn);
      document.getElementById('np-overlay-btn')?.classList.toggle('on', _androidOverlayOn);
      // Kotlin 에 오버레이 모드 전달 → LyricsOverlayService (버블) 시작/종료
      try {
        window.chrome.webview.postMessage(JSON.stringify({
          type: 'overlayMode', active: _androidOverlayOn
        }));
      } catch(e) {}
      if (typeof toast === 'function')
        toast(_androidOverlayOn ? '🫧 오버레이 켜짐' : '오버레이 꺼짐');
    };

    /* ── ③ 재생상태 → 버블 동기화 ─────────────── */
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

    /* ── ④ 트랙 변경 → 버블 패널 동기화 ─────────── */
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
