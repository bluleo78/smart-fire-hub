package com.smartfirehub.dataset.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.util.List;
import java.util.Map;

public record SqlQueryResponse(
    String queryType,
    List<String> columns,
    /**
     * SELECT 결과 행 — JS double 로 깎이는 소수·큰 정수(배열 원소 포함)는 JSON 문자열로 나간다(WD-104,
     * DatasetRowValuesSerializer). 웹 SQL 탭은 셀을 String(value) 로 그리고, ai-agent 는 JSON 을 그대로 LLM 에
     * 넘긴다.
     */
    @JsonSerialize(contentUsing = DatasetRowValuesSerializer.class) List<Map<String, Object>> rows,
    int affectedRows,
    long executionTimeMs,
    String error) {}
