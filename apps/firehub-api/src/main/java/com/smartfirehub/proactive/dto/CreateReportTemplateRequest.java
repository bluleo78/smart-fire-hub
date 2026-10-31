package com.smartfirehub.proactive.dto;

import com.smartfirehub.global.validation.MaxJsonSize;
import com.smartfirehub.proactive.validation.ReportSectionLimits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * 리포트 양식 생성 요청.
 *
 * <p>{@code sections} 는 하위 섹션 포함 100개, 글자 항목마다 10,000자까지만 받는다(WD-425 — {@link
 * ReportSectionLimits}). 섹션 맵에는 정해진 필드 외의 값(배열·객체)도 들어올 수 있어, 전체 직렬화 크기에도 상한을 둔다({@link
 * #SECTIONS_MAX_BYTES}).
 */
public record CreateReportTemplateRequest(
    @NotBlank String name,
    String description,
    @NotNull
        @ReportSectionLimits
        @MaxJsonSize(
            maxBytes = CreateReportTemplateRequest.SECTIONS_MAX_BYTES,
            message = CreateReportTemplateRequest.SECTIONS_TOO_LARGE_MESSAGE)
        List<Map<String, Object>> sections,
    String style) {

  /**
   * 섹션 전체 직렬화 크기 상한 — 512KB(UTF-8 바이트, WD-425). 섹션 수·글자 항목 검사는 정해진 글자 필드만 보므로, 한 섹션에 큰 배열·객체를 넣는
   * 우회를 이 상한이 막는다. 실제 양식은 수 KB 이고, 섹션 100개에 넉넉한 지침을 달아도 수백 KB 를 넘지 않는다.
   */
  public static final long SECTIONS_MAX_BYTES = 512 * 1024;

  /** 섹션 전체 상한을 넘었을 때의 안내 — 수정 요청과 같은 문구를 쓴다. */
  public static final String SECTIONS_TOO_LARGE_MESSAGE = "리포트 섹션 전체가 너무 큽니다(최대 512KB).";
}
