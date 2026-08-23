/**
 * 날짜 표시 헬퍼.
 *
 * `toLocaleDateString` 을 쓰지 않는 이유: 로케일에 따라 `2026. 3. 4.` 처럼 자릿수가 흔들려
 * 표 컬럼이 들쭉날쭉해진다. 운영자 화면은 정렬해서 훑는 화면이라 고정폭이 낫다.
 * 백엔드는 `@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")` 로 내려주므로 타임존 표기가 없다 —
 * `new Date()` 에 그대로 넣으면 브라우저가 로컬 시각으로 해석해 하루가 밀 수 있어 문자열을 자른다.
 */
export function formatDateOnly(iso: string): string {
  return iso.slice(0, 10);
}

/** `2026-08-19 14:02` 형태. `updatedAt` 표시에 쓴다. */
export function formatDateTimeMinute(iso: string): string {
  return `${iso.slice(0, 10)} ${iso.slice(11, 16)}`;
}
