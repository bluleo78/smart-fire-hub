package com.smartfirehub.proactive.service;

import com.smartfirehub.global.tenant.TenantContext;
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

  /**
   * opencode 전용 provider 필드(Ruling #32) — apiKey/agentType 은 기존 평면 파라미터를 그대로
   * 쓰고(하위 호환), 이 record 는 다른 유형에는 없는 필드만 묶는다. 문자열 파라미터를 계속
   * 늘리면(이미 apiKey/agentType/oauthToken 세 개가 연속 String 이다) 호출부에서 인자 순서를
   * 실수로 바꿔도 컴파일이 통과해 조용히 틀린 값이 실린다 — record 로 묶으면 그 실수의 표면이
   * 줄어든다.
   *
   * <p>{@code model} 을 포함하는 이유: 채팅 경로({@code AiAgentProxyService})는 항상
   * {@code aiSettings.getOrDefault("ai.model", ...)} 로 모델을 보내지만, 이 프로액티브 경로는
   * 지금까지 모델 자체를 보낸 적이 없다({@code ProactiveAiClient.execute} 의 body 에 "model" 키가
   * 없었다) — ai-agent 의 proactive 라우트는 고정 기본값(`claude-haiku-4-5`)만 썼다. 이 태스크
   * 이전에는 opencode 의 {@code buildOpenCodeConfig} 가 model 을 아예 쓰지 않아(옵션 3) 무해했지만,
   * top-level model 이 필수가 된 지금은 opencode 테넌트의 프로액티브 잡이 그 고정값(슬래시 없음
   * → "providerId/modelId 형식이어야 합니다" throw)으로 전부 깨진다. sdk/cli/cli-api 는 여전히
   * 고정 기본값을 쓴다(이 gap 을 넓히지 않는다 — 별도 이슈 대상).
   */
  public record OpencodeFields(String providerId, String baseUrl, String reasoningEffort, String model) {
    /** opencode 가 아닌 유형(또는 옛 호출부)을 위한 빈 값. */
    public static final OpencodeFields NONE = new OpencodeFields("", "", "", "");
  }

  public ProactiveResult execute(
      Long userId,
      String prompt,
      String context,
      String apiKey,
      String agentType,
      String oauthToken,
      Map<String, Object> template,
      Map<String, Object> config,
      OpencodeFields opencodeFields) {
    try {
      Map<String, Object> body = new HashMap<>();
      body.put("prompt", prompt);
      body.put("context", context != null ? context : "{}");
      body.put("apiKey", apiKey != null ? apiKey : "");
      // 기본값을 두지 않는다 — 유일한 호출부(ProactiveJobAsyncRunner)가 resolve() 의 exhaustive
      // switch 에서 네 유형 중 하나를 반드시 채우므로 null 이 도달할 수 없고, 설령 도달하더라도
      // 조용히 "sdk" 로 떨어지면 안 된다(ai-agent 의 isKnownAgentType 가드가 400 으로 막게 둔다).
      // ai-agent 라우트 3곳(chat/proactive/classify)에서 같은 이유로 걷어낸 기본값의 송신 측 잔재였다.
      body.put("agentType", agentType);
      body.put("config", config != null ? config : Map.of());
      // ai-agent가 body의 oauthToken 키를 읽는다 (cli/sdk 공통, 구 cliOauthToken 키는 폐기).
      // 공백 문자열은 다른 검증 지점(missingCredential 등)과 동일하게 "없음"으로 취급한다.
      if (oauthToken != null && !oauthToken.isBlank()) {
        body.put("oauthToken", oauthToken);
      }
      // opencode 전용 — ai-agent 의 buildOpenCodeConfig 가 provider 블록을 조립하는 데 쓴다
      // (필드명은 AiAgentClient.buildClassifyBody 와 동일해야 한다). providerId/baseUrl 이
      // 비어 있으면 아예 채우지 않는다 — ProactiveJobAsyncRunner 는 opencode 자격증명일 때만
      // NONE 이 아닌 값을 넘긴다(그 switch 의 opencode 분기 주석 참고).
      if (opencodeFields != null && !opencodeFields.providerId().isBlank()) {
        body.put("providerId", opencodeFields.providerId());
        body.put("baseUrl", opencodeFields.baseUrl());
        // opencode 는 buildOpenCodeConfig 가 top-level model(providerId/modelId 형식)을 필수로
        // 요구한다 — 비어 있으면 ai-agent 라우트의 고정 기본값(claude-haiku-4-5, 슬래시 없음)이
        // 대신 실려 형식 위반으로 채팅이 깨진다(OpencodeFields 의 model javadoc 참고).
        if (!opencodeFields.model().isBlank()) {
          body.put("model", opencodeFields.model());
        }
        // 빈 문자열은 "설정 안 함" 이므로 생략한다 — 그대로 보내면 ai-agent 쪽에서 opencode 가
        // 400 을 반환한다.
        if (!opencodeFields.reasoningEffort().isBlank()) {
          body.put("reasoningEffort", opencodeFields.reasoningEffort());
        }
      }
      if (template != null) {
        body.put("template", template);
      }
      // ai-agent 가 디스크 산출물 경로를 테넌트별로 가르는 데 쓴다(경로 스코핑 전용).
      // 이 경로는 @Scheduled/@Async 배경 잡에서 오므로 TenantScopedRunner 가 세운 컨텍스트를 읽는다.
      body.put("tenantId", TenantContext.require("프로액티브 AI 호출"));

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
