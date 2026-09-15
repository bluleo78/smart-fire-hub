package com.smartfirehub.graphingest.dto;

import java.time.LocalDateTime;

/** GraphRAG 적재 이력 한 행(리포지토리 read 결과). */
public record GraphIngestRecord(
    Long id,
    long datasetId,
    LocalDateTime ingestedAt,
    int schemaVersionAtIngest,
    int chunkCount,
    int nodeCount,
    int edgeCount,
    int extractionFailures,
    String status) {

  /** stale 질의용 — 데이터셋별 최신 적재행 + 그 데이터셋이 실제 바인딩된 온톨로지의 현재 schema_version. */
  public record StaleRow(
      long datasetId, LocalDateTime latestIngestedAt, int schemaVersionAtIngest, int currentSchemaVersion) {}
}
