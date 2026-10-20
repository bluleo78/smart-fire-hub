package com.smartfirehub.dataset.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.time.LocalDateTime;
import java.util.Map;

public record RowDataResponse(
    Long id,
    /** 행 값 — JS double 로 깎이는 소수·큰 정수는 JSON 문자열로 나간다(WD-101, DatasetRowValuesSerializer). */
    @JsonSerialize(using = DatasetRowValuesSerializer.class) Map<String, Object> data,
    LocalDateTime createdAt) {}
