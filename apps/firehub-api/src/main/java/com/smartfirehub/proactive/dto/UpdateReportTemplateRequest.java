package com.smartfirehub.proactive.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

/** 리포트 템플릿 수정 요청 DTO. name 필드에 @NotBlank 검증을 추가하여 빈 이름으로 저장되는 것을 방지한다 (이슈 #200). */
public record UpdateReportTemplateRequest(
    @NotBlank(message = "템플릿 이름은 필수입니다.") String name,
    String description,
    List<Map<String, Object>> sections,
    String style) {}
