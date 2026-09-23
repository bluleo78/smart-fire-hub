package com.smartfirehub.dataset.rowsearch;

import java.time.OffsetDateTime;

/** dataset_search_index 한 행. status: IDLE | SYNCING | ERROR */
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
    String lastError) {}
