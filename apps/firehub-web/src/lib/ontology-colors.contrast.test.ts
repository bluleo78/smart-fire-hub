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

import { createTypePalette, DEFAULT_TYPE_COLOR, graphChrome, PALETTE } from './ontology-colors';

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

describe('#507 graphChrome 실제 배경 주입', () => {
  it.each(MODES)('$name: background 인자를 넘기면 bg가 그 값으로 바뀐다', ({ isDark }) => {
    const c = graphChrome(isDark, 'oklch(0.12 0.015 210)');
    expect(c.bg).toBe('oklch(0.12 0.015 210)');
  });

  it.each(MODES)('$name: background를 생략하면 기존 리터럴 폴백을 그대로 쓴다', ({ isDark }) => {
    const withNoArg = graphChrome(isDark);
    const withEmptyArg = graphChrome(isDark, '');
    const fallback = isDark ? '#121110' : '#ffffff';
    expect(withNoArg.bg).toBe(fallback);
    expect(withEmptyArg.bg).toBe(fallback);
  });
});

describe('#377 노드 윤곽선 (#396 팔레트 12칸 전수)', () => {
  // 팔레트 엔트리를 직접 순회한다 — createTypePalette를 통해 합성 타입명으로 돌리면
  // localeCompare 정렬이 T0,T1,T10,T11,T2... 순서라 "i번째 타입 = PALETTE[i]" 가정이 어긋난다.
  it.each(PALETTE.map((entry, i) => ({ i, ...entry })))(
    'light/PALETTE[$i]: 윤곽선(textLight)이 캔버스 배경 대비 ≥ 3 (SC 1.4.11)',
    ({ textLight }) => {
      const c = graphChrome(false);
      expect(contrast(textLight, c.bg), `contour=${textLight}`).toBeGreaterThanOrEqual(3);
    },
  );

  it.each(PALETTE.map((entry, i) => ({ i, ...entry })))(
    'dark/PALETTE[$i]: 윤곽선(textDark)이 캔버스 배경 대비 ≥ 3 (SC 1.4.11)',
    ({ textDark }) => {
      const c = graphChrome(true);
      expect(contrast(textDark, c.bg), `contour=${textDark}`).toBeGreaterThanOrEqual(3);
    },
  );

  it.each(MODES)('$name: 팔레트 밖 타입(회색 폴백)도 대비 ≥ 3', ({ isDark }) => {
    const c = graphChrome(isDark);
    const palette = createTypePalette(['Incident']);
    const contour = palette.contour('알수없는타입', isDark);
    expect(contrast(contour, c.bg), `contour=${contour}`).toBeGreaterThanOrEqual(3);
  });

  it('타입 base(500) 색은 그대로 둔다 — 범례 점·드로어 점과 인상을 공유하기 때문', () => {
    // base를 어둡게 바꾸는 대신 윤곽선을 두른 것이 이 수정의 핵심이므로,
    // 누군가 base를 조용히 바꾸면 근거가 무너진다는 것을 명시적으로 고정한다.
    // (#396) 이름 키가 사라졌으므로 팔레트 인덱스 기준으로 재고정한다 — amber(2)/emerald(4)는
    // 예전 Cause/Equipment가 쓰던 값이고, 앞 6칸의 순서·값 자체가 인상 연속성의 근거다.
    expect(PALETTE).toHaveLength(12);
    expect(PALETTE[2].base).toBe('#f59e0b');
    expect(PALETTE[4].base).toBe('#10b981');
    expect(PALETTE.map((p) => p.base).slice(0, 6)).toEqual([
      '#ef4444',
      '#3b82f6',
      '#f59e0b',
      '#ec4899',
      '#10b981',
      '#8b5cf6',
    ]);
    expect(DEFAULT_TYPE_COLOR).toBe('#64748b');
  });
});

/**
 * (#396, #493) 해시 기반 색 배정 회귀 테스트.
 *
 * 왜: 예전 구현(#396)은 6개 데모 타입명 리터럴 키 룩업이라, 사용자가 만든 온톨로지의 타입 대부분이
 *     회색 하나로 뭉개졌다. 그 대체(정렬 인덱스)는 사용자가 만든 온톨로지 대부분에서 색이 갈리게는
 *     했지만, 타입 하나를 추가/삭제하면 정렬 위치가 바뀌는 다른 모든 타입의 색까지 함께 바뀌는
 *     문제를 재도입했다(#493). 이제는 타입 이름 자체를 해시해 인덱스를 정하므로, 아래 성질들
 *     (집합 순서 무관 / **타입 집합 변화에도 개별 타입 색 고정** / 12색 초과 시 재사용 / 목록 밖
 *     폴백)이 이 교체가 실제로 문제를 푼다는 근거다.
 */
