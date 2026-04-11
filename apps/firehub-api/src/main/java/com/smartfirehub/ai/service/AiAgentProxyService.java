package com.smartfirehub.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.settings.service.SettingsService;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

  @Value("${agent.internal-token}")
  private String internalToken;

  public AiAgentProxyService(
      @Value("${agent.url}") String agentUrl,
      ObjectMapper objectMapper,
      SettingsService settingsService) {
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

  public String verifyCliToken() {
    Optional<String> tokenOpt = settingsService.getDecryptedCliOauthToken();
    if (tokenOpt.isEmpty() || tokenOpt.get().isBlank()) {
      return "{\"valid\":false}";
    }
    return webClient
        .post()
        .uri("/agent/cli-auth/verify")
        .header("Authorization", "Internal " + internalToken)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"token\":\"" + tokenOpt.get().replace("\"", "\\\"") + "\"}")
        .retrieve()
        .bodyToMono(String.class)
        .block(Duration.ofSeconds(40));
  }

  public String verifyApiKey() {
    Optional<String> apiKeyOpt = settingsService.getDecryptedApiKey();
    if (apiKeyOpt.isEmpty() || apiKeyOpt.get().isBlank()) {
      return "{\"valid\":false}";
    }
    return webClient
        .post()
        .uri("/agent/api-key/verify")
        .header("Authorization", "Internal " + internalToken)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"apiKey\":\"" + apiKeyOpt.get().replace("\"", "\\\"") + "\"}")
        .retrieve()
        .bodyToMono(String.class)
        .block(Duration.ofSeconds(40));
  }

  public String getSessionHistory(String sessionId) {
    return webClient
        .get()
        .uri("/agent/history/{sessionId}", sessionId)
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

    Map<String, String> aiSettings = new HashMap<>(settingsService.getAsMap("ai"));
    aiSettings.remove("ai.api_key");
    String agentType = aiSettings.getOrDefault("ai.agent_type", "sdk");

    // 인증 수단 검증: cli 모드는 OAuth 토큰, sdk 모드는 API 키가 필요
    Optional<String> apiKeyOpt = settingsService.getDecryptedApiKey();
    Optional<String> cliTokenOpt =
        "cli".equals(agentType) ? settingsService.getDecryptedCliOauthToken() : Optional.empty();
    boolean missingCredential =
        "cli".equals(agentType)
            ? (cliTokenOpt.isEmpty() || cliTokenOpt.get().isBlank())
            : apiKeyOpt.isEmpty();
    if (missingCredential) {
      try {
        String errorMessage =
            "cli".equals(agentType)
                ? "Claude CLI OAuth 토큰이 설정되지 않았습니다. 관리자 설정에서 토큰을 등록하세요."
                : "AI API 키가 설정되지 않았습니다. 관리자 설정에서 API 키를 등록하세요.";
        String errorPayload =
            objectMapper.writeValueAsString(Map.of("type", "error", "message", errorMessage));
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
    if (fileIds != null && !fileIds.isEmpty()) {
      requestBody.put("fileIds", fileIds);
    }
    apiKeyOpt.ifPresent(key -> requestBody.put("apiKey", key));
    requestBody.put("agentType", agentType);
    if ("cli".equals(agentType)) {
      cliTokenOpt.ifPresent(token -> requestBody.put("cliOauthToken", token));
    }
    requestBody.put("model", aiSettings.getOrDefault("ai.model", "claude-sonnet-4-6"));
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
                        "type", "error", "data", "Agent connection failed: " + error.getMessage()));
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
