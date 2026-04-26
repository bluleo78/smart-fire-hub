package com.smartfirehub.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Slack inbound 등 batch(비-SSE) AI 응답이 필요한 경로용 ai-agent 클라이언트.
 *
 * <p>기존 {@code AiAgentProxyService}는 SSE 스트리밍으로 브라우저 클라이언트에 중계한다. 이 클래스는 단일 응답을 동기 대기(timeout:
 * session 10초, chat 60초)하여 반환한다.
 *
 * <p>ai-agent API 계약 가정:
 *
 * <ul>
 *   <li>POST /agent/session — body: {userId, title} → {sessionId}
 *   <li>POST /agent/chat — body: {sessionId, userId, message, stream:false} → {content}
 * </ul>
 *
 * 실제 ai-agent가 이 엔드포인트/스키마를 지원하지 않는 경우 향후 수정 필요. 통합 테스트에서는 이 클라이언트를 Mockito로 mock하여 검증한다.
 */
@Component
public class AiAgentBatchClient {

  private final WebClient webClient;

  public AiAgentBatchClient(
      @Value("${ai.agent.base-url:http://localhost:3001}") String baseUrl,
      WebClient.Builder builder) {
    this.webClient = builder.baseUrl(baseUrl).build();
  }

  /**
   * ai-agent 서비스에 새 세션 생성 요청.
   *
   * @param userId Smart Fire Hub 사용자 ID
   * @param title 세션 제목 (예: "Slack 대화 2026-04-19")
   * @return ai-agent 발급 session ID, 응답 파싱 실패 시 null
   */
  public String createSession(long userId, String title) {
    JsonNode resp =
        webClient
            .post()
            .uri("/agent/session")
            .bodyValue(Map.of("userId", userId, "title", title))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .block(Duration.ofSeconds(10));
    return resp != null ? resp.path("sessionId").asText(null) : null;
  }

  /**
   * 기존 ai-agent 세션에 사용자 메시지 전송 → AI 응답 텍스트 반환 (non-streaming).
   *
   * <p>stream=false를 body에 포함하여 단일 JSON 응답을 요청한다. ai-agent가 항상 SSE 스트리밍만 지원하는 경우 향후 어댑터 교체 필요.
   *
   * @param agentSessionId ai-agent 세션 ID (createSession 반환값)
   * @param userId Smart Fire Hub 사용자 ID
   * @param text 사용자 메시지 텍스트
   * @return AI 응답 content 텍스트, 파싱 실패 시 null
   */
  public String chat(String agentSessionId, long userId, String text) {
    JsonNode resp =
        webClient
            .post()
            .uri("/agent/chat")
            .bodyValue(
                Map.of(
                    "sessionId", agentSessionId,
                    "userId", userId,
                    "message", text,
                    "stream", false))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .block(Duration.ofSeconds(60));
    return resp != null ? resp.path("content").asText(null) : null;
  }
}
