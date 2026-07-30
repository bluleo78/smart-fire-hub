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
 * 빈 문자열·공백만 있는 값은 "미설정"으로 취급한다(#325).
 * config.js는 배포 시 사이트별 파일로 교체되므로 값 누락이 현실적인 실패 형태다 —
 * 스프레드 병합은 키가 존재하기만 하면 ''·null도 기본값을 밀어내므로 여기서 걸러낸다.
 */
function nonEmpty(value: string | null | undefined): string | null {
  return typeof value === 'string' && value.trim() !== '' ? value : null;
}

/**
 * 런타임 브랜딩 설정을 반환한다.
 * <head>의 /config.js가 window.__APP_CONFIG__에 심어둔 값을 기본값 위에 병합한다.
 * 동기 함수이므로 첫 렌더에서 바로 올바른 브랜드가 그려진다(깜빡임 없음).
 *
 * 빈값은 기본값으로 되돌린다 — config.js의 `if (config.brandName)` 가드와 의미를 맞춰,
 * "앱에는 브랜드가 없는데 탭 타이틀만 벤더명"인 어긋난 상태(split-brand)가 생기지 않게 한다.
 */
export function getBranding(): BrandingConfig {
  const injected = typeof window !== 'undefined' ? window.__APP_CONFIG__ : undefined;
  return {
    brandName: nonEmpty(injected?.brandName) ?? DEFAULT_BRANDING.brandName,
    logoUrl: nonEmpty(injected?.logoUrl) ?? DEFAULT_BRANDING.logoUrl,
    faviconUrl: nonEmpty(injected?.faviconUrl) ?? DEFAULT_BRANDING.faviconUrl,
  };
}
