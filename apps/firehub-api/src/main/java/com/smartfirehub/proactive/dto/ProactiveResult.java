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
    String summary) {

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

  /** 리포트 섹션 단위. key/label/content/type/data 구성. */
  public record Section(String key, String label, String content, String type, Object data) {}

  /** AI 에이전트 토큰 사용량 통계. */
  public record Usage(int inputTokens, int outputTokens, int totalTokens) {}
}
