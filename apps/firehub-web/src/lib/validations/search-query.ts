import { truncateUtf16 } from '../clone-name';

/**
 * 검색어 길이 상한(WD-600) — api `SearchQueryLimits.MAX_QUERY_LENGTH`
 * (`apps/firehub-api/.../global/validation/SearchQueryLimits.java`)와 같은 값이어야 한다. 한쪽을 바꾸면 다른 쪽도 바꾼다.
 *
 * 서버는 이보다 긴 query 를 400 으로 거절한다. 화면 입력에 같은 상한(`maxLength`)을 걸어 사용자가 애초에 넘길 수 없게 한다.
 * 브라우저 maxLength 도 UTF-16 코드 단위로 세므로 서버(`String.length()`)와 기준이 같다.
 */
export const MAX_SEARCH_QUERY_LENGTH = 1_000;

/**
 * 값을 maxLength(UTF-16 코드 유닛 기준 — input maxlength 와 같은 단위)로 자른다(WD-1131).
 * 한글 IME 조합이 끝나는 순간처럼 브라우저가 maxlength 를 지키지 않고 값을 넘겨 주는 경우에도
 * 서버 상한(400)을 넘는 검색어가 부모로 나가지 않게 하려고 컴포넌트가 직접 자른다.
 * 끝이 서로게이트 쌍(이모지 등)의 앞 반쪽에서 끊기면 깨진 글자가 되므로 그 반쪽은 버린다.
 */
export function clampToMaxLength(value: string, maxLength?: number): string {
  return maxLength === undefined ? value : truncateUtf16(value, maxLength);
}
