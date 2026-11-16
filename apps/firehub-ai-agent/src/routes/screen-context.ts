/**
 * 화면 컨텍스트(screenContext) 서버 쪽 정리 (WD-759).
 *
 * 웹은 상세 화면의 이름(사용자가 붙인 글)을 `[현재 화면] 데이터셋 상세 (ID: 1, 이름: 「…」)` 처럼 실어 보내고,
 * chat 라우트는 이것을 시스템 프롬프트에 붙인다. 웹이 이름을 한 번 정리하지만 api 를 직접 부르는 호출자는 그 정리를
 * 거치지 않으므로, 프롬프트에 붙이기 직전에 여기서 한 번 더 정리한다(저장된 프롬프트 주입면 줄이기).
 * - 제어 문자·줄바꿈 → 공백: 새 지시처럼 보이는 줄을 만들지 못하게 한 줄로.
 * - 맨 앞 `[현재 화면]` 태그 밖의 대괄호 제거: `[시스템]` 같은 태그를 흉내 내지 못하게.
 * - 길이 상한(코드 포인트): 긴 지시문을 통째로 싣지 못하게.
 * 이름 안의 글을 「데이터로만 다룬다」는 규칙은 시스템 프롬프트(L6·opencode 화면 컨텍스트 절)가 맡는다.
 */

/** 화면 컨텍스트 전체 길이 상한 — 웹의 이름 상한(100자)에 화면 라벨·ID 를 더해도 넉넉한 값. */
export const SCREEN_CONTEXT_MAX_LENGTH = 300;

/** 웹이 붙이는 화면 컨텍스트 태그 — 이것만 대괄호를 남긴다. */
const SCREEN_CONTEXT_TAG = '[현재 화면]';

/**
 * 요청 바디의 screenContext 를 프롬프트에 붙여도 되는 한 줄로 정리한다.
 * 문자열이 아니거나 정리 뒤 비면 undefined(프롬프트에 붙이지 않음).
 */
export function sanitizeScreenContext(raw: unknown): string | undefined {
  if (typeof raw !== 'string') return undefined;
  // eslint-disable-next-line no-control-regex -- 제어 문자를 지우는 것이 목적이다
  const oneLine = raw.replace(/[\u0000-\u001f\u007f-\u009f\u2028\u2029]+/g, ' ').trim();
  const tagged = oneLine.startsWith(SCREEN_CONTEXT_TAG);
  const body = (tagged ? oneLine.slice(SCREEN_CONTEXT_TAG.length) : oneLine)
    .replace(/[[\]]/g, '')
    .replace(/\s+/g, ' ')
    .trim();
  if (!body) return undefined;
  const text = tagged ? `${SCREEN_CONTEXT_TAG} ${body}` : body;
  // 서로게이트 쌍이 반으로 잘리지 않게 코드 포인트 단위로 자른다.
  return Array.from(text).slice(0, SCREEN_CONTEXT_MAX_LENGTH).join('');
}
