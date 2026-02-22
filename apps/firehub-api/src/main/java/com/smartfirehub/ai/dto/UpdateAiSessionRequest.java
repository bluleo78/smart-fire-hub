package com.smartfirehub.ai.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateAiSessionRequest(@NotBlank(message = "Title is required") String title) {}
