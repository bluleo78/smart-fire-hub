/**
 * 테마 토큰 색 계산 공용 헬퍼 — 테스트 전용 (WD-1070).
 *
 * 무엇: `src/index.css` 의 시맨틱 토큰을 파싱해 6개 테마 조합(indigo/ocean/sunset × 라이트/다크)의
 *       토큰 맵과 OKLCh → sRGB 변환·합성·WCAG 대비 계산을 제공한다.
 * 왜:   design-tokens.contrast.test.ts 와 ontology-colors.contrast.test.ts 가 같은 엔진을 써야
 *       "실제 --background" 기준 대비가 갈라지지 않는다. `*.test.ts` 가 아니라서 vitest 가 직접 실행하지 않고
 *       (include: src/**\/*.test.{ts,tsx}), 프로덕션 번들은 이 파일을 import 하지 않는다.
 */
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const CSS_PATH = resolve(dirname(fileURLToPath(import.meta.url)), '../index.css');

// ---------------------------------------------------------------------------
// 색 변환 엔진 — OKLCh → sRGB → WCAG 상대휘도
// ---------------------------------------------------------------------------

export type Rgba = { r: number; g: number; b: number; a: number };

/** OKLCh 성분을 감마 인코딩된 8비트 sRGB로 변환한다. */
function oklchToRgb(L: number, C: number, hDeg: number): { r: number; g: number; b: number } {
  const h = (hDeg * Math.PI) / 180;
  const a = C * Math.cos(h);
  const bb = C * Math.sin(h);

  // OKLab → LMS(입방근 공간) → LMS
  const l_ = L + 0.3963377774 * a + 0.2158037573 * bb;
  const m_ = L - 0.1055613458 * a - 0.0638541728 * bb;
  const s_ = L - 0.0894841775 * a - 1.291485548 * bb;
  const l = l_ ** 3;
  const m = m_ ** 3;
  const s = s_ ** 3;

  // LMS → linear sRGB
  const lr = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s;
  const lg = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s;
  const lb = -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s;

  // 감마 인코딩 + 8비트 양자화 (브라우저 canvas 실측과 동일한 표현으로 맞춘다)
  const enc = (v: number) => {
    const c = v <= 0.0031308 ? 12.92 * v : 1.055 * v ** (1 / 2.4) - 0.055;
    return Math.max(0, Math.min(255, Math.round(c * 255)));
  };
  return { r: enc(lr), g: enc(lg), b: enc(lb) };
}

/** `#rrggbb` 를 불투명 Rgba 로 바꾼다. */
export const hexToRgb = (value: string): Rgba => {
  const h = value.replace('#', '');
  const [r, g, b] = [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16));
  return { r, g, b, a: 1 };
};

/** `oklch(L C H)` / `oklch(L C H / A%)` 문자열을 파싱한다. */
export function parseOklch(value: string): Rgba {
  const m = value
    .trim()
    .match(/^oklch\(\s*([\d.]+)\s+([\d.]+)\s+([\d.]+)\s*(?:\/\s*([\d.]+)(%?)\s*)?\)$/);
  if (!m) throw new Error(`oklch가 아닌 값: ${value}`);
  const [, L, C, H, A, pct] = m;
  const alpha = A === undefined ? 1 : pct === '%' ? Number(A) / 100 : Number(A);
  const { r, g, b } = oklchToRgb(Number(L), Number(C), Number(H));
  return { r, g, b, a: alpha };
}

/** 전경(알파 포함)을 불투명 배경 위에 합성한다. */
export function composite(fg: Rgba, bg: Rgba): Rgba {
  const mix = (f: number, b: number) => Math.round(f * fg.a + b * (1 - fg.a));
  return { r: mix(fg.r, bg.r), g: mix(fg.g, bg.g), b: mix(fg.b, bg.b), a: 1 };
}

