package com.smartfirehub.dataimport.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

public record QueryResultExportRequest(
    List<String> columnNames,
    List<Map<String, Object>> rows,
    @NotNull(message = "내보내기 형식은 필수입니다.") ExportFormat format) {}
