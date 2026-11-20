/**
 * 게이지 범위가 잘못됐을 때 호·바늘 대신 보여 주는 안내 — 판정 이유는 computeGaugeReading 참고(WD-848). 높이가 없으면 부모를 채운다.
 * 'not-increasing' 은 두 값을 담아 알리고, 'non-finite'(NaN·Infinity)는 값을 문구에 넣지 않고 계산 불가로 알린다.
 */
export function GaugeInvalidRangeNotice(
  props: { height?: number } & ({ reason: 'non-finite' } | { reason: 'not-increasing'; min: number; max: number }),
) {
  const { height } = props;
  return (
    <div
      data-testid="chart-gauge-invalid-range"
      className={`flex items-center justify-center text-muted-foreground text-sm ${height ? '' : 'h-full'}`}
      style={height ? { height } : undefined}
    >
      {props.reason === 'non-finite'
        ? '게이지 범위를 계산할 수 없습니다 — 최솟값·최댓값이 올바른 숫자가 아닙니다.'
        : `게이지 최댓값(${props.max.toLocaleString('ko-KR')})이 최솟값(${props.min.toLocaleString('ko-KR')})보다 커야 합니다.`}
    </div>
  );
}
