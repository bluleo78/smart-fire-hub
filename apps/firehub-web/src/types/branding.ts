/**
 * 사이트별 화이트라벨 브랜딩 설정.
 * 런타임에 /config.js가 window.__APP_CONFIG__ 로 주입한다(빌드 불필요).
 */
export interface BrandingConfig {
  /** 앱 이름 — 탭 타이틀 · 사이드바 · 로그인 화면에 표시 */
  brandName: string;
  /** 로고 이미지 URL. null이면 기본 아이콘(Flame)을 사용 */
  logoUrl: string | null;
  /** 브라우저 탭 파비콘 URL */
  faviconUrl: string;
}

declare global {
  interface Window {
    /** /config.js가 주입하는 런타임 브랜딩 값. 일부 필드만 있을 수 있어 Partial. */
    __APP_CONFIG__?: Partial<BrandingConfig>;
  }
}
