package com.smartfirehub.dataset.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/** 행 일괄 추가 요청. rows 는 컬렉션이라 기본 @Size 문구(「N자 이하」, 문자열 기준)가 어색하므로 개수 기준 한국어 문구를 명시한다(WD-570). */
public record BatchRowDataRequest(
    @NotNull @Size(min = 1, max = 100, message = "행은 1개 이상 100개 이하로 보내야 합니다.")
        List<Map<String, Object>> rows) {}
