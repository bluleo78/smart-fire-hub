/*
 * 런타임 브랜딩 설정 — "Smart Data Platform" (사이트별 화이트라벨).
 *
 * 이 파일은 web 이미지의 기본 /config.js(= dist/config.js, 기본값 "Smart Fire Hub")를
 * 배포 시점에 "교체(마운트)"하기 위한 사이트별 override 다.
 *
 * - K8S: ConfigMap(web-branding-configmap.yaml)으로 이 내용을 담아
 *   web 컨테이너의 /usr/share/nginx/html/config.js 에 subPath 마운트한다. (재빌드 불필요)
 * - web 이미지의 기본 nginx.conf 에는 이미 `location = /config.js { add_header Cache-Control "no-store"; }`
 *   가 구워져 있어, ConfigMap 갱신 후 새로고침만으로 브랜드가 즉시 반영된다.
 * - index.html <head>에서 React 번들보다 "먼저 동기 실행"되므로 벤더 브랜드 깜빡임(flash)이 없다.
 * - React 측은 window.__APP_CONFIG__ 를 읽어 사이드바/로그인 로고·이름을 렌더링한다.
 */
(function () {
  var config = {
    brandName: 'Smart Data Platform', // 앱 이름 — 탭 타이틀 · 사이드바 · 로그인 화면
    // 로고 이미지 URL. null이면 기본 아이콘(Flame) 사용.
    // 자체 로고 사용 시: 동일 오리진으로 서빙되는 경로를 지정한다.
    //   예) '/branding/sdp-logo.svg' (nginx web root에 별도 마운트) 또는 오브젝트 스토리지 공개 URL.
    logoUrl: null,
    // 브라우저 탭 파비콘 URL. 기본 이미지 대신 자체 파비콘 지정 가능(SVG 권장).
    faviconUrl: '/vite.svg',
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
