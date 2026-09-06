// 엔티티 타입 색상 — 스키마·인스턴스·범례가 공유하는 단일 소스.
//
// (#396) 예전에는 6개 데모 타입명(Incident/Building/...)을 리터럴 키로 가진 Record 룩업이었다.
// 사용자가 만든 임의의 온톨로지는 그 이름들과 일치하지 않으므로 타입 대부분이 폴백 회색 하나로
// 수렴해, 캔버스·타입 필터·인스펙터에서 타입 구분이 사실상 사라졌다. 이름 키 대신 **순서 있는
// 팔레트 배열 + 인덱스 순환**으로 바꿔 어떤 이름 체계의 온톨로지에서도 색이 갈리게 한다.

/** 팔레트 한 칸 — base(500)와 테마별 텍스트/윤곽선 shade(라이트 700 / 다크 300)를 묶어 둔다. */
export interface PaletteEntry {
  base: string; // 500 — 범례 점·인스턴스 노드 배경
  textLight: string; // 700 — 라이트 모드 텍스트/윤곽선
  textDark: string; // 300 — 다크 모드 텍스트/윤곽선
}

/**
 * 타입 색 팔레트(12색). 앞 6개는 이전 하드코딩 색과 같은 순서·같은 값으로 두어, 데모 온톨로지
 * (Building/Cause/Damage/Equipment/Incident/Regulation)의 인상이 급격히 바뀌지 않게 한다.
 * 뒤 6개는 앞 6개와 색상환에서 최대한 떨어진 tailwind 계열을 골랐다.
 * 각 엔트리의 textLight/textDark는 #377에서 확보한 대비 계약(윤곽선 ≥3:1, 라벨 ≥4.5:1)을
 * 만족해야 하며, ontology-colors.contrast.test.ts가 12칸 전부를 전수 단언한다.
 */
export const PALETTE: readonly PaletteEntry[] = [
  { base: '#ef4444', textLight: '#b91c1c', textDark: '#fca5a5' }, // red
  { base: '#3b82f6', textLight: '#1d4ed8', textDark: '#93c5fd' }, // blue
  { base: '#f59e0b', textLight: '#b45309', textDark: '#fcd34d' }, // amber
  { base: '#ec4899', textLight: '#be185d', textDark: '#f9a8d4' }, // pink
  { base: '#10b981', textLight: '#047857', textDark: '#6ee7b7' }, // emerald
  { base: '#8b5cf6', textLight: '#6d28d9', textDark: '#c4b5fd' }, // violet
  { base: '#06b6d4', textLight: '#0e7490', textDark: '#67e8f9' }, // cyan
  { base: '#f97316', textLight: '#c2410c', textDark: '#fdba74' }, // orange
  { base: '#84cc16', textLight: '#4d7c0f', textDark: '#bef264' }, // lime
  { base: '#d946ef', textLight: '#a21caf', textDark: '#f0abfc' }, // fuchsia
  { base: '#14b8a6', textLight: '#0f766e', textDark: '#5eead4' }, // teal
  { base: '#6366f1', textLight: '#4338ca', textDark: '#a5b4fc' }, // indigo
];

// 팔레트가 모르는 타입(목록에 없는 이름) 방어용 기본색(회색 500/700/300).
export const DEFAULT_TYPE_COLOR = '#64748b';
const DEFAULT_TEXT_LIGHT = '#334155';
const DEFAULT_TEXT_DARK = '#cbd5e1';

// #rrggbb → rgba(r,g,b,alpha) 변환. tint 배경 계산에 사용.
const hexToRgba = (hex: string, alpha: number): string => {
  const h = hex.replace('#', '');
  const r = parseInt(h.slice(0, 2), 16);
  const g = parseInt(h.slice(2, 4), 16);
  const b = parseInt(h.slice(4, 6), 16);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
};

// 커스텀 노드가 사용하는 테마별 색상 묶음.
export interface EntityColorSet {
  base: string; // 타입색 500 — border·범례 점에 사용
  text: string; // 대비 통과 텍스트 색(테마별 700/300 shade)
  tint: string; // 옅은 배경 tint(테마별 alpha)
}

/**
 * 한 온톨로지의 타입 목록에 색을 고정한 팔레트 인스턴스.
 * 소비처(캔버스·타입 필터·인스펙터·스타일시트)는 이 객체 하나를 prop/인자로 받아 쓴다 —
 * 컴포넌트마다 타입 목록을 다시 계산해 각자 팔레트를 만들면 같은 타입이 화면마다 다른 색이 된다.
 */
export interface TypePalette {
  /** 타입의 base(500) 색 — 범례 점, 인스턴스 노드 배경. */
  color(type: string): string;
  /** 타입 노드의 윤곽선 색(테마별 700/300 shade) — #377. */
  contour(type: string, isDark: boolean): string;
  /** 타입 + 테마로 노드 색상 묶음(base/text/tint)을 계산. */
  colorSet(type: string, isDark: boolean): EntityColorSet;
}

