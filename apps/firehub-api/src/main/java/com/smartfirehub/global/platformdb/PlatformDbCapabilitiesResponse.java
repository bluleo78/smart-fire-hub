package com.smartfirehub.global.platformdb;

/**
 * 플랫폼 DB 가 지원하는 선택 기능 목록 응답(WD-495). 웹·ai-agent 가 꺼진 검색 기능을 미리 숨기거나 안내하는 데 쓴다.
 *
 * @param vectorSearch 벡터(의미) 검색 지원 여부 — {@link PlatformDbFeature#VECTOR_SEARCH}
 * @param trigramSearch 트라이그램(키워드) 검색 지원 여부 — {@link PlatformDbFeature#TRIGRAM_SEARCH}
 * @param vectorStoreAvailable 벡터 저장소가 지금 응답하는가(ADR 0002 결정 3·11, WD-724). 앞의 두 값은 「설치본이 지원하는가」라 바뀌지
 *     않고, 이 값은 별도 저장소(Qdrant)가 잠깐 내려가 있을 때만 거짓이 된다. 거짓이면 검색은 빈 목록을 돌려주므로 화면·에이전트가 「저장소 일시 중단」을
 *     안내한다. 플랫폼 DB 안에 두는 설치본은 늘 참
 */
public record PlatformDbCapabilitiesResponse(
    boolean vectorSearch, boolean trigramSearch, boolean vectorStoreAvailable) {}
