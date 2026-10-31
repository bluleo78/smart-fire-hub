package com.smartfirehub.proactive.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.exception.ExternalServiceException;
import com.smartfirehub.settings.model.AiCredential;
import com.smartfirehub.settings.service.AiCredentialService;
import com.smartfirehub.settings.service.SettingsService;
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

/**
 * ai-agent 의 리포트 양식 섹션 생성 엔드포인트({@code POST /agent/template-sections/generate}) 클라이언트(WD-455).
 *
 * <p>프로액티브 실행용 {@link ProactiveAiClient}(5분 상한)와 따로 둔 이유: 이 호출은 사용자가 화면에서 버튼을 누르고 기다리는 동기 요청이라 훨씬
 * 짧은 상한이 필요하고, 실패하면 화면이 고정 구성으로 대신한다. 자격증명·모델은 분류({@code AiAgentClient})와 같은 방식으로 테넌트 설정에서 해석해 바디로
 * 넘긴다 — 필드 조립은 {@link AiCredential#applyTo(Map)} 하나뿐이다.
 */
@Slf4j
@Component
public class ReportTemplateAiClient {

  /**
   * 호출 상한. ai-agent 쪽 상한(template-generate.ts 의 TEMPLATE_GENERATE_TIMEOUT_MS = 60초)보다 커야 원인이 담긴 응답이
   * 먼저 올라온다. {@code responseTimeout} 과 {@code Mono.timeout} 양쪽에 쓴다.
   */
  static final Duration TIMEOUT = Duration.ofSeconds(75);

  /** 사용자에게 보일 실패 문구 — ai-agent 원문(내부 정보 포함 가능)은 로그에만 남긴다. */
  static final String GENERATION_FAILED_MESSAGE = "AI 섹션 구성 생성에 실패했습니다. 잠시 후 다시 시도해 주세요.";

  private final WebClient webClient;
  private final ObjectMapper objectMapper;
  private final SettingsService settingsService;
  private final AiCredentialService aiCredentialService;

  @Value("${agent.internal-token}")
  private String internalToken;

  public ReportTemplateAiClient(
      @Value("${agent.url}") String agentUrl,
      ObjectMapper objectMapper,
      SettingsService settingsService,
      AiCredentialService aiCredentialService) {
    HttpClient httpClient = HttpClient.create().responseTimeout(TIMEOUT);
    this.webClient =
        WebClient.builder()
            .baseUrl(agentUrl)
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    this.objectMapper = objectMapper;
    this.settingsService = settingsService;
    this.aiCredentialService = aiCredentialService;
  }

  /**
   * 질문으로 섹션 구성을 생성해 원본 섹션 목록(검사 전)을 돌려준다. 섹션 규칙 검사는 호출자({@link ReportTemplateGenerationService})가
   * 맡는다.
   *
   * <p>자격증명 해석은 {@code try} 밖에서 한다 — 미설정·불완전 자격증명({@link IllegalStateException}, 409)과 알 수 없는 유형이
   * 아래 catch 에 뭉개져 "잠시 후 다시 시도" 502 로 바뀌지 않게 하기 위해서다({@code AiAgentClient.buildClassifyBody} 와 같은
   * 이유).
   *
   * @throws ExternalServiceException ai-agent 호출 실패·타임아웃·읽을 수 없는 응답(502)
   */
  public List<Map<String, Object>> generateSections(Long userId, String question) {
    // 자격증명 완전성 → 모델 형식 순으로 검사한다(순서가 뒤집히면 엉뚱한 "모델을 다시 선택" 안내가 나간다 — AiAgentClient.validated 참고).
    AiCredential credential = aiCredentialService.resolve().requireComplete();
    // 분류(UseChat)와 같이 채팅 모델(ai.model)을 쓴다 — 테넌트 값 → 코드 기본값으로 항상 해석된다.
    String model = settingsService.getValue("ai.model").orElseThrow();
    credential.requireModelUsable(model);

    Map<String, Object> body = new HashMap<>();
    body.put("question", question);
    body.put("model", model);
    credential.applyTo(body);

    try {
      String responseBody =
          webClient
              .post()
              .uri("/agent/template-sections/generate")
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
      if (!(responseMap.get("sections") instanceof List<?>)) {
        throw new IllegalArgumentException("sections 가 없는 응답");
      }
      return objectMapper.convertValue(responseMap.get("sections"), new TypeReference<>() {});
    } catch (WebClientResponseException e) {
      log.error(
          "AI agent template generation failed with status {}: {}",
          e.getStatusCode(),
          e.getResponseBodyAsString());
      throw new ExternalServiceException(GENERATION_FAILED_MESSAGE, e);
    } catch (Exception e) {
      log.error("AI agent template generation request failed: {}", e.getMessage(), e);
      throw new ExternalServiceException(GENERATION_FAILED_MESSAGE, e);
    }
  }
}
