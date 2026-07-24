import type { BrandingConfig } from '@/types/branding';

/**
 * config.js 로드 실패 등으로 런타임 설정이 없을 때 사용하는 기본 브랜딩(현행 Smart Fire Hub).
 * config.js의 기본값과 동일하게 유지한다.
 */
export const DEFAULT_BRANDING: BrandingConfig = {
  brandName: 'Smart Fire Hub',
  logoUrl: null,
  faviconUrl: '/vite.svg',
};

/**
 * 런타임 브랜딩 설정을 반환한다.
 * <head>의 /config.js가 window.__APP_CONFIG__에 심어둔 값을 기본값 위에 병합한다.
 * 동기 함수이므로 첫 렌더에서 바로 올바른 브랜드가 그려진다(깜빡임 없음).
 */
export function getBranding(): BrandingConfig {
  const injected = typeof window !== 'undefined' ? window.__APP_CONFIG__ : undefined;
  return { ...DEFAULT_BRANDING, ...(injected ?? {}) };
}
