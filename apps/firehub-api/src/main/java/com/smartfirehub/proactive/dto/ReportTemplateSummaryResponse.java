package com.smartfirehub.proactive.dto;

import java.time.LocalDateTime;

/**
 * 리포트 양식 목록(list) 조회 전용 요약 응답.
 *
 * <p>{@link ReportTemplateResponse}와 달리 {@code sections}(섹션별 key/label/type/instruction 전체
 * JSONB)와 {@code style}을 포함하지 않는다. 목록 조회는 이름·설명·섹션 개수만 필요한 경우가
 * 대부분인데도 매번 전체 구조를 반환하면 템플릿이 늘어날수록 payload가 선형으로 커진다(#632). 상세
 * 구조가 필요하면 단건 조회({@code GET /api/v1/proactive/templates/{id}})를 사용한다.
 */
public record ReportTemplateSummaryResponse(
    Long id,
    String name,
    String description,
    int sectionCount,
    Long userId,
    boolean builtin,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {}
