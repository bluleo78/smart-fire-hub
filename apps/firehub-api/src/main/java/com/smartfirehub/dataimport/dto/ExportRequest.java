package com.smartfirehub.dataimport.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

public record ExportRequest(
    @NotNull(message = "내보내기 형식은 필수입니다.") ExportFormat format,
    List<String> columns,
    String search,
    String geometryColumn) {}
