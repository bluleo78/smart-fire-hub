package com.smartfirehub.proactive.dto;

import com.smartfirehub.global.validation.DescriptionLimits;
import com.smartfirehub.global.validation.MaxJsonSize;
import com.smartfirehub.global.validation.TextRules;
import com.smartfirehub.proactive.validation.ReportSectionLimits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
    // WD-1094: @NotBlank 는 제로폭 문자(U+200B 등 서식·제어·결합·채움 문자)만인 이름을 못 거른다 — 이런 이름은 strip 으로도
    // 안 지워져 화면엔 빈 이름으로 보이므로, 보이는 글자가 하나도 없는 이름을 HAS_TEXT 로 막는다(문구는 같게 둔다)
    @NotBlank(message = "템플릿 이름은 필수입니다.")
        @Size(max = 200, message = "이름은 200자 이내여야 합니다.")
        @Pattern(regexp = TextRules.HAS_TEXT, message = "템플릿 이름은 필수입니다.")
        String name,
    @Size(max = DescriptionLimits.MAX_LENGTH, message = DescriptionLimits.REPORT_TEMPLATE_TOO_LONG)
        String description,
    // 섹션 상한은 생성과 같다(WD-425 — ReportSectionLimits, CreateReportTemplateRequest.SECTIONS_MAX_BYTES)
    @ReportSectionLimits
        @MaxJsonSize(
            maxBytes = CreateReportTemplateRequest.SECTIONS_MAX_BYTES,
            message = CreateReportTemplateRequest.SECTIONS_TOO_LARGE_MESSAGE)
        List<Map<String, Object>> sections,
    String style,
    LocalDateTime expectedUpdatedAt) {

  /** 이름 앞뒤의 유니코드 공백을 걷는다(WD-1094) — 보조 생성자도 이 정식 생성자를 거치므로 항상 적용. null(바꾸지 않음)은 null. */
  public UpdateReportTemplateRequest {
    name = TextRules.strip(name);
  }

  /** 기준 버전 없이 만드는 기존 호출부용 — 비교하지 않는다. */
  public UpdateReportTemplateRequest(
      String name, String description, List<Map<String, Object>> sections, String style) {
    this(name, description, sections, style, null);
  }
}
