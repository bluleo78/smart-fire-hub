/**
 * http(s) URL 입력칸 공용 형식 검사(WD-909).
 *
 * 파이프라인 API 호출 스텝·API 연결 Base URL·데이터 가져오기 마법사가 URL 칸마다 따로 검사해
 * 같은 값을 어떤 화면은 통과시키고 어떤 화면은 막았다. 규칙을 이 한 곳에 둔다.
 */

/**
 * 사용자 정보(`아이디:비밀번호@`)를 뗀 URL. 서버 BaseUrlUserInfo 처럼 `://` 뒤 첫 `/`·`?`·`#` 앞까지에서
 * 마지막 `@` 앞을 사용자 정보로 본다 — 그래야 경로·쿼리의 `@` 를 사용자 정보로 읽지 않는다.
 */
export function stripUrlUserInfo(url: string): string {
  return url.replace(/^(https?:\/\/)[^/?#]*@/i, '$1');
}

/**
 * 값이 http/https URL 형식인지 본다. 통과하면 undefined, 아니면 오류 문구.
 * - 앞뒤 공백은 무시하고, 빈 값은 통과시킨다(필수 여부는 호출자가 따로 본다).
 * - 스킴은 대소문자를 가리지 않는다(실행기가 HTTPS:// 도 받는다).
 * - 스킴 뒤에 호스트가 없거나(`https://`, `https:///x`, `https://?a`), 호스트 자리에 공백·역슬래시가 끼었거나, URL 로 해석되지 않으면 형식 오류다.
 * - 공백은 호스트 자리에서만 오류다. 실행기(Python httpx)는 경로·쿼리·해시의 공백을 %20 으로 보내
 *   그런 URL 의 파이프라인이 지금도 돌므로, 막으면 멀쩡히 돌던 저장이 막히는 회귀가 된다.
 *   호스트 자리 = `://` 뒤 첫 `/`·`?`·`#` 앞(권한부) 중 사용자 정보({@link stripUrlUserInfo}) 뒤.
 * - 역슬래시도 호스트 자리에서만 오류다. `new URL` 은 `/` 로 바꿔 읽어 통과시키지만 Java·Python 은 어느 쪽도 부르지 못한다.
 * - 제어문자(U+0000~U+001F, U+007F; 탭·줄바꿈 포함)는 어느 자리에서든 오류다. 붙여 넣다 섞인 값이고,
 *   대부분 실행 때 거절되거나 글자가 빠진 다른 주소로 불린다.
 * @param label 오류 문구 앞말(기본 'URL')
 */
export function httpUrlError(value: string, label = 'URL'): string | undefined {
  const text = value.trim();
  if (!text) return undefined;
  const scheme = /^https?:\/\//i.exec(text);
  if (!scheme) return `${label}은 http 또는 https로 시작해야 합니다.`;
  const host = stripUrlUserInfo(text).slice(scheme[0].length).split(/[/?#]/, 1)[0];
  // eslint-disable-next-line no-control-regex -- 제어문자 검출이 목적이다
  const hasControl = /[\u0000-\u001f\u007f]/.test(text);
  if (!host || /[\s\\]/.test(host) || hasControl) return `${label} 형식이 올바르지 않습니다.`;
  try {
    new URL(text);
  } catch {
    return `${label} 형식이 올바르지 않습니다.`;
  }
  return undefined;
}
