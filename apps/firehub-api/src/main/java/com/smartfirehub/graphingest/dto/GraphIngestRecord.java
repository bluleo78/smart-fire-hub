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

  /** stale 질의용 — 데이터셋별 최신 적재행(온톨로지 버전 드리프트 점검). */
  public record StaleRow(long datasetId, LocalDateTime latestIngestedAt, int schemaVersionAtIngest) {}
}
