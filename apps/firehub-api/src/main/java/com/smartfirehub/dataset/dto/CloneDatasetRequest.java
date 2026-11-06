package com.smartfirehub.dataset.dto;

import com.smartfirehub.global.validation.DescriptionLimits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CloneDatasetRequest(
    @NotBlank String name,
    @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_]*$") String tableName,
    @Size(max = DescriptionLimits.MAX_LENGTH, message = DescriptionLimits.DATASET_TOO_LONG)
        String description,
    boolean includeData,
    boolean includeTags) {}
