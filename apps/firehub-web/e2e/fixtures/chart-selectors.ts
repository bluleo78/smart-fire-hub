/**
 * recharts 차트 본체 svg 선택자(WD-826) — 범례 아이콘도 recharts-surface 라 클래스만으로는 섞인다.
 * recharts-wrapper 바로 아래 svg 로 고르면 차트 본체만 잡힌다. 여러 spec 이 같은 기준을 쓰도록 한 곳에 둔다.
 */
export const CHART_SURFACE = '.recharts-wrapper > svg.recharts-surface';
