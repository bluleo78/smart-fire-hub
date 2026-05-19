package com.smartfirehub.dashboard.dto;

import java.time.LocalDateTime;

public record AttentionItemResponse(
    String type, // PIPELINE_FAILED, IMPORT_FAILED
    String severity, // CRITICAL, WARNING
    String title,
    String description,
    Long entityId, // pipeline_id or dataset_id
    String entityType, // PIPELINE, DATASET
    LocalDateTime occurredAt) {}
