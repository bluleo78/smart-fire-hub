package com.smartfirehub.dataset.storage;

/**
 * 현재 테넌트 저장소가 지원하는 기능 목록 응답(WD-80). 웹이 미지원 기능을 미리 숨기거나 안내하는 데 쓴다.
 *
 * @param backend 저장소 백엔드 이름("POSTGRESQL"·"TIBERO")
 * @param geometry GEOMETRY 컬럼 지원 여부
 * @param rowSearch 행 검색 지원 여부
 * @param maxVarcharLength VARCHAR 최대 길이(WD-636) — PG 10000, Tibero 4000. 웹 폼의 길이 입력 상한
 * @param defaultVarcharLength 길이 미지정 VARCHAR 의 실제 길이(WD-659) — PG 255, Tibero 4000. 웹 행 폼이 서버 값
 *     검사({@code ColumnPhysicalType.enforceLimitsStrict})와 같은 한도로 미리 막게 한다. 단일 출처는 {@link
 *     DataStoreBackend#defaultVarcharLength()}
 */
public record DataStoreCapabilitiesResponse(
    String backend,
    boolean geometry,
    boolean rowSearch,
    int maxVarcharLength,
    int defaultVarcharLength) {}
