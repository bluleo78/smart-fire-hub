package com.smartfirehub.dataset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CloneDatasetRequest(
    @NotBlank String name,
    @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_]*$") String tableName,
    String description,
    boolean includeData,
    boolean includeTags) {}
