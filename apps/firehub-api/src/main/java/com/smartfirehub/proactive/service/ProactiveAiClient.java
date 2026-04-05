package com.smartfirehub.proactive.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.proactive.dto.ProactiveResult;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

@Component
@Slf4j
public class ProactiveAiClient {

  // HTML 리포트 생성은 SVG 차트/카드 레이아웃 등 복잡한 작업이므로 충분한 시간 필요
  private static final Duration TIMEOUT = Duration.ofMinutes(5);

  private final WebClient webClient;
  private final ObjectMapper objectMapper;

  @Value("${agent.internal-token}")
  private String internalToken;

  public ProactiveAiClient(@Value("${agent.url}") String agentUrl, ObjectMapper objectMapper) {
    HttpClient httpClient = HttpClient.create().responseTimeout(TIMEOUT);
    this.webClient =
        WebClient.builder()
            .baseUrl(agentUrl)
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
            .build();
    this.objectMapper = objectMapper;
  }

  public ProactiveResult execute(
      Long userId,
      String prompt,
      String context,
      String apiKey,
      String agentType,
      String cliOauthToken,
      Map<String, Object> template,
      Map<String, Object> config) {
    try {
      Map<String, Object> body = new HashMap<>();
      body.put("prompt", prompt);
      body.put("context", context != null ? context : "{}");
      body.put("apiKey", apiKey != null ? apiKey : "");
      body.put("agentType", agentType != null ? agentType : "sdk");
      body.put("config", config != null ? config : Map.of());
      if (cliOauthToken != null) {
        body.put("cliOauthToken", cliOauthToken);
      }
      if (template != null) {
        body.put("template", template);
      }

      String responseBody =
          webClient
              .post()
              .uri("/agent/proactive")
              .contentType(MediaType.APPLICATION_JSON)
              .header("Authorization", "Internal " + internalToken)
              .header("X-On-Behalf-Of", String.valueOf(userId))
              .bodyValue(body)
              .retrieve()
              .bodyToMono(String.class)
              .timeout(TIMEOUT)
              .block();

      return parseResponse(responseBody);

    } catch (WebClientResponseException e) {
      throw new RuntimeException(
          "AI agent proactive failed with status "
              + e.getStatusCode()
              + ": "
              + e.getResponseBodyAsString(),
          e);
    } catch (Exception e) {
      throw new RuntimeException("AI agent proactive request failed: " + e.getMessage(), e);
    }
  }

  private ProactiveResult parseResponse(String responseBody) throws Exception {
    Map<String, Object> responseMap =
        objectMapper.readValue(responseBody, new TypeReference<>() {});

    String title = (String) responseMap.getOrDefault("title", null);

    List<Map<String, Object>> rawSections =
        objectMapper.convertValue(
            responseMap.getOrDefault("sections", List.of()), new TypeReference<>() {});

    List<ProactiveResult.Section> sections =
        rawSections.stream()
            .map(
                s ->
                    new ProactiveResult.Section(
                        (String) s.get("key"),
                        (String) s.get("label"),
                        (String) s.get("content"),
                        (String) s.get("type"),
                        s.get("data")))
            .toList();

    ProactiveResult.Usage usage = null;
    if (responseMap.get("usage") instanceof Map<?, ?> usageMap) {
      usage =
          new ProactiveResult.Usage(
              usageMap.get("inputTokens") instanceof Number n ? n.intValue() : 0,
              usageMap.get("outputTokens") instanceof Number n ? n.intValue() : 0,
              usageMap.get("totalTokens") instanceof Number n ? n.intValue() : 0);
    }

    // AI 에이전트가 반환하는 HTML 리포트 전문 (없으면 null — 기존 sections 경로 유지)
    String htmlContent = (String) responseMap.getOrDefault("htmlContent", null);

    // 리포트 요약 텍스트 (채팅/이메일 미리보기에 사용)
    String summary = (String) responseMap.getOrDefault("summary", null);

    return new ProactiveResult(title, sections, usage, htmlContent, summary);
  }
}
