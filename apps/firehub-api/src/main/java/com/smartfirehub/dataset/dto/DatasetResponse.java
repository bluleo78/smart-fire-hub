package com.smartfirehub.dataset.dto;

import java.time.LocalDateTime;
import java.util.List;

public record DatasetResponse(
    Long id,
    String name,
    String tableName,
    String description,
    CategoryResponse category,
    String datasetType,
    LocalDateTime createdAt,
    boolean isFavorite,
    List<String> tags,
    String status,
    String statusNote,
    String statusUpdatedBy,
    LocalDateTime statusUpdatedAt) {}
