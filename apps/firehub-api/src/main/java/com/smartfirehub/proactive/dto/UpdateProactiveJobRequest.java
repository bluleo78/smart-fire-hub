package com.smartfirehub.proactive.dto;

import java.util.Map;

public record UpdateProactiveJobRequest(
    String name,
    String prompt,
    Long templateId,
    String cronExpression,
    String timezone,
    Boolean enabled,
    Map<String, Object> config) {}
