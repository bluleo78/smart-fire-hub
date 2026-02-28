package com.smartfirehub.analytics.dto;

import java.time.LocalDateTime;

public record SavedQueryListResponse(
    Long id,
    String name,
    String description,
    String folder,
    Long datasetId,
    String datasetName,
    boolean isShared,
    String createdByName,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    long chartCount) {}
