package com.smartfirehub.proactive.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

public record CreateReportTemplateRequest(
    @NotBlank String name,
    String description,
    @NotNull List<Map<String, Object>> sections,
    String style) {}
