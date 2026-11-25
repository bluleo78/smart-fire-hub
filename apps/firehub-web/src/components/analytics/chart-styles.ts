/** 큰 숫자를 축약 표기 (80000000 → 80M, 만단위 이하는 그대로) */
export function formatYAxisTick(value: unknown): string {
  const n = Number(value);
  if (!Number.isFinite(n)) return String(value);
  if (Math.abs(n) >= 100_000_000) return `${(n / 100_000_000).toFixed(1).replace(/\.0$/, '')}억`;
  if (Math.abs(n) >= 10_000) return `${(n / 10_000).toFixed(1).replace(/\.0$/, '')}만`;
  return String(n);
}

export const TOOLTIP_CONTENT_STYLE = {
  background: 'var(--popover)',
  border: '1px solid var(--border)',
  borderRadius: 6,
  fontSize: 12,
  color: 'var(--popover-foreground)',
} as const;

export const BAR_CURSOR_STYLE = { fill: 'var(--muted)', opacity: 0.5 } as const;

export const LINE_CURSOR_STYLE = { stroke: 'var(--muted-foreground)', strokeWidth: 1 } as const;

/**
 * 차트 시리즈 팔레트 (범주형 8색). #375 — 라이트/다크 각각 카드 배경 대비 3:1 이상.
 *
 * 값은 index.css의 `--chart-1`~`--chart-8`이 소유한다. **여기서 hex를 쓰지 말 것.**
 * 이전에는 같은 하드코딩 배열이 5개 뷰에 중복 정의돼 있어(+ 깔때기/레이더/트리맵 등 별도 팔레트)
 * 팔레트가 13군데로 흩어졌고, 라이트 흰 카드 위에서 8색 중 6색이 1.56~2.73:1이었다.
 *
 * ★ 토큰을 `hsl()` 함수로 감싸지 말 것 — `--chart-*`는 완결형 `oklch()` 색이라
 *   중첩하면 무효 CSS가 되어 선언이 통째로 드롭된다(#374의 결함 그 자체).
 *   반드시 bare `var(--chart-N)`으로 쓴다. hsl-var-gate.test.ts가 이를 강제한다.
 *   SVG presentation attribute의 bare `var()`는 커스텀 프로퍼티 캐스케이드에서 그대로 해석된다.
 */
export const CHART_SERIES_COLORS = [
  'var(--chart-1)',
  'var(--chart-2)',
  'var(--chart-3)',
  'var(--chart-4)',
  'var(--chart-5)',
  'var(--chart-6)',
  'var(--chart-7)',
  'var(--chart-8)',
] as const;

/**
 * 「기타」 조각·범례 스와치의 회색(WD-852) — 테마를 따르는 muted-foreground 토큰이라 밝은/어두운 테마 모두에서 보인다.
 * 대비는 테마의 muted-foreground 가 책임진다(비텍스트 그래픽 3:1 이상, WCAG 1.4.11).
 * 팔레트 색을 쓰면 큰 조각 중 하나와 같은 색이 되어 기타를 그 조각으로 오해하므로 팔레트와 따로 둔다.
 */
export const CHART_OTHER_COLOR = 'var(--muted-foreground)';

/**
 * 시리즈 색 **위에** 얹는 라벨색 (트리맵 타일 등). 범주형 8색은 명도를 일부러 흩뜨렸으므로
 * 라벨색이 색마다 다르다 — 라이트는 chart-2/4/5만 근검정, 나머지는 흰색이다.
 * 반드시 타일 색과 **같은 인덱스 식**으로 골라야 대비 짝이 성립한다.
 */
export const CHART_SERIES_LABEL_COLORS = [
  'var(--chart-1-foreground)',
  'var(--chart-2-foreground)',
  'var(--chart-3-foreground)',
  'var(--chart-4-foreground)',
  'var(--chart-5-foreground)',
  'var(--chart-6-foreground)',
  'var(--chart-7-foreground)',
  'var(--chart-8-foreground)',
] as const;

/**
 * 깔때기 등 순차(단계) 램프. 라이트는 흰 라벨이, 다크는 근검정 라벨이 6단 전부에서
 * 4.5:1을 넘도록 램프 자체의 휘도 범위를 압축했다(라이트 ≤0.183 / 다크 ≥0.197).
 *
 * ★ 인덱스는 순환(`% 6`)이 아니라 **클램프**한다 — 7단째에 가장 밝은 색 다음
 *   가장 어두운 색이 다시 나오면 순차 램프가 비단조로 읽힌다. 깔때기는 세그먼트마다
 *   직접 레이블이 붙어 있어 색이 유일한 식별자가 아니므로 색 중복이 순서 역전보다 낫다.
 */
export const CHART_SEQ_COLORS = [
  'var(--chart-seq-1)',
  'var(--chart-seq-2)',
  'var(--chart-seq-3)',
  'var(--chart-seq-4)',
  'var(--chart-seq-5)',
  'var(--chart-seq-6)',
] as const;

/** 순차 램프 세그먼트 위 라벨색 — 모드 단위로 반전된다(라이트 흰색 / 다크 근검정). */
export const CHART_SEQ_LABEL_COLOR = 'var(--chart-seq-foreground)';

/** 순차 램프 인덱스 클램프 — 위 주석의 비단조 방지 정책. */
export const seqColor = (i: number) => CHART_SEQ_COLORS[Math.min(i, CHART_SEQ_COLORS.length - 1)];

/**
 * 툴팁 항목 텍스트색. recharts `Tooltip`은 범례와 마찬가지로 항목 텍스트를 시리즈 색으로
 * 칠하므로, 본문 텍스트 기준(4.5:1)을 만족하는 팝오버 전경색으로 덮는다 (#375, SC 1.4.3).
 */
export const TOOLTIP_ITEM_STYLE = { color: 'var(--popover-foreground)' } as const;

/**
 * 이 행(또는 점) 수를 넘는 차트는 recharts 시리즈 애니메이션을 끈다 (WD-1072).
 * 500행 차트의 애니메이션이 프레임마다 SVG 를 다시 그려 200~500ms 장기 작업이 이어지기 때문이다.
 */
export const CHART_ANIMATION_MAX_ROWS = 100;

/**
 * 그리는 데이터 행(또는 점) 수가 기준 이하일 때만 시리즈 애니메이션을 켠다 (WD-1072).
 * BoxPlot·Candlestick 은 직접 그린 SVG 라 애니메이션이 없고 Heatmap(nivo)은 animate={false} — 적용 대상 아님(WD-1113).
 */
export function chartAnimationActive(rowCount: number): boolean {
  return rowCount <= CHART_ANIMATION_MAX_ROWS;
}
