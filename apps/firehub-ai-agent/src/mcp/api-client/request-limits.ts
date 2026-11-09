/**
 * firehub-api 요청 크기 상한의 ai-agent 쪽 사본(WD-600).
 *
 * api 는 아래 상한을 넘는 요청을 Bean Validation 400 으로 거절한다. ai-agent 는 사용자 질문 전체나 LLM 이 뽑은
 * 엔티티 이름을 그대로 보내므로, 400 으로 도구 전체가 실패하지 않게 보내기 전에 맞춘다(자르거나 나눈다).
 * 값은 api 쪽 상수와 같아야 한다 — 한쪽을 바꾸면 다른 쪽도 함께 바꾼다.
 */

/**
 * 검색 질의 길이 상한 — api `SearchQueryLimits.MAX_QUERY_LENGTH`
 * (apps/firehub-api/src/main/java/com/smartfirehub/global/validation/SearchQueryLimits.java)와 같은 값.
 */
export const MAX_SEARCH_QUERY_LENGTH = 1_000;

/**
 * embed() 한 번의 api 호출에 싣는 최대 텍스트 개수 — api `EmbedRequest.MAX_TEXTS`
 * (apps/firehub-api/src/main/java/com/smartfirehub/admin/embedding/EmbedRequest.java)와 같은 값.
 */
export const EMBED_BATCH_SIZE = 256;

/** 임베딩 항목당 최대 길이 — api `EmbedRequest.MAX_TEXT_LENGTH`(같은 파일)와 같은 값. */
export const EMBED_MAX_TEXT_LENGTH = 2_000;

/**
 * 문자열을 최대 max UTF-16 코드 단위로 자른다 — api 의 String.length() 와 같은 기준.
 * 자른 끝이 서로게이트 쌍의 앞 절반이면 그 반쪽도 버린다(깨진 문자를 보내지 않게). 짧으면 그대로 돌려준다.
 */
export function truncateUtf16(text: string, max: number): string {
  if (text.length <= max) return text;
  let cut = text.slice(0, max);
  const last = cut.charCodeAt(cut.length - 1);
  if (last >= 0xd800 && last <= 0xdbff) cut = cut.slice(0, -1);
  return cut;
}

/**
 * 검색 질의를 api 상한에 맞춘다. graphrag_query·search_documents·데이터셋 검색 도구는 사용자 질문 전체를 질의로 넘기는데,
 * 의미 검색은 앞 1,000자로도 충분하고 400 으로 도구가 실패하는 쪽이 더 나쁘므로 자른다.
 */
export function clampSearchQuery(query: string): string {
  return truncateUtf16(query, MAX_SEARCH_QUERY_LENGTH);
}