describe('#396 / #493 createTypePalette', () => {
  // 해시 mod 12 인덱스가 서로 겹치지 않는 것을 사전에 확인해 둔 9개 타입명 — "회색으로 뭉개지지
  // 않는다" 단언이 우연한 해시 충돌로 깨지는 것을 피한다.
  const TYPES = ['FireIncident', 'Facility', 'FireCause', 'QATestType', 'Suspect', 'Evidence', 'Weapon', 'District', 'Municipality'];

  it('같은 타입 집합을 다른 순서로 넣어도 타입별 색이 같다', () => {
    const a = createTypePalette(TYPES);
    const b = createTypePalette([...TYPES].reverse());
    for (const t of TYPES) {
      expect(b.color(t), t).toBe(a.color(t));
      expect(b.contour(t, false), t).toBe(a.contour(t, false));
      expect(b.contour(t, true), t).toBe(a.contour(t, true));
    }
  });

  it('타입 수가 팔레트보다 적으면 서로 다른 색을 받는다 (회색으로 뭉개지지 않는다)', () => {
    const palette = createTypePalette(TYPES);
    const colors = TYPES.map((t) => palette.color(t));
    expect(new Set(colors).size).toBe(TYPES.length);
    expect(colors).not.toContain(DEFAULT_TYPE_COLOR);
  });

  it('#493: 타입을 추가/삭제해도 기존 타입의 색은 바뀌지 않는다', () => {
    // 이슈 재현 시나리오 그대로: ExplorerTestType 추가 전/후로 Incident·QATestType 색이 같아야 한다.
    const before = createTypePalette(['Building', 'Cause', 'Damage', 'Equipment', 'ExplorerTestType', 'Incident', 'QATestType', 'Regulation']);
    const after = createTypePalette(['Building', 'Cause', 'Damage', 'Equipment', 'Incident', 'QATestType', 'Regulation']);
    expect(after.color('Incident')).toBe(before.color('Incident'));
    expect(after.color('QATestType')).toBe(before.color('QATestType'));
    expect(after.contour('Incident', false)).toBe(before.contour('Incident', false));
    expect(after.contour('QATestType', false)).toBe(before.contour('QATestType', false));

    // 타입을 더 추가해도 마찬가지다 (집합의 다른 원소 유무와 무관).
    const withMore = createTypePalette(['Incident', 'QATestType', 'NewlyAddedType', 'AnotherNewType']);
    expect(withMore.color('Incident')).toBe(before.color('Incident'));
    expect(withMore.color('QATestType')).toBe(before.color('QATestType'));
  });

  it('12개를 넘는 타입에서도 팔레트 안의 색만 배정한다(폴백 회색으로 떨어지지 않는다)', () => {
    const many = Array.from({ length: 16 }, (_, i) => String.fromCharCode(65 + i));
    const palette = createTypePalette(many);
    const colors = many.map((t) => palette.color(t));
    expect(colors).not.toContain(DEFAULT_TYPE_COLOR);
    for (const c of colors) {
      expect(PALETTE.map((p) => p.base)).toContain(c);
    }
  });

  it('목록에 없는 타입은 회색 폴백을 받는다', () => {
    const palette = createTypePalette(TYPES);
    expect(palette.color('없는타입')).toBe(DEFAULT_TYPE_COLOR);
    expect(palette.contour('없는타입', false)).toBe('#334155');
    expect(palette.contour('없는타입', true)).toBe('#cbd5e1');
    expect(palette.colorSet('없는타입', false).tint).toBe('rgba(100, 116, 139, 0.1)');
  });

  it('중복 타입명이 섞여도 색이 밀리지 않는다', () => {
    const withDupes = createTypePalette(['B', 'A', 'B', 'C']);
    const clean = createTypePalette(['A', 'B', 'C']);
    for (const t of ['A', 'B', 'C']) expect(withDupes.color(t), t).toBe(clean.color(t));
  });

  it('입력 배열을 변형하지 않는다 — 호출처의 allTypes는 #412 activeTypes 동기화 입력이다', () => {
    const input = ['C', 'A', 'B'];
    createTypePalette(input);
    expect(input).toEqual(['C', 'A', 'B']);
  });
});
