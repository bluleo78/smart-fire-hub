package com.smartfirehub.pipeline.dto;

import java.util.Map;

public record UpdateTriggerRequest(
    String name,
    Boolean isEnabled,
    String description,
    Map<String, Object> config
) {}
