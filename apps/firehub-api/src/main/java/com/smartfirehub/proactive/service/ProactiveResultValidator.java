package com.smartfirehub.proactive.service;

import com.smartfirehub.proactive.dto.ProactiveResult;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * AI 에이전트가 반환한 프로액티브 실행 결과가 "사용자에게 발송해도 되는 리포트"인지 검증한다.
 *
 * <p>왜 필요한가 (이슈 #350): CLI/SDK 에이전트는 인증 만료·크레딧 소진 같은 실패를 SSE {@code error}
 * 이벤트가 아니라 일반 assistant 텍스트로 흘려보낸 뒤 정상 종료한다. 그러면 오류 원문이 그대로 리포트
 * 본문(sections[0].content)이 되고, 실행은 COMPLETED로 기록되며, CHAT/EMAIL 채널로 발송된다.
 * ai-agent 층에서 1차 차단하지만, 백엔드에서도 발송 직전에 한 번 더 막는 2중 방어를 둔다.
 *
 * <p>의도적으로 검사하지 <b>않는</b> 것: {@code htmlContent}/{@code summary}가 비었다는 사실 자체는
 * 실패 신호가 아니다. report-writer가 report.md만 생성한 경우 두 필드는 비어 있고 sections만 채워지는
 * 정상 리포트가 되기 때문이다. 이를 실패로 처리하면 멀쩡한 리포트가 FAILED가 되는 회귀가 생긴다.
 */
public final class ProactiveResultValidator {

  private ProactiveResultValidator() {}

  /** 에이전트 레벨 실패(인증/쿼터)를 나타내는 문구. ai-agent의 detectAgentFailure()와 동일 목록. */
  private static final List<String> AGENT_FAILURE_SIGNATURES =
      List.of(
          "failed to authenticate",
          // SDK가 잘못된 API 키에 대해 내는 문구: "Invalid API key · Fix external API key"
          "invalid api key",
          "invalid bearer token",
          "authentication_error",
          "api error: 401",
          "api error: 403",
          "api error: 429",
          "credit balance is too low",
          "oauth token has expired");

  /** 검증 실패 시 사용자에게 노출할 문구. 원문 오류·request_id는 절대 포함하지 않는다 (#313 원칙). */
  public static final String USER_FACING_FAILURE_MESSAGE =
      "AI 분석을 완료하지 못해 리포트를 생성하지 못했습니다. 관리 > 설정에서 AI 인증 정보를 확인해 주세요.";

  /**
   * 결과를 COMPLETED로 기록하고 발송해도 되는지 검증한다.
   *
   * @param result AI 에이전트 실행 결과
   * @return 거부 사유(로그·디버깅용 내부 문자열). 정상이면 {@link Optional#empty()}
   */
  public static Optional<String> findRejectionReason(ProactiveResult result) {
    if (result == null) {
      return Optional.of("result is null");
    }

    // 1) 실질 내용이 전혀 없는 결과 — 분석이 수행되지 않았다는 뜻
    if (!hasAnyContent(result)) {
      return Optional.of("empty result (no htmlContent, summary, or section content)");
    }

    // 2) 본문이 에이전트 실패 메시지로 시작하는 결과 — 오류 원문이 리포트로 둔갑한 경우.
    // startsWith로 맨 앞에서만 매칭한다: 리포트는 장애를 *서술*하면서 본문에 같은 문구를 인용할 수
    // 있으므로 contains로 검사하면 정상 리포트가 FAILED가 되는 회귀가 생긴다.
    String head = firstContent(result).toLowerCase(Locale.ROOT);
    for (String signature : AGENT_FAILURE_SIGNATURES) {
      if (head.startsWith(signature)) {
        return Optional.of("agent failure text in report body: " + signature);
      }
    }

    return Optional.empty();
  }

  /** htmlContent / summary / 임의 섹션 content 중 하나라도 실질 내용이 있는지 확인한다. */
  private static boolean hasAnyContent(ProactiveResult result) {
    if (isNotBlank(result.htmlContent()) || isNotBlank(result.summary())) {
      return true;
    }
    if (result.sections() != null) {
      for (ProactiveResult.Section section : result.sections()) {
        if (section != null && isNotBlank(section.content())) {
          return true;
        }
      }
    }
    return false;
  }

  /** 시그니처 검사 대상 본문을 고른다. 사용자에게 실제로 노출되는 요약 경로를 우선한다. */
  private static String firstContent(ProactiveResult result) {
    String summary = result.effectiveSummary();
    if (isNotBlank(summary)) {
      return summary.trim();
    }
    return isNotBlank(result.htmlContent()) ? result.htmlContent().trim() : "";
  }

  private static boolean isNotBlank(String value) {
    return value != null && !value.isBlank();
  }
}
