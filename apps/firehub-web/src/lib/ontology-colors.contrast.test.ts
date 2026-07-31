/**
 * 그래프 캔버스 색 대비 회귀 테스트 (#377)
 *
 * 무엇: Cytoscape 캔버스에 그려지는 리터럴 색(엣지·엣지 라벨·노드 윤곽선)이
 *       캔버스 배경 대비 SC 1.4.11(그래픽 객체 3:1) / SC 1.4.3(텍스트 4.5:1)을
 *       만족하는지 단언한다.
 * 왜:   캔버스는 DOM 계산색이 없어 브라우저 픽셀 샘플링으로만 실측할 수 있는데,
 *       그건 CI에서 돌릴 수 없다. 다행히 이 색들은 CSS 변수가 아니라 소스의 리터럴
 *       상수이므로(cytoscape 스타일시트가 var()를 못 읽는다) 값 자체를 여기서 고정한다.
 *       엣지는 라이트 1.55 / 다크 1.70:1, 노드는 Cause 2.15 / Equipment 2.54:1이었다.
 */
import { describe, expect, it } from 'vitest';

import {
  contourForType,
  DEFAULT_TYPE_COLOR,
  ENTITY_TYPE_COLORS,
  graphChrome,
} from './ontology-colors';

/** `#rrggbb` → [r,g,b] */
function rgb(hex: string): [number, number, number] {
  const h = hex.replace('#', '');
  return [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16)) as [number, number, number];
}

/** WCAG 2.x 상대휘도 */
function luminance(hex: string): number {
  const [r, g, b] = rgb(hex);
  const lin = (v: number) => {
    const c = v / 255;
    return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
}

function contrast(a: string, b: string): number {
  const l1 = luminance(a);
  const l2 = luminance(b);
  const [hi, lo] = l1 > l2 ? [l1, l2] : [l2, l1];
  return (hi + 0.05) / (lo + 0.05);
}

const MODES = [
  { name: 'light', isDark: false },
  { name: 'dark', isDark: true },
] as const;

// 온톨로지에 없는 타입 폴백까지 포함해 전수 검사한다.
const ALL_TYPES = [...Object.keys(ENTITY_TYPE_COLORS), '알수없는타입'];

describe('색 변환 엔진', () => {
  it('알려진 대비값을 재현한다', () => {
    // 이슈 본문의 픽셀 샘플링 실측치(#d3cfc7 on #ffffff = 1.55)를 픽스처로 고정해
    // 계산기가 틀렸을 때 아래 단언 전체가 공허해지는 것을 막는다.
    expect(contrast('#d3cfc7', '#ffffff')).toBeCloseTo(1.55, 1);
    expect(contrast('#3f3b36', '#121110')).toBeCloseTo(1.7, 1);
    expect(contrast('#f59e0b', '#ffffff')).toBeCloseTo(2.15, 1);
  });
});

describe('#377 그래프 엣지', () => {
  it.each(MODES)('$name: 엣지선이 캔버스 배경 대비 ≥ 3 (SC 1.4.11)', ({ isDark }) => {
    const c = graphChrome(isDark);
    expect(contrast(c.edge, c.bg), `edge=${c.edge} bg=${c.bg}`).toBeGreaterThanOrEqual(3);
  });

  it.each(MODES)('$name: 엣지 라벨이 캔버스 배경 대비 ≥ 4.5 (SC 1.4.3)', ({ isDark }) => {
    const c = graphChrome(isDark);
    expect(contrast(c.muted, c.bg)).toBeGreaterThanOrEqual(4.5);
  });

  it.each(MODES)('$name: 노드 라벨·선택 링도 계속 통과한다', ({ isDark }) => {
    const c = graphChrome(isDark);
    expect(contrast(c.label, c.bg)).toBeGreaterThanOrEqual(4.5);
    expect(contrast(c.ring, c.bg)).toBeGreaterThanOrEqual(3);
  });
});

describe('#377 노드 윤곽선', () => {
  it.each(
    MODES.flatMap((m) => ALL_TYPES.map((t) => ({ mode: m.name, isDark: m.isDark, type: t })))
  )('$mode/$type: 윤곽선이 캔버스 배경 대비 ≥ 3 (SC 1.4.11)', ({ isDark, type }) => {
    const c = graphChrome(isDark);
    const contour = contourForType(type, isDark);
    expect(contrast(contour, c.bg), `${type} contour=${contour}`).toBeGreaterThanOrEqual(3);
  });

  it('타입 base(500) 색은 그대로 둔다 — 범례 점·드로어 점과 인상을 공유하기 때문', () => {
    // base를 어둡게 바꾸는 대신 윤곽선을 두른 것이 이 수정의 핵심이므로,
    // 누군가 base를 조용히 바꾸면 근거가 무너진다는 것을 명시적으로 고정한다.
    expect(ENTITY_TYPE_COLORS.Cause).toBe('#f59e0b');
    expect(ENTITY_TYPE_COLORS.Equipment).toBe('#10b981');
    expect(DEFAULT_TYPE_COLOR).toBe('#64748b');
  });
});
