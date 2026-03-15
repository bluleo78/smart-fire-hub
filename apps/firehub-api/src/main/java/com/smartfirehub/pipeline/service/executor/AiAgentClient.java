package com.smartfirehub.pipeline.service.executor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

@Component
public class AiAgentClient {

  private static final Logger log = LoggerFactory.getLogger(AiAgentClient.class);
  private static final Duration TIMEOUT = Duration.ofSeconds(60);

  private final WebClient webClient;
  private final ObjectMapper objectMapper;

  @Value("${agent.internal-token}")
  private String internalToken;

  public AiAgentClient(@Value("${agent.url}") String agentUrl, ObjectMapper objectMapper) {
    HttpClient httpClient = HttpClient.create().responseTimeout(TIMEOUT);
    this.webClient =
        WebClient.builder()
            .baseUrl(agentUrl)
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
            .build();
    this.objectMapper = objectMapper;
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

  public ClassifyResponse classify(ClassifyRequest request, Long userId) {
    try {
      Map<String, Object> body =
          Map.of(
              "rows", request.rows(),
              "prompt", request.prompt(),
              "outputColumns", request.outputColumns());

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
