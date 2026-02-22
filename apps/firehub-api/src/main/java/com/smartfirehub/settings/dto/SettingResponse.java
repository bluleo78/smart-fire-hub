package com.smartfirehub.settings.dto;

import java.time.LocalDateTime;

public record SettingResponse(
    String key, String value, String description, LocalDateTime updatedAt) {}
