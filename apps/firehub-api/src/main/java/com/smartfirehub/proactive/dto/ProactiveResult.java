package com.smartfirehub.proactive.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
    /**
     * WD-765: ai-agent 가 위임 경로(sdk·cli)에서 리포트 파일을 하나도 저장하지 못해 rawText 로 대신 응답했을 때 true. 이 결과를 거절하면
     * 「AI 인증 정보 확인」 대신 저장 실패 문구로 안내하는 근거다.
     *
     * <p>ai-agent→api 사이 내부 신호라 {@code @JsonIgnore} 로 Jackson 직렬화·역직렬화에서 뺀다 — 실행 결과(updateResult)로
     * 저장되거나 컨트롤러가 프론트로 내보내는 JSON 에 남지 않게 하려는 것이다. 값은 ProactiveAiClient 가 응답 Map 에서 직접 꺼내 생성자로
     * 넣으므로(Jackson 역직렬화가 아님) 실행기가 메모리상의 결과로 거절 문구를 고르는 데는 영향이 없다. 저장된 결과 행을 다시 읽으면 늘 false 다.
     */
    @JsonIgnore boolean reportFilesMissing) {

  /**
   * reportFilesMissing 이 없는(false) 결과 — WD-765 이전부터 쓰던 생성 경로(테스트·발송 채널 등)가 그대로 쓰도록 둔다. Jackson 은
   * 레코드의 정식 생성자를 쓰므로 역직렬화에는 영향이 없다.
   */
  public ProactiveResult(
      String title, List<Section> sections, Usage usage, String htmlContent, String summary) {
    this(title, sections, usage, htmlContent, summary, false);
  }

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
