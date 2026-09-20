package com.smartfirehub.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.settings.model.AiCredential;
import com.smartfirehub.settings.service.AiCredentialService;
import com.smartfirehub.settings.service.SettingsService;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;

@Service
@Slf4j
public class AiAgentProxyService {

  private static final Duration TIMEOUT = Duration.ofMinutes(5);

  private final WebClient webClient;
  private final ObjectMapper objectMapper;
  private final SettingsService settingsService;
  private final AiCredentialService aiCredentialService;

  @Value("${agent.internal-token}")
  private String internalToken;

  public AiAgentProxyService(
      @Value("${agent.url}") String agentUrl,
      ObjectMapper objectMapper,
      SettingsService settingsService,
      AiCredentialService aiCredentialService) {
    HttpClient httpClient =
        HttpClient.create().responseTimeout(Duration.ofMinutes(5)).keepAlive(true);
    this.webClient =
        WebClient.builder()
            .baseUrl(agentUrl)
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
            .build();
    this.objectMapper = objectMapper;
    this.settingsService = settingsService;
    this.aiCredentialService = aiCredentialService;
  }

  private static int parseIntSafe(String value, int defaultValue) {
    if (value == null) return defaultValue;
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      log.warn("[AI Chat] Invalid int setting value '{}', using default {}", value, defaultValue);
      return defaultValue;
    }
  }

  private static double parseDoubleSafe(String value, double defaultValue) {
    if (value == null) return defaultValue;
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException e) {
      log.warn(
          "[AI Chat] Invalid double setting value '{}', using default {}", value, defaultValue);
      return defaultValue;
    }
  }

  /**
   * OAuth 토큰을 ai-agent 에 검증시킨다.
   *
   * <p><b>토큰은 호출부가 넘긴다 — 여기서 다시 해석하지 않는다.</b> 예전에는 이 메서드가
   * {@code aiCredentialService.resolve()} 를 스스로 한 번 더 불러 유형별로 토큰을 골랐는데,
   * 유일한 호출부인 두 {@code getAuthStatus} 컨트롤러가 <b>이미 같은 {@code resolve()} 로
   * 판정해 그 값을 손에 쥔 채</b> 이 메서드를 불렀다 — 요청 1건당 두 평면 SELECT + AES-GCM
   * 복호화가 정확히 두 번 돌았다. 어느 유형에서 무엇이 토큰인지(그리고 어떤 유형이 이 경로
   * 자체를 타면 안 되는지)를 정하는 것은 그 컨트롤러의 exhaustive switch 의 책임이다 —
   * 그 switch 는 {@code AiCredential} 에 변형이 늘면 컴파일 오류로 막히고,
   * {@code AiCredentialSwitchGuardTest} 가 {@code default} 탈출구까지 소스 텍스트로 금지한다.
   *
   * @param oauthToken 호출부가 자격증명에서 꺼낸 OAuth 토큰. 비어 있으면(빈/공백) ai-agent 를
   *     부르지 않고 즉시 {@code invalid} 를 돌려준다 — "미설정"을 네트워크 왕복 없이 답한다.
   */
  public String verifyCliToken(String oauthToken) {
    if (oauthToken.isBlank()) {
      return "{\"valid\":false}";
    }
    // JSON 인젝션 방지: 문자열 연결이 아닌 ObjectMapper로 안전하게 직렬화한다.
    // 백슬래시(\\), 따옴표("), 줄바꿈(\n), 탭(\t) 등 모든 JSON 특수문자가 자동 이스케이프된다.
    String body = serializeBody(Map.of("token", oauthToken));
    return webClient
        .post()
        .uri("/agent/cli-auth/verify")
        .header("Authorization", "Internal " + internalToken)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .retrieve()
        .bodyToMono(String.class)
        .block(Duration.ofSeconds(40));
  }

  /**
   * Anthropic API 키를 ai-agent 에 검증시킨다.
   *
   * <p><b>키는 호출부가 넘긴다</b>(위 {@link #verifyCliToken(String)} 와 같은 이유 — 요청당
   * {@code resolve()} 가 두 번 돌던 것을 한 번으로 줄인다). <b>어떤 유형의 키를 여기로 보낼지는
   * 호출부 switch 가 정한다</b> — 특히 {@code Opencode.apiKey} 는 OpenAI 호환 키라 이
   * 엔드포인트(Anthropic 키 검증)로 보내면 안 되고, 두 {@code getAuthStatus} 컨트롤러의
   * switch 가 opencode 를 이 경로가 아니라 "해당 없음" 응답으로 보낸다.
   *
   * @param apiKey 호출부가 자격증명에서 꺼낸 Anthropic API 키. 비어 있으면 ai-agent 를 부르지
   *     않고 즉시 {@code invalid} 를 돌려준다.
   */
  public String verifyApiKey(String apiKey) {
    if (apiKey.isBlank()) {
      return "{\"valid\":false}";
    }
    // JSON 인젝션 방지: 문자열 연결이 아닌 ObjectMapper로 안전하게 직렬화한다.
    String body = serializeBody(Map.of("apiKey", apiKey));
    return webClient
        .post()
        .uri("/agent/api-key/verify")
        .header("Authorization", "Internal " + internalToken)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .retrieve()
        .bodyToMono(String.class)
        .block(Duration.ofSeconds(40));
  }

  /**
   * Map을 JSON 문자열로 직렬화한다. ObjectMapper는 모든 JSON 특수문자를 안전하게 이스케이프하므로 토큰/API키에 따옴표·백슬래시·제어문자가 포함되어도
   * JSON 구조가 깨지지 않는다.
   */
  private String serializeBody(Map<String, ?> payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      // Map 직렬화는 실패할 수 없으므로 발생 시 즉시 런타임 예외로 노출
      throw new IllegalStateException("Failed to serialize verification body", e);
    }
  }

  /**
   * ai-agent 의 세션 트랜스크립트를 가져온다.
   *
   * <p>테넌트를 쿼리 파라미터로 실어 보낸다 — ai-agent 는 트랜스크립트를 테넌트별 디렉터리에
   * 저장하므로 어느 테넌트의 것을 읽을지 알아야 하고, 동시에 세션 귀속 표식과 대조하는 심층방어
   * 게이트의 입력이 된다. 1차 소유권 검증은 호출 전에 {@code verifySessionOwnership} 이 이미
   * 수행한다({@code AiController.getSessionMessages}).
   */
  public String getSessionHistory(String sessionId) {
    long tenantId = TenantContext.require("AI 세션 이력 조회");
    return webClient
        .get()
        .uri(
            uriBuilder ->
                uriBuilder
                    .path("/agent/history/{sessionId}")
                    .queryParam("tenantId", tenantId)
                    .build(sessionId))
        .header("Authorization", "Internal " + internalToken)
        .retrieve()
        .bodyToMono(String.class)
        .block(TIMEOUT);
  }

  public void streamChat(
      SseEmitter emitter,
      String message,
      String sessionId,
      List<Long> fileIds,
      Long userId,
      String navigationContext,
      String screenContext) {
    emitter.onTimeout(() -> emitter.completeWithError(new RuntimeException("SSE timeout")));
    emitter.onError(e -> log.error("[AI Chat] SseEmitter error", e));

    // 실행 테넌트를 여기서 확정한다 — 이 메서드는 아직 요청 스레드이므로 컨텍스트가 살아 있고,
    // 아래 Flux 구독은 다른 스레드로 넘어가므로 그때 읽으면 이미 비어 있다. ai-agent 는 이 값으로
    // 워크스페이스·트랜스크립트 경로를 테넌트별로 가른다(경로 스코핑 전용 — MCP 도구가 되돌아올 때의
    // 테넌트는 API 가 멤버십에서 다시 파생한다).
    long tenantId = TenantContext.require("AI 챗 프록시");

    // aiSettings 는 이후 키 단위로만 읽히고(아래 requestBody 조립) 맵 자체가 요청 바디에 실리지
    // 않는다. ai.credential 은 이 맵에 나타나지 않는다 — SettingsService.getAsMap 이 그 키를
    // 범용 경로에서 걸러낸다(비밀이 하위 필드에 있어 이 맵의 마스킹 계약으로는 다룰 수 없다).
    // 비밀 값은 아래 aiCredentialService.resolve() 의 결과(AiCredential)가 credential.applyTo()
    // 로 요청 바디에 실을 때만 들어간다 — 그 경로 하나뿐이다.
    Map<String, String> aiSettings = settingsService.getAsMap("ai");

    // agentType 의 출처는 AiCredentialService.resolve() 하나뿐이어야 한다. 예전에는
    // aiSettings.getOrDefault("ai.agent_type", "sdk") 로 따로 읽었는데, getAsMap 은 getValue()
    // 의 rejectBundleKey 를 거치지 않으므로 번들 단일 키 금지 규칙을 조용히 우회하는 뒷문이었다
    // — 그리고 getOrDefault 는 키가 존재하면 빈 문자열도 그대로 돌려주므로(정규화 없음), 빈 값이
    // 저장되면 sdk 폴백이 아니라 else 분기(cli-api)로 떨어져 엉뚱하게 API 키를 요구했다. 같은
    // 상태를 보는 AiAgentClient.classify()/AiController.getAuthStatus() 는 타입 있는 AiCredential
    // 을 보므로, 두 소스가 있으면 한쪽만 고쳐지는 사고가 난다. 그래서 agentType 은 아래 resolve()
    // 결과에서만 파생한다 — 여기서 다시 aiSettings 를 읽지 말 것.
    //
    // resolve() 는 Flux 구독 전, 이 메서드가 아직 요청 스레드에서 동기 실행되는 동안 부른다 —
    // 알 수 없는 agentType 이면 UnknownAgentTypeException 을 그대로 던지고, 그 예외가
    // AiController.chat() 까지 전파돼 500 이 된다(fail-closed). 구독 콜백 안에서 불렀다면 이
    // 예외가 리액터 에러 경로로 흘러 원인 불명의 조용한 실패가 됐을 것이다 — 6b1c6383 과 같은
    // 모양의 회귀를 만들지 않으려면 이 위치가 중요하다.
    AiCredential credential = aiCredentialService.resolve();

    // 사용 가능 여부 판정은 전부 AiCredential 의 유형별 메서드에 있다(이슈 #695). 예전에는 여기에
    // agentType 문자열 if-else 사슬과 opencode 전용 instanceof 가 있었는데, 그건 #693 이 걷어낸
    // 바로 그 안티패턴이 타입 있는 분기 옆에 남아 있던 자리였다 — 유형이 늘 때 컴파일이 아무것도
    // 막아주지 않는다. 공백 문자열을 "없음"으로 보는 정규화도 각 유형의 isComplete() 안에 있다.
    //
    // ai.model 은 유형과 무관하게 한 번 읽어 넘긴다 — 모델 제약이 없는 유형에서는 modelProblem 이
    // 항상 null 이라 no-op 이다. opencode 에서 이 검사가 중요한 이유: ai.model 이 opencode 형식
    // (providerId/modelId)이 아니면(예: sdk 시절 값 "claude-sonnet-5" 가 남아 있는 채로 opencode 로
    // 전환) ai-agent 의 buildOpenCodeConfig 가 throw 하는데, 그 시점은 이미 SSE 헤더가 나간 뒤
    // (chat.ts 가 헤더를 먼저 쓰고서 provider.execute() 를 부른다)라 프론트엔드는 구체적 원인 없이
    // "Agent 처리 중 오류가 발생했습니다" 만 본다. 그래서 여기서 먼저 막는다.
    String model = aiSettings.getOrDefault("ai.model", AiCredential.DEFAULT_MODEL);
    String credentialProblem =
        credential.isComplete() ? credential.modelProblem(model) : credential.incompleteMessage();
    if (credentialProblem != null) {
      try {
        // 채팅은 예외가 아니라 SSE 이벤트로 오류를 내보내야 하므로 requireModelUsable 대신 문구만
        // 가져다 쓴다(분류·프로액티브는 같은 문구를 예외로 던진다).
        String errorPayload =
            objectMapper.writeValueAsString(Map.of("type", "error", "message", credentialProblem));
        emitter.send(SseEmitter.event().data(errorPayload));
        emitter.complete();
      } catch (IOException ignored) {
      }
      return;
    }

    Map<String, Object> requestBody = new HashMap<>();
    requestBody.put("message", message != null ? message : "");
    requestBody.put("sessionId", sessionId != null ? sessionId : "");
    requestBody.put("userId", userId);
    requestBody.put("tenantId", tenantId);
    if (fileIds != null && !fileIds.isEmpty()) {
      requestBody.put("fileIds", fileIds);
    }
    // 자격증명 필드(agentType/apiKey/oauthToken/providerId/baseUrl/reasoningEffort)는 유형별
    // applyTo() 가 채운다 — 필드 이름은 ai-agent 가 읽는 이름이라 분류·프로액티브 경로와 같아야
    // 하고, 그래서 세 경로가 이 메서드 하나를 공유한다(이슈 #695).
    credential.applyTo(requestBody);
    requestBody.put("model", model);
    requestBody.put("maxTurns", parseIntSafe(aiSettings.get("ai.max_turns"), 10));
    requestBody.put("systemPrompt", aiSettings.get("ai.system_prompt"));
    requestBody.put("temperature", parseDoubleSafe(aiSettings.get("ai.temperature"), 1.0));
    requestBody.put("maxTokens", parseIntSafe(aiSettings.get("ai.max_tokens"), 16384));
    requestBody.put(
        "sessionMaxTokens", parseIntSafe(aiSettings.get("ai.session_max_tokens"), 50000));
    if (navigationContext != null && !navigationContext.isEmpty()) {
      requestBody.put("navigationContext", navigationContext);
    }
    if (screenContext != null && !screenContext.isEmpty()) {
      requestBody.put("screenContext", screenContext);
    }

    // Send initial event to flush response headers and prevent proxy buffering
    try {
      emitter.send(SseEmitter.event().comment("connected"));
    } catch (IOException e) {
      log.error("[AI Chat] Failed to send initial event", e);
      return;
    }

    Flux<ServerSentEvent<String>> eventStream =
        webClient
            .post()
            .uri("/agent/chat")
            .header("Authorization", "Internal " + internalToken)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchangeToFlux(
                response -> {
                  if (!response.statusCode().is2xxSuccessful()) {
                    return response
                        .bodyToMono(String.class)
                        .flatMapMany(
                            body ->
                                Flux.error(
                                    new RuntimeException(
                                        "Agent error: " + response.statusCode() + " " + body)));
                  }
                  return response.bodyToFlux(
                      new ParameterizedTypeReference<ServerSentEvent<String>>() {});
                })
            .timeout(TIMEOUT);

    final boolean[] completed = {false};

    eventStream.subscribe(
        sse -> {
          try {
            String data = sse.data();
            if (data == null || data.isEmpty()) return;

            JsonNode node = objectMapper.readTree(data);
            String type = node.has("type") ? node.get("type").asText() : "";

            // Pass through events from firehub-ai to frontend as-is
            // firehub-ai format matches frontend expectations:
            // init, text, tool_use, tool_result, turn, done, error
            switch (type) {
              case "init", "text", "tool_use", "tool_result", "turn", "ping" -> {
                emitter.send(SseEmitter.event().data(data));
              }
              case "done" -> {
                completed[0] = true;
                emitter.send(SseEmitter.event().data(data));
                emitter.complete();
              }
              case "error" -> {
                completed[0] = true;
                String rawMsg = node.has("message") ? node.get("message").asText() : "";
                String errorMsg =
                    (rawMsg == null || rawMsg.isBlank())
                        ? "AI agent processing error (max turns exceeded, etc.)"
                        : rawMsg;
                log.error("[AI Chat] Agent error: {}", errorMsg);
                emitter.send(SseEmitter.event().data(data));
                emitter.complete();
              }
            }
          } catch (IOException e) {
            log.error("[AI Chat] SSE event processing error", e);
            try {
              emitter.completeWithError(e);
            } catch (Exception ignored) {
            }
          }
        },
        error -> {
          log.error("[AI Chat] Agent stream error: {}", error.getMessage());
          try {
            String payload =
                objectMapper.writeValueAsString(
                    Map.of(
                        "type",
                        "error",
                        "message",
                        "Agent connection failed: " + error.getMessage()));
            emitter.send(SseEmitter.event().data(payload));
            emitter.completeWithError(error);
          } catch (IOException ignored) {
          }
        },
        () -> {
          if (!completed[0]) {
            log.warn("[AI Chat] Abnormal stream completion");
          }
          emitter.complete();
        });
  }
}
