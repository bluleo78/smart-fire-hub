package com.smartfirehub.dataset.dto;

import java.time.LocalDateTime;

public record DatasetResponse(
    Long id,
    String name,
    String tableName,
    String description,
    CategoryResponse category,
    String datasetType,
    LocalDateTime createdAt
) {}
