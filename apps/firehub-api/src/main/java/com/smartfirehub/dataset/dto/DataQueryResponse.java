package com.smartfirehub.dataset.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.util.List;
import java.util.Map;

public record DataQueryResponse(
    List<DatasetColumnResponse> columns,
    /** 행 값 — JS double 로 깎이는 소수·큰 정수는 JSON 문자열로 나간다(WD-101, DatasetRowValuesSerializer). */
    @JsonSerialize(contentUsing = DatasetRowValuesSerializer.class) List<Map<String, Object>> rows,
    int page,
    int size,
    long totalElements,
    int totalPages) {}
