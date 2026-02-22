package com.smartfirehub.dataset.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateDatasetRequest(@NotBlank String name, String description, Long categoryId) {}
