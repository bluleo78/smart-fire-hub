/*
 * 런타임 브랜딩 설정 (사이트별 화이트라벨).
 *
 * - 이 파일은 빌드 산출물(dist)에 "기본값"으로 포함된다.
 * - 배포 시 이 파일만 사이트별 파일로 교체(볼륨 마운트)하면 재빌드 없이
 *   브랜드명 · 로고 · 파비콘을 바꿀 수 있다. (nginx web root: /usr/share/nginx/html/config.js)
 * - index.html <head>에서 React 번들보다 "먼저 동기 실행"되므로, 벤더 브랜드가
 *   잠깐 보였다 바뀌는 깜빡임(flash)이 발생하지 않는다.
 * - React 측은 window.__APP_CONFIG__ 를 읽어 사이드바/로그인 로고·이름을 렌더링한다.
 */
(function () {
  var config = {
    brandName: 'Smart Fire Hub', // 앱 이름 — 탭 타이틀 · 사이드바 · 로그인 화면
    logoUrl: null, // 로고 이미지 URL. null이면 기본 아이콘(Flame) 사용
    faviconUrl: '/vite.svg', // 브라우저 탭 파비콘 URL
  };

  window.__APP_CONFIG__ = config;

  // 탭 타이틀 즉시 반영 (React 로드 전)
  if (config.brandName) {
    document.title = config.brandName;
  }

  // 파비콘 즉시 반영 — 기존 link[rel=icon]가 있으면 교체, 없으면 생성
  if (config.faviconUrl) {
    var link = document.querySelector("link[rel='icon']");
    if (!link) {
      link = document.createElement('link');
      link.rel = 'icon';
      document.head.appendChild(link);
    }
    link.href = config.faviconUrl;
  }
})();
