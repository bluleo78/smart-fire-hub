package com.smartfirehub.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;

import com.smartfirehub.settings.service.SettingsService;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Service
public class AiAgentProxyService {

    private static final Logger log = LoggerFactory.getLogger(AiAgentProxyService.class);
    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final SettingsService settingsService;

    @Value("${agent.internal-token}")
    private String internalToken;

    public AiAgentProxyService(
            @Value("${agent.url}") String agentUrl,
            ObjectMapper objectMapper,
            SettingsService settingsService
    ) {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofMinutes(5))
                .keepAlive(true);
        this.webClient = WebClient.builder()
                .baseUrl(agentUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
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
            log.warn("[AI Chat] Invalid double setting value '{}', using default {}", value, defaultValue);
            return defaultValue;
        }
    }

    public String getSessionHistory(String sessionId) {
        return webClient.get()
                .uri("/agent/history/{sessionId}", sessionId)
                .header("Authorization", "Internal " + internalToken)
                .retrieve()
                .bodyToMono(String.class)
                .block(TIMEOUT);
    }

    public void streamChat(SseEmitter emitter, String message, String sessionId, Long userId) {
        emitter.onTimeout(() -> emitter.completeWithError(new RuntimeException("SSE timeout")));
        emitter.onError(e -> log.error("[AI Chat] SseEmitter error", e));

        Map<String, String> aiSettings = settingsService.getAsMap("ai");

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("message", message);
        requestBody.put("sessionId", sessionId != null ? sessionId : "");
        requestBody.put("userId", userId);
        requestBody.put("model", aiSettings.getOrDefault("ai.model", "claude-sonnet-4-6"));
        requestBody.put("maxTurns", parseIntSafe(aiSettings.get("ai.max_turns"), 10));
        requestBody.put("systemPrompt", aiSettings.get("ai.system_prompt"));
        requestBody.put("temperature", parseDoubleSafe(aiSettings.get("ai.temperature"), 1.0));
        requestBody.put("maxTokens", parseIntSafe(aiSettings.get("ai.max_tokens"), 16384));

        // Send initial event to flush response headers and prevent proxy buffering
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            log.error("[AI Chat] Failed to send initial event", e);
            return;
        }

        Flux<ServerSentEvent<String>> eventStream = webClient.post()
                .uri("/agent/chat")
                .header("Authorization", "Internal " + internalToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchangeToFlux(response -> {
                    if (!response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(String.class)
                                .flatMapMany(body -> Flux.error(new RuntimeException("Agent error: " + response.statusCode() + " " + body)));
                    }
                    return response.bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {});
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
                        // init, text, tool_use, tool_result, done, error
                        switch (type) {
                            case "init", "text", "tool_use", "tool_result" -> {
                                emitter.send(SseEmitter.event().data(data));
                            }
                            case "done" -> {
                                completed[0] = true;
                                emitter.send(SseEmitter.event().data(data));
                                emitter.complete();
                            }
                            case "error" -> {
                                completed[0] = true;
                                String errorMsg = node.has("message") ? node.get("message").asText() : "Unknown error";
                                log.error("[AI Chat] Agent error: {}", errorMsg);
                                emitter.send(SseEmitter.event().data(data));
                                emitter.completeWithError(new RuntimeException(errorMsg));
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
                        String payload = objectMapper.writeValueAsString(
                                Map.of("type", "error", "data", "Agent 연결 실패: " + error.getMessage()));
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
                }
        );
    }
}
