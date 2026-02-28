package com.smartfirehub.analytics.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateSavedQueryRequest(
    @NotBlank @Size(max = 200) String name,
    String description,
    @NotBlank String sqlText,
    Long datasetId,
    @Size(max = 100) String folder,
    boolean isShared) {}
