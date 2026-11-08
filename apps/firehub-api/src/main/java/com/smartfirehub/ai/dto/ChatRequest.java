package com.smartfirehub.ai.dto;

import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * AI 채팅 요청 본문.
 *
 * <p>message 는 상한을 둔다(WD-550) — 상한이 없으면 수 MB 메시지가 그대로 ai-agent 로 넘어가 거기서 본문 한도 오류가 나거나 모델 컨텍스트를
 * 낭비한다. 컨트롤러 경계(@Valid)에서만 검증되며, 내부 호출측(Slack·배치 등)은 이 레코드를 거치지 않는다.
 *
 * <p>navigationContext·screenContext 도 같은 이유로 상한을 둔다. 상한은 문자 수라 직렬화 바이트는 더 크다 — ai-agent 채팅 본문 한도의
 * 최악값 계산은 ai-agent {@code src/http/json-body.ts} 의 CHAT_BODY_LIMIT 주석 참고(상한을 바꾸면 그 값도 다시 계산).
 */
public record ChatRequest(
    @Size(max = ChatRequest.MAX_MESSAGE_LENGTH, message = ChatRequest.MESSAGE_TOO_LONG)
        String message,
    String sessionId,
    List<Long> fileIds,
    String agentType,
    @Size(
            max = ChatRequest.MAX_NAVIGATION_CONTEXT_LENGTH,
            message = ChatRequest.NAVIGATION_CONTEXT_TOO_LONG)
        String navigationContext,
    @Size(
            max = ChatRequest.MAX_SCREEN_CONTEXT_LENGTH,
            message = ChatRequest.SCREEN_CONTEXT_TOO_LONG)
        String screenContext) {

  /** 채팅 메시지 최대 길이(문자 수). */
  public static final int MAX_MESSAGE_LENGTH = 50_000;

  /** 메시지가 상한을 넘었을 때 필드 오류 메시지. */
  public static final String MESSAGE_TOO_LONG = "메시지는 50,000자 이하로 입력하세요.";

  /** 화면 이동 컨텍스트 최대 길이(문자 수). */
  public static final int MAX_NAVIGATION_CONTEXT_LENGTH = 10_000;

  /** navigationContext 가 상한을 넘었을 때 필드 오류 메시지. */
  public static final String NAVIGATION_CONTEXT_TOO_LONG = "화면 이동 정보는 10,000자 이하여야 합니다.";

  /** 화면 컨텍스트 최대 길이(문자 수). */
  public static final int MAX_SCREEN_CONTEXT_LENGTH = 100_000;

  /** screenContext 가 상한을 넘었을 때 필드 오류 메시지. */
  public static final String SCREEN_CONTEXT_TOO_LONG = "화면 정보는 100,000자 이하여야 합니다.";
}
