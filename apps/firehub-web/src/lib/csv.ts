/**
 * 브라우저에서 만드는 CSV(채팅 표 위젯·데이터셋 위젯·선택 행 내보내기)의 셀 직렬화 공용 도우미.
 *
 * CSV 수식 주입(OWASP CSV Injection) 방어: 문자열 값이 `=`, `+`, `-`, `@`, 탭(`\t`), 캐리지 리턴(`\r`) 으로 시작하면
 * Excel·LibreOffice 가 파일을 열 때 수식으로 실행한다(예: `=HYPERLINK("http://…","클릭")`). 행을 쓸 수 있는 누구나 이런 값을
 * 심을 수 있으므로 OWASP 권고대로 앞에 작은따옴표 `'` 를 붙여 텍스트로 취급하게 한다. 백엔드 CsvFormulaGuard 와 같은 규칙이다(WD-156).
 *
 * - 문자열(JS string) 값만 중화한다. 숫자·불리언은 원형 유지 — 음수 `-5` 에 따옴표가 붙으면 데이터가 망가진다.
 * - 첫 글자만 본다(OWASP 권고의 첫 글자 목록을 따르는 결정). 앞 공백 뒤의 위험 문자(` =1+1`)는 범위에서 제외했다.
 */

/** 수식으로 해석되는 첫 글자 — OWASP CSV Injection 권고 목록. */
const FORMULA_START = new Set(['=', '+', '-', '@', '\t', '\r']);

/** 문자열이 수식 시작 문자로 시작하면 앞에 `'` 를 붙인다. 아니면 그대로 돌려준다. */
export function neutralizeCsvFormula(value: string): string {
  return value.length > 0 && FORMULA_START.has(value[0]) ? `'${value}` : value;
}

/**
 * 값 하나를 CSV 셀 문자열로 만든다.
 *
 * - null/undefined → 빈 칸
 * - 문자열 → 수식 주입 중화
 * - 객체(GeoJSON·JSON 등) → JSON 문자열(`{`·`[` 로 시작하므로 수식 아님)
 * - 그 외(숫자·불리언) → String() 원형
 *
 * 그 뒤 RFC 4180 규칙으로 쌍따옴표·콤마·개행(\n·\r)이 있으면 쌍따옴표로 감싸고 내부 쌍따옴표는 두 번 쓴다.
 */
export function toCsvCell(value: unknown): string {
  if (value === null || value === undefined) return '';
  let str: string;
  if (typeof value === 'string') {
    str = neutralizeCsvFormula(value);
  } else if (typeof value === 'object') {
    str = JSON.stringify(value);
  } else {
    str = String(value);
  }
  return /[",\r\n]/.test(str) ? `"${str.replace(/"/g, '""')}"` : str;
}
