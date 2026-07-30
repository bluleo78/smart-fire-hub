package com.smartfirehub.proactive.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.proactive.dto.ProactiveResult;
import com.smartfirehub.proactive.exception.ProactiveJobException;
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
      String oauthToken,
      Map<String, Object> template,
      Map<String, Object> config) {
    try {
      Map<String, Object> body = new HashMap<>();
      body.put("prompt", prompt);
      body.put("context", context != null ? context : "{}");
      body.put("apiKey", apiKey != null ? apiKey : "");
      body.put("agentType", agentType != null ? agentType : "sdk");
      body.put("config", config != null ? config : Map.of());
      // ai-agent가 body의 oauthToken 키를 읽는다 (cli/sdk 공통, 구 cliOauthToken 키는 폐기).
      // 공백 문자열은 다른 검증 지점(missingCredential 등)과 동일하게 "없음"으로 취급한다.
      if (oauthToken != null && !oauthToken.isBlank()) {
        body.put("oauthToken", oauthToken);
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
      // 원문 응답 본문(오류 메시지·request_id 등 내부 정보 포함)은 서버 로그에만 남긴다.
      // 여기서 throw한 메시지는 execution.error로 저장되어 사용자 화면에 그대로 노출되므로
      // 번역된 행동 가능 문구만 전달한다 (이슈 #350, #313 원칙).
      String body = e.getResponseBodyAsString();
      log.error(
          "AI agent proactive failed with status {}: {}", e.getStatusCode(), body);
      throw new ProactiveJobException(userFacingMessageFor(body), e);
    } catch (Exception e) {
      log.error("AI agent proactive request failed: {}", e.getMessage(), e);
      throw new ProactiveJobException(AGENT_CALL_FAILED_MESSAGE, e);
    }
  }

  /** 에이전트 호출 자체가 실패했을 때(네트워크·타임아웃·5xx) 사용자에게 보여줄 문구. */
  static final String AGENT_CALL_FAILED_MESSAGE =
      "AI 에이전트 호출에 실패해 리포트를 생성하지 못했습니다. 잠시 후 다시 시도하거나 관리 > 설정에서 AI 설정을 확인해 주세요.";

  /**
   * 에이전트 오류 응답 본문의 {@code code}를 보고 사용자 문구를 고른다.
   *
   * <p>ai-agent는 인증/쿼터 실패를 {@code AGENT_AUTH_OR_QUOTA_FAILURE} 코드로 구분해 알려준다. 이 경우
   * 사용자가 실제로 취할 수 있는 조치(인증 정보 확인)를 안내한다.
   */
  private String userFacingMessageFor(String responseBody) {
    if (responseBody != null && responseBody.contains("AGENT_AUTH_OR_QUOTA_FAILURE")) {
      return ProactiveResultValidator.USER_FACING_FAILURE_MESSAGE;
    }
    return AGENT_CALL_FAILED_MESSAGE;
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
