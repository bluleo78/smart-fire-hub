package com.smartfirehub.proactive.dto;

import java.util.List;
import java.util.Map;

/**
 * 목표 기반 리포트 양식 섹션 생성 응답(WD-455). 섹션은 저장 때와 같은 검사(WD-298)를 통과한 것만 돌려준다.
 *
 * @param sections 섹션 목록 — 각 원소는 key·type·label·instruction·required 만 담는다(웹 TemplateSection 과 같은
 *     모양)
 */
public record GenerateReportTemplateResponse(List<Map<String, Object>> sections) {}
