package com.smartfirehub.dataset.storage;

/**
 * 현재 테넌트 저장소가 지원하는 기능 목록 응답(WD-80). 웹이 미지원 기능을 미리 숨기거나 안내하는 데 쓴다.
 *
 * @param backend 저장소 백엔드 이름("POSTGRESQL"·"TIBERO")
 * @param geometry GEOMETRY 컬럼 지원 여부
 * @param rowSearch 행 검색 지원 여부
 */
public record DataStoreCapabilitiesResponse(String backend, boolean geometry, boolean rowSearch) {}
