package com.smartfirehub.dataimport.dto;

import java.util.List;
import java.util.Map;

public record QueryResultExportRequest(
    List<String> columnNames, List<Map<String, Object>> rows, ExportFormat format) {}