/** WCAG 2.x 상대휘도. */
export function luminance({ r, g, b }: Rgba): number {
  const lin = (v: number) => {
    const c = v / 255;
    return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
}

/** 두 불투명 색의 WCAG 대비비. 알파가 있으면 호출 전에 합성해야 한다. */
export function contrast(a: Rgba, b: Rgba): number {
  const l1 = luminance(a);
  const l2 = luminance(b);
  const [hi, lo] = l1 > l2 ? [l1, l2] : [l2, l1];
  return (hi + 0.05) / (lo + 0.05);
}

export const hex = ({ r, g, b }: Rgba) =>
  '#' + [r, g, b].map((v) => v.toString(16).padStart(2, '0')).join('');

/** 알파 수식자(`bg-X/10`)를 흉내 내어 틴트 색을 만든다. */
export const withAlpha = (c: Rgba, alpha: number): Rgba => ({ ...c, a: c.a * alpha });

// ---------------------------------------------------------------------------
// index.css 토큰 파싱
// ---------------------------------------------------------------------------

export const css = readFileSync(CSS_PATH, 'utf8');

/** 셀렉터 블록 하나의 본문(`{` 와 `}` 사이)을 돌려준다 — 토큰 블록과 그라디언트 블록이 같이 쓴다. */
export function blockBody(selector: string): string {
  const idx = css.indexOf(selector + ' {');
  if (idx < 0) throw new Error(`셀렉터를 찾지 못했다: ${selector}`);
  const start = css.indexOf('{', idx) + 1;
  return css.slice(start, css.indexOf('}', start));
}

/** 셀렉터 블록 하나에서 `--토큰: 값;` 쌍을 모두 뽑는다. */
function readBlock(selector: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const line of blockBody(selector).split('\n')) {
    const m = line.match(/^\s*(--[\w-]+)\s*:\s*([^;]+);/);
    if (m) out[m[1]] = m[2].trim();
  }
  return out;
}

export const LIGHT = readBlock(':root');
export const DARK_OVERRIDES = readBlock('.dark');
export const OCEAN_LIGHT = readBlock('.theme-ocean');
export const OCEAN_DARK = readBlock('.theme-ocean.dark, .dark .theme-ocean');
export const SUNSET_LIGHT = readBlock('.theme-sunset');
export const SUNSET_DARK = readBlock('.theme-sunset.dark, .dark .theme-sunset');

/** CSS 캐스케이드를 흉내 내어 6개 테마 조합의 토큰 맵을 만든다. */
export const THEMES: Record<string, Record<string, string>> = {
  'indigo-light': { ...LIGHT },
  'indigo-dark': { ...LIGHT, ...DARK_OVERRIDES },
  'ocean-light': { ...LIGHT, ...OCEAN_LIGHT },
  'ocean-dark': { ...LIGHT, ...DARK_OVERRIDES, ...OCEAN_LIGHT, ...OCEAN_DARK },
  'sunset-light': { ...LIGHT, ...SUNSET_LIGHT },
  'sunset-dark': { ...LIGHT, ...DARK_OVERRIDES, ...SUNSET_LIGHT, ...SUNSET_DARK },
};

export const DARK_THEMES = ['indigo-dark', 'ocean-dark', 'sunset-dark'] as const;
export const LIGHT_THEMES = ['indigo-light', 'ocean-light', 'sunset-light'] as const;
export const ALL_THEMES = Object.keys(THEMES);

/** 토큰을 Rgba로 읽는다. */
export function token(theme: string, name: string): Rgba {
  const raw = THEMES[theme][name];
  if (raw === undefined) throw new Error(`${theme}에 ${name} 토큰이 없다`);
  return parseOklch(raw);
}

/** 카드 표면 실색 — 다크의 `--card`는 반투명이므로 `--background` 위에 합성한다. */
export function cardSurface(theme: string): Rgba {
  return composite(token(theme, '--card'), token(theme, '--background'));
}

