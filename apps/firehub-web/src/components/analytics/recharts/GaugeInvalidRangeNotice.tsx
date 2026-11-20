/** 게이지 범위가 잘못됐을 때 호·바늘 대신 보여 주는 안내 — 판정 이유는 computeGaugeReading 참고(WD-848). 높이가 없으면 부모를 채운다. */
export function GaugeInvalidRangeNotice({ min, max, height }: { min: number; max: number; height?: number }) {
  return (
    <div
      data-testid="chart-gauge-invalid-range"
      className={`flex items-center justify-center text-muted-foreground text-sm ${height ? '' : 'h-full'}`}
      style={height ? { height } : undefined}
    >
      게이지 최댓값({max.toLocaleString('ko-KR')})이 최솟값({min.toLocaleString('ko-KR')})보다 커야 합니다.
    </div>
  );
}
