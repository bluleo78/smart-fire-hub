/**
 * 서버가 보내는 10진 숫자 문자열 패턴(선택 '-'·정수부·선택 소수부) — WD-101·WD-104 공용.
 *
 * 데이터셋 행 조회·SQL 탭 응답은 JS double 로 깎이는 NUMERIC·BIGINT 값을 BigDecimal.toPlainString·Long.toString
 * 문자열로 보낸다. 그 모양만 숫자 문자열로 인정하고 '+'·지수 표기·쉼표·공백은 맞지 않게 한다.
 * 캡처 그룹: 1 = 부호('-' 또는 ''), 2 = 정수부, 3 = 소수부('.' 포함, 없으면 undefined).
 * g 플래그가 없어 test/exec 를 여러 번 불러도 lastIndex 상태가 남지 않는다.
 *
 * 쓰는 곳: AI 표 위젯 CellRenderer(자릿수 보존 쉼표 표시), AI 챗 인라인 차트 rows 정규화(chart-numeric-rows).
 */
export const PLAIN_DECIMAL_RE = /^(-?)(\d+)(\.\d+)?$/;
