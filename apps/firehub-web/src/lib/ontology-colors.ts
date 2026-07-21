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
