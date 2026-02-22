package com.smartfirehub.settings.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.Map;

public record UpdateSettingsRequest(
    @NotEmpty(message = "Settings map must not be empty") Map<String, String> settings) {}
