package com.smartfirehub.analytics.dto;

import jakarta.validation.constraints.Size;

public record UpdateSavedQueryRequest(
    @Size(max = 200) String name,
    String description,
    String sqlText,
    Long datasetId,
    @Size(max = 100) String folder,
    Boolean isShared) {}
