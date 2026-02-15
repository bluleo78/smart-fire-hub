package com.smartfirehub.dataimport.dto;

import java.util.List;
import java.util.Map;

public record ImportPreviewResponse(
    List<String> fileHeaders,
    List<Map<String, String>> sampleRows,
    List<ColumnMappingDto> suggestedMappings,
    int totalRows
) {}
