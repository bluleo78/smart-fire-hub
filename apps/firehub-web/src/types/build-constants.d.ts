/**
 * 빌드 상수 — vite.config.ts·vitest.config.ts 의 define 이 값을 넣는다.
 * __E2E__: Playwright E2E 용 서버(dev·preview)로 띄운 빌드면 true. 운영 빌드에서는 false 라
 * `if (__E2E__)` 블록이 번들에서 빠진다(WD-257).
 */
declare const __E2E__: boolean;
