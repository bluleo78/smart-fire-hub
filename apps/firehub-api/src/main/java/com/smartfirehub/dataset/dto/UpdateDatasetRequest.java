package com.smartfirehub.dataset.dto;

import com.smartfirehub.global.validation.DescriptionLimits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateDatasetRequest(
    @NotBlank String name,
    @Size(max = DescriptionLimits.MAX_LENGTH, message = DescriptionLimits.DATASET_TOO_LONG)
        String description,
    Long categoryId) {}
