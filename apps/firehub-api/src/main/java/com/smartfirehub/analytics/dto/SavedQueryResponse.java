package com.smartfirehub.analytics.dto;

import java.time.LocalDateTime;

public record SavedQueryResponse(
    Long id,
    String name,
    String description,
    String sqlText,
    Long datasetId,
    String datasetName,
    String folder,
    boolean isShared,
    String createdByName,
    Long createdBy,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    long chartCount) {}
