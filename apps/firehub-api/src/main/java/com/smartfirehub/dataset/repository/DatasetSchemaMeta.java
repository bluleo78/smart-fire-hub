package com.smartfirehub.dataset.repository;

import java.util.List;

/**
 * 스키마 정보(분석 편집기의 테이블·컬럼 목록) 조립에 쓰는 데이터셋 메타 한 건(WD-494).
 *
 * <p>왜 따로 두는가: 예전에는 {@code information_schema.columns}(데이터 저장소)와 {@code dataset}·{@code
 * dataset_column}(플랫폼 DB)을 한 SQL 로 조인했다. 두 DB 가 갈라지면 한 SQL 로 조인할 수 없어, 플랫폼 쪽 메타는 이 값으로 읽고 물리 컬럼은
 * 데이터 저장소에서 읽어 앱에서 합친다.
 *
 * @param datasetId 데이터셋 id
 * @param datasetName 데이터셋 이름
 * @param tableName 물리 테이블 이름(문서·파일형 데이터셋은 null)
 * @param storageType 저장 방식(TABLE/DOCUMENT)
 * @param columns 컬럼 메타 — {@code column_order} 순
 */
public record DatasetSchemaMeta(
    long datasetId,
    String datasetName,
    String tableName,
    String storageType,
    List<Column> columns) {

  /** 컬럼 메타 — 이름·데이터 타입·표시 이름. */
  public record Column(String columnName, String dataType, String displayName) {}
}
