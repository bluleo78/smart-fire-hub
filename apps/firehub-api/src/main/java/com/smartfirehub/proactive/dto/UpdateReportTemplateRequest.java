package com.smartfirehub.proactive.dto;

import com.smartfirehub.global.validation.MaxJsonSize;
import com.smartfirehub.proactive.validation.ReportSectionLimits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 리포트 템플릿 수정 요청 DTO. name 필드에 @NotBlank 검증을 추가하여 빈 이름으로 저장되는 것을 방지한다 (이슈 #200).
 *
 * @param expectedUpdatedAt 편집을 시작할 때 읽은 양식의 {@code updatedAt}(WD-364 낙관적 잠금). 섹션이 전체 교체라 두 곳에서 고치면
 *     나중 저장이 먼저 저장을 통째로 되돌린다 — 지금 저장된 값과 다르면 409 EDIT_CONFLICT 로 거부한다. null 이면 비교하지 않는다(AI 도구
 *     update_report_template 등 기존 호출부, "내 변경으로 덮어쓰기").
 */
public record UpdateReportTemplateRequest(
    // DB 한도(varchar(200))를 넘으면 500 이 났다 — 400 으로 이유를 알린다(WD-430)
    @NotBlank(message = "템플릿 이름은 필수입니다.") @Size(max = 200, message = "이름은 200자 이내여야 합니다.")
        String name,
    String description,
    // 섹션 상한은 생성과 같다(WD-425 — ReportSectionLimits, CreateReportTemplateRequest.SECTIONS_MAX_BYTES)
    @ReportSectionLimits
        @MaxJsonSize(
            maxBytes = CreateReportTemplateRequest.SECTIONS_MAX_BYTES,
            message = CreateReportTemplateRequest.SECTIONS_TOO_LARGE_MESSAGE)
        List<Map<String, Object>> sections,
    String style,
    LocalDateTime expectedUpdatedAt) {

  /** 기준 버전 없이 만드는 기존 호출부용 — 비교하지 않는다. */
  public UpdateReportTemplateRequest(
      String name, String description, List<Map<String, Object>> sections, String style) {
    this(name, description, sections, style, null);
  }
}
