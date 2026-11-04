package com.smartfirehub.dataset.dto;

/**
 * 데이터셋 컬럼 메타({@code dataset_column}) 한 행의 보관본 — 실패한 TEMP 재생성을 되돌릴 때(WD-238) 행을 id 까지 그대로 다시 넣기 위해
 * 모든 열을 담는다.
 *
 * <p>응답 DTO({@link DatasetColumnResponse})는 일부 열만 담아 검색 대상 설정({@code isSearchable})·테넌트 id 를 잃는다.
 * 예전에는 jOOQ {@code Record} 를 그대로 보관했는데, 서비스가 jOOQ 타입에 기대지 않도록 도메인 값으로 바꿨다(WD-485). 열이 늘면 이 레코드와
 * {@code DatasetColumnRepository} 의 보관·복원 매핑에 함께 더해야 한다.
 */
public record DatasetColumnSnapshot(
    Long id,
    Long datasetId,
    String columnName,
    String displayName,
    String dataType,
    Boolean isNullable,
    Boolean isIndexed,
    String description,
    Integer columnOrder,
    Integer maxLength,
    Boolean isPrimaryKey,
    Long tenantId,
    Boolean isSearchable) {}
