// apps/firehub-web/src/components/analytics/chart-legend.tsx
// 범례 렌더러 — chart-styles.ts는 JSX를 담지 않으므로 분리한다.

/**
 * 범례 텍스트를 시리즈 색이 아니라 본문 전경색으로 칠한다 (#375, SC 1.4.3).
 *
 * 무엇: recharts `Legend`는 항목 텍스트를 시리즈 색으로 칠하는 게 기본 동작이라
 *       `#8884d8` 기준 라이트 3.31:1 / 2번째 시리즈는 1.93:1까지 떨어졌다.
 * 왜:   시리즈색은 그래픽 객체 기준(3:1)으로 설계된 값이라 본문 텍스트 기준(4.5:1)을
 *       동시에 만족시킬 수 없다. 팔레트로는 풀리지 않으므로 역할을 분리한다 —
 *       색 정보는 왼쪽 스와치 아이콘에만 남기고(그래픽 객체 3:1), 텍스트는 --foreground.
 *       전 사이트가 같은 심볼을 꽂도록 단일 export로 둬서 부분 적용을 막는다.
 */
export const CHART_LEGEND_FORMATTER = (value: string) => (
  <span style={{ color: 'var(--foreground)' }}>{value}</span>
);
