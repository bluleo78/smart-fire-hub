package com.smartfirehub.analytics.dto;

import java.util.List;

/**
 * 분석 스키마 정보 응답.
 *
 * @param dialect 테넌트 저장소 방언({@code POSTGRESQL}·{@code TIBERO}) — 웹 편집기가 스니펫(LIMIT/ROWNUM)을 고르는 근거
 * @param schema 쿼리에서 테이블 앞에 붙일 스키마 이름(PG 는 {@code data_t{id}}, Tibero 는 소문자 {@code genia_t{id}})
 */
public record SchemaInfoResponse(
    List<SchemaInfoResponse.TableInfo> tables, String dialect, String schema) {

  public record TableInfo(
      String tableName,
      String datasetName,
      Long datasetId,
      List<SchemaInfoResponse.ColumnInfo> columns) {}

  public record ColumnInfo(String columnName, String dataType, String displayName) {}
}
