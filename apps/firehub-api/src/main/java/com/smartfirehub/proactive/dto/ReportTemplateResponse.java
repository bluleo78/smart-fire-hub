package com.smartfirehub.proactive.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record ReportTemplateResponse(
    Long id,
    String name,
    String description,
    List<Map<String, Object>> sections,
    Long userId,
    boolean builtin,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {}
