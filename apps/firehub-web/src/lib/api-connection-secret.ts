/**
 * API 연결 비밀 값(API 키·Bearer 토큰) 빈 값 판정 — 화면 안 한 곳(WD-639).
 *
 * 서버(AuthConfigValidator)가 최종 판정을 하고 같은 경우를 400 「… 입력하세요」로 거절한다. 화면은 서버를 부르기 전에 같은 규칙으로
 * 먼저 안내만 한다. 예전에는 생성·저장 전 테스트·상세 저장이 저마다 `token.trim()` 으로 봐서 「Bearer 」 처럼 접두어만 있는 토큰은
 * 화면을 통과해 서버에서야 거절됐고, 문구도 「토큰을 입력하세요.」·「Bearer 토큰을 입력하세요.」로 갈렸다. 규칙이 바뀌면 이 파일과
 * 서버 AuthConfigValidator(normalizeBearerToken·isSecretMissing)를 함께 고친다.
 *
 * 공백 판정은 서버(Java)와 글자 단위로 맞춘다 — JS 의 `trim()`·`\s` 는 NBSP(U+00A0)·U+2007·U+202F·BOM(U+FEFF)까지 공백으로 보지만,
 * 서버의 `String.strip()`·`isBlank()`(Character.isWhitespace)는 그 글자들을 공백으로 보지 않는다. 그대로 쓰면 NBSP 만 있는 토큰을
 * 화면은 「비었다」고 막고 서버는 받아들이는 식으로 어긋난다.
 */

export type SecretAuthType = 'API_KEY' | 'BEARER';

/**
 * Java `Character.isWhitespace` 가 참인 글자 — ASCII 제어 공백(\t \n \u000B \f \r \u001C–\u001F), 공백(U+0020), 유니코드
 * 공백 구분자·줄/문단 구분자. 줄 바뀜 없는 공백(U+00A0·U+2007·U+202F)과 BOM 은 빠진다.
 */
const JAVA_WHITESPACE = '\\t\\n\\u000B\\f\\r\\u001C-\\u001F \\u1680\\u2000-\\u2006\\u2008-\\u200A\\u2028\\u2029\\u205F\\u3000';
const JAVA_STRIP = new RegExp(`^[${JAVA_WHITESPACE}]+|[${JAVA_WHITESPACE}]+$`, 'g');

/**
 * 토큰 앞 「Bearer」 접두어(겹친 것·값 끝의 접두어까지) — 서버 `(?i)^(bearer(\s+|$))+` 와 같다. Java 정규식의 `\s` 는
 * (UNICODE_CHARACTER_CLASS 없이) ASCII 공백 [ \t\n\v\f\r](\v = \x0B) 만이라 여기서도 그 글자만 쓴다.
 */
const BEARER_PREFIX = /^(bearer([ \t\n\v\f\r]+|$))+/i;

/** Java `String.strip()` 과 같은 앞뒤 공백 제거. */
function javaStrip(value: string): string {
  return value.replace(JAVA_STRIP, '');
}

/** 비밀 값을 서버와 같은 방식으로 정리한다 — 앞뒤 공백을 떼고, Bearer 토큰이면 접두어도 뗀다(서버 normalizeBearerToken). */
export function normalizeSecret(authType: SecretAuthType, value: string): string {
  const stripped = javaStrip(value);
  return authType === 'BEARER' ? stripped.replace(BEARER_PREFIX, '') : stripped;
}

/**
 * 비밀 칸이 정리한 뒤 비었을 때의 안내(서버 문구와 같은 이름). 비지 않았으면 null — 서버 `isBlank()` 와 같은 판정.
 * normalizeSecret 이 이미 앞뒤 공백을 떼고(접두어를 뗀 나머지는 원래 값의 끝부분이라 끝 공백이 없다) 돌려주므로 빈 문자열 비교만 한다.
 */
export function missingSecretMessage(authType: SecretAuthType, value: string): string | null {
  if (normalizeSecret(authType, value) !== '') return null;
  return authType === 'API_KEY' ? 'API 키를 입력하세요.' : 'Bearer 토큰을 입력하세요.';
}
