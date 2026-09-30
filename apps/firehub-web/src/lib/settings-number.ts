/**
 * 설정 숫자 칸의 표기(문법) 판정(#727).
 *
 * 서버(`SettingsService` 의 `INTEGER_SYNTAX`/`DECIMAL_SYNTAX`)와 **같은 문법**이어야 한다.
 * 예전에는 `Number(raw)` + `Number.isInteger` 로 판정하고 원문 문자열을 그대로 보냈는데,
 * JS 는 `587.0`·`5e2`·`1e1`·`0x10` 을 전부 정수로 읽고 Java `Integer.parseInt` 는 못 읽는다.
 * 그래서 클라이언트 검증은 통과하고 서버가 400 으로 거부했다 — 칸에는 아무 오류도 붙지 않은 채.
 *
 * 값을 정규화해서(`String(Number(raw))`) 보내는 대신 **표기를 거부**한다: 저장된 문자열이 화면에
 * 입력한 문자열과 달라지면 폼의 dirty 판정(입력값 vs 저장값 문자열 비교)이 어긋난다.
 */

/** 부호 없는 십진 정수 표기인가 — `10`, `587`. (`587.0`·`5e2`·`+5`·공백은 아니다) */
export function isIntegerSyntax(raw: string): boolean {
  return /^[0-9]+$/.test(raw);
}

/** 평범한 십진 소수 표기인가 — `0`, `1.0`, `0.75`. (`1e-1`·`.5`·`0x0`·`NaN` 은 아니다) */
export function isDecimalSyntax(raw: string): boolean {
  return /^[0-9]+(\.[0-9]+)?$/.test(raw);
}