// 타입 이름 문자열을 32bit 정수 해시로 변환한다(FNV-1a).
// 왜: 팔레트 인덱스를 "정렬 순서"가 아니라 "타입 이름 자체"에서만 뽑아내야, 다른 타입이
//   추가/삭제돼도 이 타입의 색이 흔들리지 않는다(#493). djb2류(곱셈 33 + XOR)는 데모 6종
//   (Building/Cause/Damage/Equipment/Incident/Regulation)만으로도 mod 12 결과가 3개나
//   겹치는 등 실사용 이름 길이대에서 눈에 띄게 몰린다 — FNV-1a는 바이트마다 XOR 후 소수
//   곱셈(0x01000193)을 적용해 아바란치가 훨씬 고르며, 짧은 영문 식별자 해싱의 표준 선택지다.
//   `>>> 0`으로 부호 없는 32bit 정수로 고정해 모듈로 연산이 항상 음수 없이 나온다.
function hashTypeName(type: string): number {
  let hash = 0x811c9dc5; // FNV offset basis
  for (let i = 0; i < type.length; i++) {
    hash ^= type.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193); // FNV prime
  }
  return hash >>> 0;
}

/**
 * 타입 목록에서 결정적 색 배정 팔레트를 만든다 (#396, #493).
 *
 * 무엇: 타입 이름 자체를 해시해 `PALETTE[hash(type) % PALETTE.length]`를 배정한다.
 * 왜 해시인가: 이전(#396) 구현은 "정렬 순서 인덱스"를 색의 근거로 삼았는데, 이는 타입 자신의
 *   정체성과 무관한 값이라 타입 하나를 추가/삭제/리네임하면 정렬 위치가 바뀌는 다른 모든 타입의
 *   색까지 함께 바뀌었다(#493). 해시는 타입 이름 하나만으로 정해지므로 타입 집합의 다른 원소가
 *   바뀌어도 이 타입의 색은 그대로다 — "같은 타입 집합이면 같은 색"(#396)에 더해 "집합이 바뀌어도
 *   기존 타입 색은 안정적"이라는 불변식을 추가로 만족한다(해시 충돌로 서로 다른 두 타입이 같은
 *   색을 받을 수는 있음 — #444와 공유하는 절충).
 * 목록에 없는 타입은 회색 폴백으로 떨어진다(예: 스키마에 없는 타입이 섞인 인스턴스 그래프 노드).
 */
export function createTypePalette(types: readonly string[]): TypePalette {
  // 중복 제거만 하고 정렬하지 않는다 — 색이 이름 해시로만 정해지므로 순서는 더 이상 의미가 없다.
  const uniqueTypes = [...new Set(types)];
  const entryByType = new Map<string, PaletteEntry>(
    uniqueTypes.map((type) => [type, PALETTE[hashTypeName(type) % PALETTE.length]]),
  );

  const color = (type: string) => entryByType.get(type)?.base ?? DEFAULT_TYPE_COLOR;
  // #377: base(500)는 라이트 캔버스에서 2.15~2.54:1까지 떨어져 도형 경계로 쓸 수 없다.
  // base를 어둡게 바꾸면 범례 점·드로어 점 인상까지 바뀌므로, 이미 AA를 검증해 둔 테마별
  // 텍스트 shade(라이트 700 / 다크 300)를 윤곽선으로 재사용해 경계만 확보한다.
  const contour = (type: string, isDark: boolean) => {
    const entry = entryByType.get(type);
    if (!entry) return isDark ? DEFAULT_TEXT_DARK : DEFAULT_TEXT_LIGHT;
    return isDark ? entry.textDark : entry.textLight;
  };
  // light: 배경 10% tint + 700 텍스트, dark: 배경 14% tint + 300 텍스트 — 두 경우 모두 AA 통과.
  const colorSet = (type: string, isDark: boolean): EntityColorSet => ({
    base: color(type),
    text: contour(type, isDark),
    tint: hexToRgba(color(type), isDark ? 0.14 : 0.1),
  });

  return { color, contour, colorSet };
}

/**
 * 그래프 캔버스 크롬 색 — 단일 소스 (#377).
 *
 * 무엇: cytoscape 스타일시트가 CSS 변수를 못 읽으므로 리터럴로 들고 있어야 하는 색들.
 * 왜:   InstanceGraph/SchemaGraph가 각자 `chrome()`을 중복 정의하고 있었고, 그 리터럴이
 *       대비 기준 없이 잡혀 엣지가 라이트 1.55 / 다크 1.70:1이었다(SC 1.4.11 요구 3:1).
 *       지식그래프에서 엣지는 장식이 아니라 **관계를 표현하는 핵심 콘텐츠**라 배경에
 *       묻히면 화면의 목적 자체가 성립하지 않는다 → 라이트 4.09 / 다크 4.21로 올렸다.
 *       값이 두 곳에 흩어져 있으면 다시 갈라지므로 여기로 모으고 단위 테스트로 고정한다.
 */
export function graphChrome(isDark: boolean) {
  return isDark
    ? {
        label: '#e5e7eb',
        muted: '#8b847a',
        edge: '#7d766d', // was #3f3b36 (1.70:1) → 4.21:1 on #121110
        ring: '#f1f5f9',
        surface: '#1b1917',
        bg: '#121110',
      }
    : {
        label: '#1b1a17',
        muted: '#6b665d',
        edge: '#827d74', // was #d3cfc7 (1.55:1) → 4.09:1 on #ffffff
        ring: '#0f172a',
        surface: '#ffffff',
        bg: '#ffffff',
      };
}
