package com.smartfirehub.dataset.rowsearch;

import java.time.OffsetDateTime;

/**
 * dataset_search_index 한 행. status: IDLE | SYNCING | ERROR
 *
 * <p>{@code generation} 은 색인 세대(1 부터, swap 재구축마다 1 증가 — WD-722). Qdrant 색인이 이 세대의 점만 검색한다.
 */
public record SearchIndexState(
    long datasetId,
    String status,
    String configHash,
    String embeddingModel,
    Integer embeddingDim,
    Long sourceTableOid,
    OffsetDateTime syncCursor,
    OffsetDateTime passCursor,
    Long resumeAfterId,
    long indexedRows,
    long totalRows,
    int consecutiveFailures,
    OffsetDateTime lastSyncedAt,
    String lastError,
    long generation) {}
