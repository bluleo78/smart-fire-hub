/**
 * 디자인 토큰 색 대비 회귀 테스트 (#365 / #367 / #370)
 *
 * 무엇: `src/index.css`에 정의된 시맨틱 색 토큰을 직접 파싱해
 *       OKLCh → linear sRGB → sRGB 변환 후 WCAG 2.x 상대휘도로 대비를 계산하고,
 *       라이트/다크 × 3개 테마(indigo 기본 / ocean / sunset) 전 조합에서
 *       AA 기준(본문 4.5:1, UI 컴포넌트 3:1)을 만족하는지 검증한다.
 * 왜:   토큰 값은 CSS에 정적으로 존재하므로 브라우저(E2E)가 필요 없고,
 *       누가 명도를 되돌리거나 `-foreground` 짝을 깨면 즉시 red가 되어야 한다.
 *       (디자이너 확정 스펙: "E2E보다 단위 테스트가 적합하다")
 */
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

const CSS_PATH = resolve(dirname(fileURLToPath(import.meta.url)), '../index.css');

// ---------------------------------------------------------------------------
// 색 변환 엔진 — OKLCh → sRGB → WCAG 상대휘도
// ---------------------------------------------------------------------------

type Rgba = { r: number; g: number; b: number; a: number };

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

/** `oklch(L C H)` / `oklch(L C H / A%)` 문자열을 파싱한다. */
function parseOklch(value: string): Rgba {
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
function composite(fg: Rgba, bg: Rgba): Rgba {
  const mix = (f: number, b: number) => Math.round(f * fg.a + b * (1 - fg.a));
  return { r: mix(fg.r, bg.r), g: mix(fg.g, bg.g), b: mix(fg.b, bg.b), a: 1 };
}

/** WCAG 2.x 상대휘도. */
function luminance({ r, g, b }: Rgba): number {
  const lin = (v: number) => {
    const c = v / 255;
    return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
}

/** 두 불투명 색의 WCAG 대비비. 알파가 있으면 호출 전에 합성해야 한다. */
function contrast(a: Rgba, b: Rgba): number {
  const l1 = luminance(a);
  const l2 = luminance(b);
  const [hi, lo] = l1 > l2 ? [l1, l2] : [l2, l1];
  return (hi + 0.05) / (lo + 0.05);
}

const hex = ({ r, g, b }: Rgba) =>
  '#' + [r, g, b].map((v) => v.toString(16).padStart(2, '0')).join('');

/** 알파 수식자(`bg-X/10`)를 흉내 내어 틴트 색을 만든다. */
const withAlpha = (c: Rgba, alpha: number): Rgba => ({ ...c, a: c.a * alpha });

// ---------------------------------------------------------------------------
// index.css 토큰 파싱
// ---------------------------------------------------------------------------

const css = readFileSync(CSS_PATH, 'utf8');

/** 셀렉터 블록 하나에서 `--토큰: 값;` 쌍을 모두 뽑는다. */
function readBlock(selector: string): Record<string, string> {
  const idx = css.indexOf(selector + ' {');
  if (idx < 0) throw new Error(`셀렉터를 찾지 못했다: ${selector}`);
  const start = css.indexOf('{', idx) + 1;
  const end = css.indexOf('}', start);
  const out: Record<string, string> = {};
  for (const line of css.slice(start, end).split('\n')) {
    const m = line.match(/^\s*(--[\w-]+)\s*:\s*([^;]+);/);
    if (m) out[m[1]] = m[2].trim();
  }
  return out;
}

const LIGHT = readBlock(':root');
const DARK_OVERRIDES = readBlock('.dark');
const OCEAN_LIGHT = readBlock('.theme-ocean');
const OCEAN_DARK = readBlock('.theme-ocean.dark, .dark .theme-ocean');
const SUNSET_LIGHT = readBlock('.theme-sunset');
const SUNSET_DARK = readBlock('.theme-sunset.dark, .dark .theme-sunset');

/** CSS 캐스케이드를 흉내 내어 6개 테마 조합의 토큰 맵을 만든다. */
const THEMES: Record<string, Record<string, string>> = {
  'indigo-light': { ...LIGHT },
  'indigo-dark': { ...LIGHT, ...DARK_OVERRIDES },
  'ocean-light': { ...LIGHT, ...OCEAN_LIGHT },
  'ocean-dark': { ...LIGHT, ...DARK_OVERRIDES, ...OCEAN_LIGHT, ...OCEAN_DARK },
  'sunset-light': { ...LIGHT, ...SUNSET_LIGHT },
  'sunset-dark': { ...LIGHT, ...DARK_OVERRIDES, ...SUNSET_LIGHT, ...SUNSET_DARK },
};

const DARK_THEMES = ['indigo-dark', 'ocean-dark', 'sunset-dark'] as const;
const LIGHT_THEMES = ['indigo-light', 'ocean-light', 'sunset-light'] as const;
const ALL_THEMES = Object.keys(THEMES);

/** 토큰을 Rgba로 읽는다. */
function token(theme: string, name: string): Rgba {
  const raw = THEMES[theme][name];
  if (raw === undefined) throw new Error(`${theme}에 ${name} 토큰이 없다`);
  return parseOklch(raw);
}

/** 카드 표면 실색 — 다크의 `--card`는 반투명이므로 `--background` 위에 합성한다. */
function cardSurface(theme: string): Rgba {
  return composite(token(theme, '--card'), token(theme, '--background'));
}

// ---------------------------------------------------------------------------
// 0. 엔진 검증 — 디자이너가 게시한 hex/대비값을 재현하는지 확인한다.
//    (엔진이 틀리면 아래 모든 단언이 무의미해지므로 먼저 고정한다)
// ---------------------------------------------------------------------------

describe('색 변환 엔진', () => {
  it('알려진 OKLCh 값을 디자이너 게시 hex로 재현한다', () => {
    expect(hex(parseOklch('oklch(0.52 0.13 84)'))).toBe('#8c6000'); // --warning (라이트)
    expect(hex(parseOklch('oklch(0.46 0.16 149.5)'))).toBe('#006e1c'); // --success (라이트)
    expect(hex(parseOklch('oklch(0.53 0.22 27.325)'))).toBe('#cd000f'); // --destructive (라이트)
    expect(hex(parseOklch('oklch(0.75 0.12 195)'))).toBe('#2cc5c5'); // ocean 다크 --primary
    expect(hex(parseOklch('oklch(0.72 0.15 50)'))).toBe('#ee8545'); // sunset 다크 --primary
    expect(hex(parseOklch('oklch(0.17 0.02 280)'))).toBe('#0e0e18'); // 다크 -foreground
  });

  it('WCAG 대비 계산이 디자이너 수치와 일치한다(±0.05)', () => {
    const white = parseOklch('oklch(1 0 0)');
    expect(contrast(parseOklch('oklch(0.52 0.13 84)'), white)).toBeCloseTo(5.54, 1);
    expect(contrast(parseOklch('oklch(0.17 0.02 280)'), parseOklch('oklch(0.75 0.12 195)'))).toBeCloseTo(9.04, 1);
  });
});

// ---------------------------------------------------------------------------
// 1. #365 — primary 짝: 6개 테마 조합 전부 4.5:1
// ---------------------------------------------------------------------------

describe('#365 primary 대비', () => {
  it.each(ALL_THEMES)('%s: bg-primary 위 text-primary-foreground ≥ 4.5', (theme) => {
    const ratio = contrast(token(theme, '--primary-foreground'), token(theme, '--primary'));
    expect(ratio, `${theme} primary=${hex(token(theme, '--primary'))}`).toBeGreaterThanOrEqual(4.5);
  });

  it.each(ALL_THEMES)('%s: sidebar-primary 짝 ≥ 4.5', (theme) => {
    const ratio = contrast(
      token(theme, '--sidebar-primary-foreground'),
      token(theme, '--sidebar-primary')
    );
    expect(ratio).toBeGreaterThanOrEqual(4.5);
  });

  it.each(ALL_THEMES)('%s: text-primary가 카드 표면 위에서 ≥ 4.5', (theme) => {
    const ratio = contrast(token(theme, '--primary'), cardSurface(theme));
    expect(ratio).toBeGreaterThanOrEqual(4.5);
  });
});

// ---------------------------------------------------------------------------
// 2. #367 / #370 — 시맨틱 상태 토큰
//    역할 계약: `--X`는 배경 겸 전경이므로 카드 위 4.5:1을 만족해야 하고,
//    `--X-foreground`는 `--X` 배경 위에서 4.5:1을 만족해야 한다.
// ---------------------------------------------------------------------------

const SEMANTIC = ['success', 'warning', 'destructive', 'info', 'caution'] as const;

describe('#367/#370 시맨틱 토큰 대비', () => {
  it('destructive 짝 토큰이 :root와 .dark 양쪽에 정의돼 있다', () => {
    // #367 근본 원인: `--destructive-foreground`가 아예 없어 text 유틸이 무효값이 됐다.
    expect(LIGHT['--destructive-foreground']).toBeDefined();
    expect(DARK_OVERRIDES['--destructive-foreground']).toBeDefined();
    expect(css).toContain('--color-destructive-foreground: var(--destructive-foreground);');
  });

  for (const name of SEMANTIC) {
    describe(`--${name}`, () => {
      it.each(ALL_THEMES)('%s: text-X가 카드 표면 위에서 ≥ 4.5', (theme) => {
        const fg = token(theme, `--${name}`);
        const ratio = contrast(fg, cardSurface(theme));
        expect(ratio, `${theme} --${name}=${hex(fg)}`).toBeGreaterThanOrEqual(4.5);
      });

      it.each(ALL_THEMES)('%s: bg-X 도트/바가 카드 위 ≥ 3 (SC 1.4.11)', (theme) => {
        const ratio = contrast(token(theme, `--${name}`), cardSurface(theme));
        expect(ratio).toBeGreaterThanOrEqual(3);
      });

      it.each(ALL_THEMES)('%s: X-foreground가 bg-X 위에서 ≥ 4.5', (theme) => {
        const ratio = contrast(token(theme, `--${name}-foreground`), token(theme, `--${name}`));
        expect(ratio).toBeGreaterThanOrEqual(4.5);
      });

      it.each(ALL_THEMES)('%s: text-X가 bg-X/10 틴트 위에서 ≥ 4.5', (theme) => {
        const surface = cardSurface(theme);
        const tint = composite(withAlpha(token(theme, `--${name}`), 0.1), surface);
        const ratio = contrast(token(theme, `--${name}`), tint);
        expect(ratio).toBeGreaterThanOrEqual(4.5);
      });
    });
  }

  // `-subtle`은 배경 전용 토큰이며 라이트/다크 모두 같은 색 위에 X 전경이 얹힌다.
  const SUBTLE = ['success', 'warning', 'info', 'caution'] as const;
  for (const name of SUBTLE) {
    it.each(ALL_THEMES)(`%s: text-${name}가 bg-${name}-subtle 위에서 ≥ 4.5`, (theme) => {
      const ratio = contrast(
        token(theme, `--${name}`),
        composite(token(theme, `--${name}-subtle`), cardSurface(theme))
      );
      expect(ratio).toBeGreaterThanOrEqual(4.5);
    });
  }
});

// ---------------------------------------------------------------------------
// 3. 나머지 액센트 계열 `-foreground` 짝 (역할 계약 준수 확인)
// ---------------------------------------------------------------------------

describe('액센트 -foreground 짝', () => {
  const ACCENTS = ['pipeline', 'dataset', 'dashboard-accent', 'ai-accent'] as const;
  it.each(
    ALL_THEMES.flatMap((t) => ACCENTS.map((a) => [t, a] as const))
  )('%s: --%s 짝 ≥ 4.5', (theme, name) => {
    const ratio = contrast(token(theme, `--${name}-foreground`), token(theme, `--${name}`));
    expect(ratio).toBeGreaterThanOrEqual(4.5);
  });
});

// ---------------------------------------------------------------------------
// 4. #368 — 포커스 링 원색이 3:1을 만족한다 (알파 없이 쓰는 근거)
// ---------------------------------------------------------------------------

describe('#368 포커스 링', () => {
  it.each(ALL_THEMES)('%s: --ring이 페이지/카드 배경 위에서 ≥ 3', (theme) => {
    const ring = token(theme, '--ring');
    expect(contrast(ring, token(theme, '--background'))).toBeGreaterThanOrEqual(3);
    expect(contrast(ring, cardSurface(theme))).toBeGreaterThanOrEqual(3);
  });

  it('전역 outline과 shadcn 포커스 링에 알파 수식자가 없다', () => {
    expect(css).toContain('@apply border-border outline-ring;');
    expect(css).not.toContain('outline-ring/');
  });
});

// ---------------------------------------------------------------------------
// 5. #366 — 보조 텍스트는 알파 없이도 4.5:1 (토큰 자체는 변경 대상이 아님)
// ---------------------------------------------------------------------------

describe('#366 muted-foreground', () => {
  it.each([...LIGHT_THEMES, ...DARK_THEMES])('%s: 카드/배경 위 ≥ 4.5', (theme) => {
    const fg = token(theme, '--muted-foreground');
    expect(contrast(fg, token(theme, '--background'))).toBeGreaterThanOrEqual(4.5);
    expect(contrast(fg, cardSurface(theme))).toBeGreaterThanOrEqual(4.5);
    // `bg-muted`는 페이지 배경 위에 놓이는 표면이다. 카드 안에 다시 muted를 겹치는
    // 중첩 조합(다크 4.39:1)은 디자이너가 이연한 경계값 항목이라 여기서 단언하지 않는다
    // (→ docs/design-system/13-migration-backlog.md).
    expect(
      contrast(fg, composite(token(theme, '--muted'), token(theme, '--background')))
    ).toBeGreaterThanOrEqual(4.5);
  });
});
