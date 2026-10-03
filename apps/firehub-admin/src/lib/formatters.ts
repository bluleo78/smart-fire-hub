/**
 * 날짜 표시 헬퍼.
 *
 * `toLocaleDateString` 을 쓰지 않는 이유: 로케일에 따라 `2026. 3. 4.` 처럼 자릿수가 흔들려
 * 표 컬럼이 들쭉날쭉해진다. 운영자 화면은 정렬해서 훑는 화면이라 고정폭이 낫다.
 * 백엔드는 시각에 저장 TZ 오프셋을 붙여 준다(WD-11, 운영 `…Z`·dev `…+09:00`) — 문자열을 자르면
 * 운영(저장 TZ=UTC)에서 KST 운영자에게 9시간 이른 시각·전날 날짜가 보이므로, 순간으로 해석해 브라우저 로컬로 그린다.
 */

/**
 * 서버 시각 문자열을 순간(Date)으로 해석한다(WD-11).
 * 오프셋이 없으면 UTC 로 본다(웹 parseUtcDate 와 같은 규칙). 해석할 수 없으면 null.
 */
function parseInstant(iso: string): Date | null {
  const d = new Date(/(Z|[+-]\d{2}:?\d{2})$/.test(iso) ? iso : `${iso}Z`);
  return Number.isNaN(d.getTime()) ? null : d;
}

/** 순간을 브라우저 로컬 `yyyy-MM-dd HH:mm:ss` 로 — 아래 헬퍼들이 필요한 길이만큼 잘라 쓴다. */
function localSecondText(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

/** `2026-03-04` 형태(브라우저 로컬 날짜). 테넌트 목록 생성일에 쓴다. 해석할 수 없으면 원문. */
export function formatDateOnly(iso: string): string {
  const d = parseInstant(iso);
  return d ? localSecondText(d).slice(0, 10) : iso;
}

/** `2026-08-19 14:02` 형태(브라우저 로컬). 테넌트 상세 생성일시에 쓴다. 해석할 수 없으면 원문. */
export function formatDateTimeMinute(iso: string): string {
  const d = parseInstant(iso);
  return d ? localSecondText(d).slice(0, 16) : iso;
}

/**
 * 감사 시각(서버가 저장 TZ 오프셋을 붙여 준다, WD-11)을 브라우저 로컬 `yyyy-MM-dd HH:mm:ss` 로.
 * 감사 로그처럼 초 단위 순서가 의미 있는 곳에 쓴다(소수 초는 버린다).
 * 자르지 않는 이유: 운영 저장 TZ 가 UTC 라 자르면 KST 운영자에게 9시간 이른 시각이 보인다.
 * 오프셋이 없으면 UTC 로 본다(웹 parseUtcDate 와 같은 규칙). 해석할 수 없으면 원문을 그대로 보인다.
 */
export function formatDateTimeSecond(iso: string): string {
  const d = parseInstant(iso);
  return d ? localSecondText(d) : iso;
}

/**
 * 로컬 달력 날짜(`yyyy-MM-dd`)의 자정에 addDays 일을 더한 순간을 절대 시각(ISO, Z)으로(WD-11).
 * 감사 기간의 종료일은 addDays=1 — 다음 날 자정 "미만" 이 종료일 하루 전체다.
 */
export function localMidnightIso(date: string, addDays = 0): string {
  const [y, m, d] = date.split('-').map(Number);
  return new Date(y, m - 1, d + addDays).toISOString();
}
