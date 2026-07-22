package com.smartfirehub.graphingest.dto;

/** GraphRAG 적재 이력 기록 요청(ai-agent가 적재 완료 후 호출). */
public record RecordGraphIngestRequest(
    int schemaVersionAtIngest,
    int chunkCount,
    int nodeCount,
    int edgeCount,
    int extractionFailures,
    String status) {}
