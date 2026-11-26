package com.smartfirehub.proactive.dto;

import com.smartfirehub.global.validation.DescriptionLimits;
import com.smartfirehub.global.validation.MaxJsonSize;
import com.smartfirehub.global.validation.TextRules;
import com.smartfirehub.proactive.validation.ReportSectionLimits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/**
 * 리포트 양식 생성 요청.
 *
 * <p>{@code sections} 는 하위 섹션 포함 100개, 글자 항목마다 10,000자까지만 받는다(WD-425 — {@link
 * ReportSectionLimits}). 섹션 맵에는 정해진 필드 외의 값(배열·객체)도 들어올 수 있어, 전체 직렬화 크기에도 상한을 둔다({@link
 * #SECTIONS_MAX_BYTES}).
 *
 * <p>name 은 DB 한도(varchar(200))를 넘으면 500 이 났다 — 400 으로 이유를 알린다(WD-430). 비었거나 공백만 있으면 영어 기본 문구("must
 * not be blank")가 화면에 그대로 보였다 — 수정 요청과 같은 한국어 문구를 쓴다(WD-501).
 */
public record CreateReportTemplateRequest(
    // WD-1094: @NotBlank 는 제로폭 문자(U+200B 등 서식·제어·결합·채움 문자)만인 이름을 못 거른다 — 이런 이름은 strip 으로도
    // 안 지워져 화면엔 빈 이름으로 보이므로, 보이는 글자가 하나도 없는 이름을 HAS_TEXT 로 막는다(문구는 같게 둔다)
    @NotBlank(message = "템플릿 이름은 필수입니다.")
        @Size(max = 200, message = "이름은 200자 이내여야 합니다.")
        @Pattern(regexp = TextRules.HAS_TEXT, message = "템플릿 이름은 필수입니다.")
        String name,
    @Size(max = DescriptionLimits.MAX_LENGTH, message = DescriptionLimits.REPORT_TEMPLATE_TOO_LONG)
        String description,
    @NotNull
        @ReportSectionLimits
        @MaxJsonSize(
            maxBytes = CreateReportTemplateRequest.SECTIONS_MAX_BYTES,
            message = CreateReportTemplateRequest.SECTIONS_TOO_LARGE_MESSAGE)
        List<Map<String, Object>> sections,
    String style) {

  /** 이름 앞뒤의 유니코드 공백(전각·NBSP 포함)을 걷어, @Size 검사와 중복 검사·저장이 같은 값을 보게 한다(WD-1094). null 은 null. */
  public CreateReportTemplateRequest {
    name = TextRules.strip(name);
  }

  /**
   * 섹션 전체 직렬화 크기 상한 — 512KB(UTF-8 바이트, WD-425). 섹션 수·글자 항목 검사는 정해진 글자 필드만 보므로, 한 섹션에 큰 배열·객체를 넣는
   * 우회를 이 상한이 막는다. 실제 양식은 수 KB 이고, 섹션 100개에 넉넉한 지침을 달아도 수백 KB 를 넘지 않는다.
   */
  public static final long SECTIONS_MAX_BYTES = 512 * 1024;

  /** 섹션 전체 상한을 넘었을 때의 안내 — 수정 요청과 같은 문구를 쓴다. */
  public static final String SECTIONS_TOO_LARGE_MESSAGE = "리포트 섹션 전체가 너무 큽니다(최대 512KB).";
}
