package com.smartfirehub.dataset.dto;

import java.time.LocalDateTime;
import java.util.List;

public record DatasetDetailResponse(
    Long id,
    String name,
    String tableName,
    String description,
    CategoryResponse category,
    String datasetType,
    String createdBy,
    List<DatasetColumnResponse> columns,
    long rowCount,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    String updatedBy
) {}
