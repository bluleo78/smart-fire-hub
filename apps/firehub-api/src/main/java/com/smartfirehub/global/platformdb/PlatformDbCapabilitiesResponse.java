package com.smartfirehub.global.platformdb;

/**
 * 플랫폼 DB 가 지원하는 선택 기능 목록 응답(WD-495). 웹·ai-agent 가 꺼진 검색 기능을 미리 숨기거나 안내하는 데 쓴다.
 *
 * @param vectorSearch 벡터(의미) 검색 지원 여부 — {@link PlatformDbFeature#VECTOR_SEARCH}
 * @param trigramSearch 트라이그램(키워드) 검색 지원 여부 — {@link PlatformDbFeature#TRIGRAM_SEARCH}
 */
public record PlatformDbCapabilitiesResponse(boolean vectorSearch, boolean trigramSearch) {}
