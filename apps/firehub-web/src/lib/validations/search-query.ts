/**
 * 검색어 길이 상한(WD-600) — api `SearchQueryLimits.MAX_QUERY_LENGTH`
 * (`apps/firehub-api/.../global/validation/SearchQueryLimits.java`)와 같은 값이어야 한다. 한쪽을 바꾸면 다른 쪽도 바꾼다.
 *
 * 서버는 이보다 긴 query 를 400 으로 거절한다. 화면 입력에 같은 상한(`maxLength`)을 걸어 사용자가 애초에 넘길 수 없게 한다.
 * 브라우저 maxLength 도 UTF-16 코드 단위로 세므로 서버(`String.length()`)와 기준이 같다.
 */
export const MAX_SEARCH_QUERY_LENGTH = 1_000;
