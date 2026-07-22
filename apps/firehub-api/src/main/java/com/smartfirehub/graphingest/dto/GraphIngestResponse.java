package com.smartfirehub.graphingest.dto;

/** GraphRAG 적재 이력 응답 — ingestedAt은 LocalDateTime.toString() 형태의 문자열. */
public record GraphIngestResponse(
    long id,
    long datasetId,
    String ingestedAt,
    int schemaVersionAtIngest,
    int chunkCount,
    int nodeCount,
    int edgeCount,
    int extractionFailures,
    String status) {}
