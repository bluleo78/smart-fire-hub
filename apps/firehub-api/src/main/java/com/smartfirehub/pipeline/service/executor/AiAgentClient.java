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

  /**
   * ai-agent 호출 상한. <b>ai-agent 쪽 상한보다 반드시 커야 한다</b> — 저쪽이 먼저 끊어야 원인이
   * 담긴 메시지가 올라오고, 순서가 뒤집히면 원인 없는 타임아웃만 남는다. 근거와 값의 유래는
   * {@code classification-service.ts} 의 {@code classifyTimeoutMs} 에 있다(그쪽 천장 300초 + 여유).
   *
   * <p>아래에서 {@code HttpClient.responseTimeout} 과 {@code Mono.timeout} <b>양쪽</b>에 쓰인다 —
   * 한쪽만 고치면 효과가 없다. {@code classify()} 가 이 WebClient 의 유일한 소비자라,
   * 이 값을 올려도 다른 ai-agent 호출의 예산은 넓어지지 않는다.
   */
  private static final Duration TIMEOUT = Duration.ofSeconds(330);

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
   * "DB 에서 복호화해 바디로 넘긴다"는 골격은 AiAgentProxyService(채팅)와 같다. 다만 agentType=opencode
   * 일 때 자격증명을 숨기는 채팅 쪽 규칙은 여기 적용되지 않는다 — 이유는 아래 classify() 본문 주석 참고.
   * 이전에는 ai-agent 가 {@code /settings/ai-api-key} 를 역호출해 스스로 키를 가져왔는데, 그 엔드포인트는
   * {@code ai:settings}(ADMIN 전용) 권한을 요구하므로 비-ADMIN 사용자의 파이프라인이 조용히 실패했고 OAuth
   * 토큰은 아예 전달되지 않았다.
   */
  public ClassifyResponse classify(ClassifyRequest request, Long userId) {
    try {
      Map<String, Object> body = new java.util.HashMap<>();
      body.put("rows", request.rows());
      body.put("prompt", request.prompt());
      body.put("outputColumns", request.outputColumns());
      body.put("model", settingsService.getValue("ai.model").orElse("claude-sonnet-5"));
      // 자격증명은 번들로 함께 해석되어 agentType 과 무관하게 항상 보낸다 — 채팅 경로(AiAgentProxyService)와
      // 다르다. classify 는 opencode 로 라우팅되지 않는다: ai-agent 의 ProviderFactory.createCompletionProvider
      // (provider-factory.ts)에는 agentType 분기가 아예 없고 SDK 경로 하나로 고정되어 있다. 여기서 agentType
      // 을 보고 자격증명을 숨기면(과거의 실수) 바디에 apiKey/oauthToken 이 둘 다 빠지고, SDK 가 ai-agent
      // 컨테이너 자신의 ANTHROPIC_API_KEY/CLAUDE_CODE_OAUTH_TOKEN 으로 폴백한다 — 인증 실패 또는 테넌트
      // 청구가 플랫폼 계정으로 새는 사고로 이어진다.
      var creds = settingsService.getAiCredentials();
      if (creds.hasApiKey()) body.put("apiKey", creds.apiKey());
      if (creds.hasOauthToken()) body.put("oauthToken", creds.cliOauthToken());

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
