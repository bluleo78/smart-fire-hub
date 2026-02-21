package com.smartfirehub.pipeline.dto;

import java.util.Map;

public record CreateTriggerRequest(
    String name,
    TriggerType triggerType,
    String description,
    Map<String, Object> config
) {}
