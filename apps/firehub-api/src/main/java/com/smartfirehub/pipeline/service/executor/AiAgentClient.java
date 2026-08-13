package com.smartfirehub.pipeline.service.executor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.settings.service.SettingsService;
import java.time.Duration;
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

@Slf4j
@Component
public class AiAgentClient {

  private static final Duration TIMEOUT = Duration.ofSeconds(60);

  private final WebClient webClient;
  private final ObjectMapper objectMapper;
  private final SettingsService settingsService;

  @Value("${agent.internal-token}")
  private String internalToken;

  public AiAgentClient(
      @Value("${agent.url}") String agentUrl,
      ObjectMapper objectMapper,
      SettingsService settingsService) {
    HttpClient httpClient = HttpClient.create().responseTimeout(TIMEOUT);
    this.webClient =
        WebClient.builder()
            .baseUrl(agentUrl)
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
            .build();
    this.objectMapper = objectMapper;
    this.settingsService = settingsService;
  }

  public record ClassifyRequest(
      List<Map<String, Object>> rows,
      String prompt,
      List<Map<String, String>> outputColumns // [{name, type}]
      ) {}

  public record ClassifyRowResult(
      Map<String, Object> values // dynamic column values; includes source_id
      ) {}

  public record ClassifyResponse(List<ClassifyRowResult> results, int processed, String model) {}

  /**
   * ai-agent 의 분류 엔드포인트를 호출한다.
   *
   * <p>자격증명(apiKey / oauthToken)과 모델은 여기서 관리자 설정(DB)에서 복호화해 요청 바디에 주입한다 —
   * AiAgentProxyService(채팅)와 동일한 패턴이다. 이전에는 ai-agent 가 {@code /settings/ai-api-key} 를 역호출해
   * 스스로 키를 가져왔는데, 그 엔드포인트는 {@code ai:settings}(ADMIN 전용) 권한을 요구하므로 비-ADMIN 사용자의
   * 파이프라인이 조용히 실패했고 OAuth 토큰은 아예 전달되지 않았다.
   */
  public ClassifyResponse classify(ClassifyRequest request, Long userId) {
    try {
      Map<String, Object> body = new java.util.HashMap<>();
      body.put("rows", request.rows());
      body.put("prompt", request.prompt());
      body.put("outputColumns", request.outputColumns());
      body.put("model", settingsService.getValue("ai.model").orElse("claude-sonnet-5"));
      // OAuth 토큰이 있으면 ai-agent 가 구독 인증을 우선 선택한다(둘 다 보내도 무방).
      settingsService
          .getDecryptedApiKey()
          .filter(key -> !key.isBlank())
          .ifPresent(key -> body.put("apiKey", key));
      settingsService
          .getDecryptedCliOauthToken()
          .filter(token -> !token.isBlank())
          .ifPresent(token -> body.put("oauthToken", token));

      String responseBody =
          webClient
              .post()
              .uri("/agent/classify")
              .contentType(MediaType.APPLICATION_JSON)
              .header("Authorization", "Internal " + internalToken)
              .header("X-On-Behalf-Of", String.valueOf(userId))
              .bodyValue(body)
              .retrieve()
              .bodyToMono(String.class)
              .timeout(TIMEOUT)
              .block();

      Map<String, Object> responseMap =
          objectMapper.readValue(responseBody, new TypeReference<>() {});

      List<Map<String, Object>> rawResults =
          objectMapper.convertValue(responseMap.get("results"), new TypeReference<>() {});

      List<ClassifyRowResult> results = rawResults.stream().map(ClassifyRowResult::new).toList();

      int processed = responseMap.get("processed") instanceof Number n ? n.intValue() : 0;
      String model = (String) responseMap.getOrDefault("model", "unknown");

      return new ClassifyResponse(results, processed, model);

    } catch (WebClientResponseException e) {
      throw new RuntimeException(
          "AI agent classify failed with status "
              + e.getStatusCode()
              + ": "
              + e.getResponseBodyAsString(),
          e);
    } catch (Exception e) {
      throw new RuntimeException("AI agent classify request failed: " + e.getMessage(), e);
    }
  }
}
