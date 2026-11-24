package com.smartfirehub.analytics.dto;

import com.smartfirehub.global.validation.TextRules;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AnalyticsQueryRequest(
    // WD-1086: NBSP·Cf 만인 SQL 은 @NotBlank 를 지나 DB 까지 가 문법 오류(500)를 냈다 — HAS_TEXT 로 400 필드 오류로 막는다
    @NotBlank(message = TextRules.SQL_BLANK_MESSAGE)
        @Pattern(regexp = TextRules.HAS_TEXT, message = TextRules.SQL_BLANK_MESSAGE)
        String sql,
    @Min(1) @Max(10000) Integer maxRows,
    Boolean readOnly) {}
