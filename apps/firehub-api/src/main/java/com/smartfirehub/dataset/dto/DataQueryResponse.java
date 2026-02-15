package com.smartfirehub.dataset.dto;

import java.util.List;
import java.util.Map;

public record DataQueryResponse(
    List<DatasetColumnResponse> columns,
    List<Map<String, Object>> rows,
    int page,
    int size,
    long totalElements,
    int totalPages
) {}
