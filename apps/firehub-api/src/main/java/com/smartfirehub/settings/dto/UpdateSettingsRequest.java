package com.smartfirehub.settings.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.Map;

public record UpdateSettingsRequest(
        @NotEmpty(message = "설정 항목이 비어있습니다")
        Map<String, String> settings
) {}
