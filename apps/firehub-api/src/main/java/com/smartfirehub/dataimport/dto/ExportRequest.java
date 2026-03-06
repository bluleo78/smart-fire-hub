package com.smartfirehub.dataimport.dto;

import java.util.List;

public record ExportRequest(
    ExportFormat format, List<String> columns, String search, String geometryColumn) {}
