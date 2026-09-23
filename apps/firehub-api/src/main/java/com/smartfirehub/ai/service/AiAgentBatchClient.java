package com.smartfirehub.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;

/**
 * 응답 전체를 한 번에 받아야 하는 경로(Slack 인바운드)용 ai-agent 채팅 클라이언트(이슈 #709).
 *
 * <p>ai-agent 에는 비스트리밍 채팅 엔드포인트도, 세션 생성 엔드포인트도 없다. 그래서 웹 채팅과
 * 같은 SSE 스트림({@link AiAgentProxyService#chatEvents})을 받아 {@code text} 를 이어 붙이고(웹
 * 화면과 같은 방식), 빈 {@code sessionId} 로 부른 새 세션의 ID 는 {@code init}/{@code done} 에서
 * 얻는다.
 */
@Component
public class AiAgentBatchClient {

  /** 웹 채팅 프록시와 같은 상한 — 에이전트 턴이 도구를 여러 번 부르면 수 분이 걸린다. */
  static final Duration TIMEOUT = Duration.ofMinutes(5);

  private final AiAgentProxyService proxy;
  private final ObjectMapper objectMapper;

  public AiAgentBatchClient(AiAgentProxyService proxy, ObjectMapper objectMapper) {
    this.proxy = proxy;
    this.objectMapper = objectMapper;
  }

  /**
   * 완료된 한 턴의 결과.
   *
   * @param sessionId ai-agent 세션 ID — 새 세션이었다면 이번에 발급된 값
   * @param text 이어 붙인 응답 텍스트(비어 있을 수 있다)
   */
  public record ChatReply(String sessionId, String text) {}

  /** ai-agent 가 {@code error} 이벤트로 턴을 끝냈다. 메시지는 사용자에게 보여 줄 수 있는 문구다. */
  public static class AgentChatException extends RuntimeException {
    public AgentChatException(String message) {
      super(message);
    }
  }

  /**
   * 채팅 한 턴을 보내고 {@code done} 까지 기다린다.
   *
   * <p>이벤트는 도착하는 대로 접는다 — 목록에 모았다가 접으면 버릴 {@code tool_result}(조회 결과
   * 등, 클 수 있다)까지 턴이 끝날 때까지 메모리에 남는다. {@code done}/{@code error} 에서 바로
   * 구독을 끊어 스레드를 돌려준다.
   *
   * @param body {@link AiChatRequestBuilder#prepare} 가 만든 요청 바디
   * @throws AgentChatException ai-agent 가 {@code error} 이벤트를 보낸 경우
   * @throws IllegalStateException {@code done} 없이 스트림이 끝난 경우 — 일부만 받은 답을 완성된
   *     답처럼 보내지 않는다
   */
  public ChatReply chat(Map<String, Object> body) {
    Turn turn = new Turn();
    proxy.chatEvents(body).takeUntil(turn::accept).then().block(TIMEOUT);
    if (!turn.done) {
      throw new IllegalStateException("ai-agent 스트림이 done 없이 끝났습니다");
    }
    return new ChatReply(turn.sessionId, turn.text.toString());
  }

  /** 한 턴의 누적 상태. 구독 하나에서만 순차로 불리므로 동기화가 필요 없다. */
  private final class Turn {
    private final StringBuilder text = new StringBuilder();
    private String sessionId;
    private boolean done;

    /** 이벤트 하나를 반영한다. 턴이 끝났으면 {@code true} — {@code takeUntil} 이 구독을 끊는다. */
    boolean accept(ServerSentEvent<String> sse) {
      String data = sse.data();
      if (data == null || data.isEmpty()) return false;
      JsonNode node;
      try {
        node = objectMapper.readTree(data);
      } catch (IOException e) {
        throw new IllegalStateException("ai-agent SSE 데이터를 해석하지 못했습니다", e);
      }
      switch (node.path("type").asText()) {
        case "init" -> sessionId = node.path("sessionId").asText(sessionId);
        case "text" -> text.append(node.path("content").asText(""));
        case "error" -> {
          String message = node.path("message").asText("");
          throw new AgentChatException(
              message.isBlank() ? "AI 에이전트 처리 중 오류가 발생했습니다." : message);
        }
        case "done" -> {
          // CLI 경로의 done 에는 sessionId 가 없을 수 있다 — 그때는 init 의 값을 쓴다
          sessionId = node.path("sessionId").asText(sessionId);
          done = true;
          return true;
        }
        default -> {
          // tool_use / tool_result / turn / ping — 응답 텍스트와 무관하다
        }
      }
      return false;
    }
  }
}
