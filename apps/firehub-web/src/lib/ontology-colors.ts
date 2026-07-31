// 엔티티 타입 색상 — 스키마·인스턴스·범례가 공유하는 단일 소스.
export const ENTITY_TYPE_COLORS: Record<string, string> = {
  Incident: '#ef4444', Building: '#3b82f6', Cause: '#f59e0b',
  Damage: '#ec4899', Equipment: '#10b981', Regulation: '#8b5cf6',
};
// 온톨로지에 없는 타입 방어용 기본색(회색).
export const DEFAULT_TYPE_COLOR = '#64748b';
export const colorForType = (type: string): string => ENTITY_TYPE_COLORS[type] ?? DEFAULT_TYPE_COLOR;

// 테마 인지 텍스트 색상 — base(500) 배경 위 흰 글씨는 WCAG AA(4.5:1) 미달이므로,
// light 모드는 700 shade(옅은 tint 배경 위 진한 글씨), dark 모드는 300 shade(어두운 tint 배경 위 밝은 글씨)로 대비를 확보한다.
const TEXT_LIGHT: Record<string, string> = {
  Incident: '#b91c1c', Building: '#1d4ed8', Cause: '#b45309',
  Damage: '#be185d', Equipment: '#047857', Regulation: '#6d28d9',
};
const TEXT_DARK: Record<string, string> = {
  Incident: '#fca5a5', Building: '#93c5fd', Cause: '#fcd34d',
  Damage: '#f9a8d4', Equipment: '#6ee7b7', Regulation: '#c4b5fd',
};
// 타입에 없는 경우 slate 계열 폴백(700/300).
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
  base: string;   // 타입색 500 — border·범례 점에 사용
  text: string;   // 대비 통과 텍스트 색(테마별 700/300 shade)
  tint: string;   // 옅은 배경 tint(테마별 alpha)
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

/**
 * 타입 노드의 윤곽선 색 (#377).
 *
 * base(500)는 라이트 캔버스에서 `Cause` 2.15 / `Equipment` 2.54:1이라 도형 경계로 쓸 수 없다.
 * base 자체를 어둡게 바꾸면 범례 점·드로어 점까지 인상이 바뀌므로, **이미 AA를 검증해 둔**
 * 테마별 텍스트 shade(라이트 700 / 다크 300)를 윤곽선으로 재사용해 경계만 확보한다.
 */
export const contourForType = (type: string, isDark: boolean): string =>
  isDark ? (TEXT_DARK[type] ?? DEFAULT_TEXT_DARK) : (TEXT_LIGHT[type] ?? DEFAULT_TEXT_LIGHT);

// 타입 + 테마(dark 여부)로 노드 색상 묶음을 계산한다.
// light: 배경 10% tint + 700 텍스트, dark: 배경 14% tint + 300 텍스트 — 두 경우 모두 AA 대비 통과.
export const entityColorSet = (type: string, isDark: boolean): EntityColorSet => {
  const base = colorForType(type);
  if (isDark) {
    return {
      base,
      text: TEXT_DARK[type] ?? DEFAULT_TEXT_DARK,
      tint: hexToRgba(base, 0.14),
    };
  }
  return {
    base,
    text: TEXT_LIGHT[type] ?? DEFAULT_TEXT_LIGHT,
    tint: hexToRgba(base, 0.10),
  };
};
