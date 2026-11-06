/**
 * 값 컬럼이 숫자가 아닐 때 차트 대신 보여 주는 안내(WD-505).
 * 게이지·퍼널은 값 컬럼을 Number() 로 읽어, 문자 컬럼을 고르면 NaN 이 그대로 그려졌다.
 * ChartRenderer 의 문장 하나짜리 안내와 같은 muted 가운데 정렬을 쓰고, height 가 없으면(fillParent) 부모 높이를 채운다.
 */
export function NonNumericValueNotice({ height }: { height?: number }) {
  return (
    <div
      data-testid="chart-non-numeric-value"
      className={`flex items-center justify-center text-muted-foreground text-sm ${height ? '' : 'h-full'}`}
      style={height ? { height } : undefined}
    >
      숫자 값 컬럼을 선택하세요.
    </div>
  );
}
