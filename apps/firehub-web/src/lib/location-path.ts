/**
 * location-path — 앱 안 경로(pathname + search + hash) 비교용 정규화 (WD-673)
 *
 * 같은 위치라도 표기가 다를 수 있다 — 한글·공백이 그대로인 경로(`/a?q=한글 값`)와 브라우저 주소의 퍼센트 인코딩
 * (`/a?q=%ED%95%9C%EA%B8%80%20%EA%B0%92`), 쿼리 공백의 `+` 와 `%20`, 16진수 대소문자(`%ed` / `%ED`) 등.
 * 문자열을 그대로 비교하면 이 경우들이 늘 다르다고 나와, 「같은 화면으로 돌아왔는가」 판정(useUnsavedChangesGuard 의
 * settleLeaving)이 영영 참이 되지 않는다. 그래서 양쪽을 같은 규칙으로 정규화한 뒤 비교한다.
 *
 * 규칙(비교 전용 — 결과를 이동 주소로 쓰지 않는다):
 * - URL 파서로 나눈다(원문 한글·공백은 파서가 퍼센트 인코딩한다).
 * - pathname·hash 는 decodeURI 로 푼다. decodeURI 는 `%2F`·`%3F`·`%23` 같은 예약 문자를 풀지 않아 `/a%2Fb` 가
 *   `/a/b` 와 같아지지 않는다. 잘못된 인코딩(`%E0%A4` 등)이면 푸는 것을 건너뛰고 원문을 쓴다(예외를 던지지 않음).
 * - search 는 URLSearchParams 로 다시 직렬화한다 — `+`·`%20`·원문 공백, 원문·인코딩 한글, 대소문자 차이를 한 표기로
 *   맞춘다. 잘못된 퍼센트 시퀀스도 URLSearchParams 는 예외 없이 글자 그대로 둔다.
 */

/** 정규화용 가상 출처 — 경로만 다루므로 실제 출처와 무관하다. */
const BASE = 'http://normalize.invalid';

/** decodeURI 를 시도하고, 잘못된 퍼센트 시퀀스로 실패하면 원문을 돌려준다. */
function safeDecodeUri(value: string): string {
  try {
    return decodeURI(value);
  } catch {
    return value;
  }
}

/**
 * 경로 문자열(pathname + search + hash)을 비교용 표기로 정규화한다.
 * URL 로 해석할 수 없는 입력이면 원문을 그대로 돌려준다(비교는 문자열 그대로가 된다).
 */
export function normalizeLocationPath(path: string): string {
  let url: URL;
  try {
    url = new URL(path, BASE);
  } catch {
    return path;
  }
  const search = new URLSearchParams(url.search).toString();
  return safeDecodeUri(url.pathname) + (search ? `?${search}` : '') + safeDecodeUri(url.hash);
}

/** 두 경로가 표기 차이(인코딩·`+`/`%20` 등)를 빼고 같은 위치인지. */
export function isSameLocationPath(a: string, b: string): boolean {
  return a === b || normalizeLocationPath(a) === normalizeLocationPath(b);
}
