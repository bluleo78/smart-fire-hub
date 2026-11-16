package com.smartfirehub.proactive.dto;

import java.util.List;

/**
 * AI 에이전트가 반환하는 프로액티브 스마트작업 실행 결과 DTO.
 *
 * <p>AI 에이전트는 이제 htmlContent(HTML 리포트 전문)와 summary(요약 텍스트)를 추가로 반환한다. sections/title은 하위 호환을 위해
 * 유지한다.
 */
public record ProactiveResult(
    String title,
    List<Section> sections,
    Usage usage,
    /** AI 에이전트가 생성한 HTML 리포트 전문 (수십 KB일 수 있음). null이면 기존 sections 기반 렌더링 사용. */
    String htmlContent,
    /** 리포트 요약 텍스트. 채팅 메시지 content 및 이메일 본문 미리보기에 사용. */
    String summary,
    /** 리포트 수치의 출처 검증 결과(WD-767). 이전 응답처럼 필드가 없으면 null이며, 저장·알림 모두 null을 표식 없음으로 취급한다. */
    NumberCheck numberCheck) {

  /** 알림 요약 앞에 두는 출처 미확인 수치 경고 문구. {n}은 개수. */
  private static final String UNVERIFIED_NOTICE = "⚠ 출처를 확인하지 못한 수치 %d개가 있습니다 — 실행 결과 화면에서 확인하세요.";

  /** 유효한 제목을 반환한다. title이 비어 있으면 fallback(잡 이름 등)을 반환. */
  public String effectiveTitle(String fallback) {
    return title != null && !title.isBlank() ? title : fallback;
  }

  /**
   * 유효한 요약을 반환한다. summary가 있으면 사용하고, 없으면 첫 번째 섹션의 content를 요약으로 대신 사용한다. 채팅 메시지나 이메일 미리보기에서 짧은 텍스트가
   * 필요할 때 활용한다.
   */
  public String effectiveSummary() {
    if (summary != null && !summary.isBlank()) {
      return summary;
    }
    // summary가 없으면 첫 번째 섹션의 content를 대체 요약으로 반환
    if (sections != null && !sections.isEmpty()) {
      String firstContent = sections.get(0).content();
      if (firstContent != null && !firstContent.isBlank()) {
        return firstContent;
      }
    }
    return "";
  }

  /**
   * 알림(채팅·이메일·알림 payload)에 쓰는 요약. 출처 미확인 수치가 있으면 경고 한 줄을 요약 **앞**에 두고 빈 줄 뒤에 effectiveSummary()를
   * 잇는다 — 카카오·슬랙 등 채널이 긴 본문을 잘라도 경고가 남게 하려는 것. 요약이 비었거나 공백이면 경고 줄만 돌려준다. 검증기·PDF는 원문 요약이 필요하므로
   * effectiveSummary()를 그대로 쓰고, 알림 경로만 이 메서드를 쓴다.
   */
  public String notificationSummary() {
    // 검증하지 않은 실행(checked=false)과 음수 total 은 표식 없음으로 본다.
    String base = effectiveSummary();
    if (numberCheck == null || !numberCheck.checked() || numberCheck.unverifiedTotal() <= 0) {
      return base;
    }
    String notice = String.format(UNVERIFIED_NOTICE, numberCheck.unverifiedTotal());
    return base.isBlank() ? notice : notice + "\n\n" + base;
  }

  /** 리포트 섹션 단위. key/label/content/type/data 구성. */
  public record Section(String key, String label, String content, String type, Object data) {}

  /** AI 에이전트 토큰 사용량 통계. */
  public record Usage(int inputTokens, int outputTokens, int totalTokens) {}

  /**
   * 수치 출처 검증 결과. checked=false면 조회가 없어 검증하지 않은 실행, unverified는 최대 20개, unverifiedTotal은 잘리기 전 전체
   * 개수.
   */
  public record NumberCheck(
      boolean checked, List<UnverifiedNumber> unverified, int unverifiedTotal) {}

  /** 조회 결과에서 찾지 못한 수치와 그 주변 문맥. */
  public record UnverifiedNumber(String text, String context) {}
}
