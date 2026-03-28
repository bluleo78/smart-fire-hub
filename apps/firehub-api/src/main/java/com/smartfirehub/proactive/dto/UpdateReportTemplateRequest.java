package com.smartfirehub.proactive.dto;

import java.util.List;
import java.util.Map;

public record UpdateReportTemplateRequest(
    String name, String description, List<Map<String, Object>> sections) {}
