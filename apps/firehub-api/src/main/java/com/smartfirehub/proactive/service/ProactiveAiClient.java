package com.smartfirehub.proactive.service;

import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import com.smartfirehub.global.json.JsonConversions;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.util.AppJsonWebClients;
import com.smartfirehub.proactive.dto.ProactiveResult;
import com.smartfirehub.proactive.exception.ProactiveJobException;
import com.smartfirehub.securitylevel.ai.AiCallContext;
import com.smartfirehub.settings.model.AiCredential;
import com.smartfirehub.vectorstore.VectorStoreFeatures;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayList;
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
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
@Slf4j
public class ProactiveAiClient {

  /**
   * ai-agent 프로액티브 호출의 제한 시간 — 설정 {@code app.proactive.agent-timeout}(기본 15분, WD-766). ai-agent 쪽에는
   * 응답 제한이 없어 이 값이 실질 상한이고, 걸리면 연결을 닫아 ai-agent 도 실행을 멈춘다(proactive.ts 의 close 감지).
   */
  private final Duration timeout;

  private final WebClient webClient;
  private final ObjectMapper objectMapper;
  private final DataStoreResolver dataStoreResolver;
  private final VectorStoreFeatures vectorStoreFeatures;

  @Value("${agent.internal-token}")
  private String internalToken;

  /** 제한 시간 설정 키 — 기동 실패 문구에 그대로 싣는다(WD-778). */
  static final String TIMEOUT_PROPERTY = "app.proactive.agent-timeout";

  /**
   * 제한 시간 하한(WD-778). 0 이면 모든 리포트가 곧바로 「시간 초과」로 끝나고, 음수는 Reactor {@code Mono.timeout} 이 호출마다 예외를
   * 던진다 — 둘 다 기동은 성공한 채 스마트 작업 전체가 조용히 실패한다. 리포트 한 건은 ai-agent 의 LLM·도구 호출 여러 번이라 몇 초 안에 끝날 수 없으므로
   * 10초 미만은 오타(예: 단위 누락)로 보고 기동을 멈춘다.
   */
  static final Duration MIN_TIMEOUT = Duration.ofSeconds(10);

  public ProactiveAiClient(
      @Value("${agent.url}") String agentUrl,
      ObjectMapper objectMapper,
      DataStoreResolver dataStoreResolver,
      VectorStoreFeatures vectorStoreFeatures,
      @Value("${app.proactive.agent-timeout:PT15M}") Duration timeout) {
    this.timeout = timeout;
    // 제한은 아래 Mono.timeout 하나로 건다 — Netty responseTimeout 을 함께 두면 어느 쪽이 먼저 걸렸는지 예외로 다시 가려야 한다.
    HttpClient httpClient = HttpClient.create();
    this.webClient =
        AppJsonWebClients.webClientBuilder()
            .baseUrl(agentUrl)
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
            .build();
    this.objectMapper = objectMapper;
    this.dataStoreResolver = dataStoreResolver;
    this.vectorStoreFeatures = vectorStoreFeatures;
  }

  /** 운영 빈의 제한 시간 하한 검사(WD-778) — 생성자가 아니라 여기서 해, 시험은 생성자로 짧은 제한 시간(300ms 등)을 그대로 쓸 수 있다. */
  @PostConstruct
  void validateTimeout() {
    requireMinimumTimeout(timeout);
  }

  /**
   * 제한 시간이 {@link #MIN_TIMEOUT}(10초) 이상인지 본다(WD-778). 아니면 설정 키와 받은 값을 담은 {@link
   * IllegalStateException} 으로 빈 생성을 — 곧 기동을 — 실패시킨다. 운영자가 로그 한 줄로 어느 설정을 어떻게 고칠지 알 수 있게 한다.
   */
  static void requireMinimumTimeout(Duration timeout) {
    if (timeout == null || timeout.compareTo(MIN_TIMEOUT) < 0) {
      throw new IllegalStateException(
          TIMEOUT_PROPERTY
              + " 은 "
              + MIN_TIMEOUT.toSeconds()
              + "초 이상이어야 합니다(환경변수 PROACTIVE_AGENT_TIMEOUT, 예: PT15M). 받은 값: "
              + timeout);
    }
  }

  /**
   * ai-agent 의 프로액티브 라우트를 호출한다.
   *
   * <p>자격증명은 평면 문자열 파라미터가 아니라 {@link AiCredential} 을 그대로 받는다(이슈 #695). 예전에는 {@code apiKey}/{@code
   * agentType}/{@code oauthToken} 세 개의 연속 String 과 opencode 전용 필드를 묶은 별도 record 를 받아 <b>이 메서드가 직접
   * 바디를 조립</b>했는데, 그 조립 규칙이 채팅·분류 경로와 따로 유지되는 세 번째 방언이었다 — 실제로 필드 누락과 빈 값 처리 차이가 생겨 있었다. 이제 필드 조립은
   * {@link AiCredential#applyTo(Map)} 하나뿐이다.
   *
   * @param model 테넌트 {@code ai.model}. 채팅·분류와 같이 모든 유형에 싣는다(WD-766). {@code null} 이면 생략한다
   * @param executionId 이 호출을 일으킨 실행 id(WD-198) — ai-agent 가 MCP 호출 헤더로 되돌려 보내 리포트 등급 기록에 쓴다. null
   *     이면 싣지 않는다
   */
  public ProactiveResult execute(
      Long userId,
      String prompt,
      String context,
      AiCredential credential,
      String model,
      Map<String, Object> template,
      Map<String, Object> config,
      Long executionId) {
    try {
      Map<String, Object> body = new HashMap<>();
      body.put("prompt", prompt);
      body.put("context", context != null ? context : "{}");
      // 자격증명 필드(agentType/apiKey/oauthToken/providerId/baseUrl/reasoningEffort)를 유형별로
      // 채운다. agentType 에 기본값을 두지 않는 것도 그 안의 규칙이다 — 조용히 "sdk" 로 떨어지면
      // 안 되고, 알 수 없는 유형은 ai-agent 의 isKnownAgentType 가드가 400 으로 막게 둔다.
      credential.applyTo(body);
      body.put("config", config != null ? config : Map.of());
      // 모델은 유형과 무관하게 싣는다(WD-766) — 테넌트가 고른 모델로 리포트를 만들게 한다.
      if (model != null) {
        body.put("model", model);
      }
      if (template != null) {
        body.put("template", template);
      }
      // ai-agent 가 디스크 산출물 경로를 테넌트별로 가르는 데 쓴다(경로 스코핑 전용).
      // 이 경로는 @Scheduled/@Async 배경 잡에서 오므로 TenantScopedRunner 가 세운 컨텍스트를 읽는다.
      long tenantId = TenantContext.require("프로액티브 AI 호출");
      body.put("tenantId", tenantId);
      // 테넌트 데이터 저장소 방언(WD-79) — ai-agent 가 리포트 생성 프롬프트에 SQL 방언 지침을 넣는 근거.
      // 바디의 tenantId 와 같은 값으로 판정해 둘이 어긋나지 않게 한다.
      DataStoreBackend backend = dataStoreResolver.backendFor(tenantId);
      body.put("dbDialect", DataStoreBackend.dialectOf(backend));
      // 「행 검색 가능」(WD-744) — 채팅(AiChatRequestBuilder)과 같은 판정. ai-agent 가 행 검색 도구 노출을 방언 대신 이 값으로
      // 가른다.
      body.put("rowSearch", vectorStoreFeatures.supportsRowSearch(backend));
      // 리포트는 발송(공유)된다 — ai-agent 가 이 값을 읽어 실행 중 MCP 호출에 X-AI-Purpose: share 를 실어, API 가 AI 판정에
      // 더해 SHARE 정책(공유 금지 등급 제외)까지 걸게 한다(스펙 §4.3 Proactive 행).
      body.put("aiPurpose", AiCallContext.PURPOSE_SHARE);
      // 리포트 등급(WD-198): ai-agent 가 이 실행 중의 MCP 호출에 X-Proactive-Execution-Id 로 실어, api 가 그 호출에서 허용한
      // 데이터셋
      // 읽기의 등급을 이 실행에 남기게 한다. api 는 헤더 값을 믿지 않고 작성자·진행 중 여부를 확인한 뒤에만 기록한다.
      if (executionId != null) {
        body.put("proactiveExecutionId", executionId);
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
              // 시간 초과는 도메인 예외로 바로 낸다(WD-766) — 구독 취소로 연결이 닫혀 ai-agent 도 멈춘다.
              .timeout(
                  timeout,
                  Mono.error(
                      () -> {
                        log.error("AI agent proactive request timed out after {}", timeout);
                        return new ProactiveJobException(timeoutMessage(timeout));
                      }))
              .block();

      return parseResponse(responseBody);

    } catch (ProactiveJobException e) {
      // 위 시간 초과 예외는 그 문구 그대로 올린다 — 아래 일반 문구로 덮지 않는다.
      throw e;
    } catch (WebClientResponseException e) {
      // 원문 응답 본문(오류 메시지·request_id 등 내부 정보 포함)은 서버 로그에만 남긴다.
      // 여기서 throw한 메시지는 execution.error로 저장되어 사용자 화면에 그대로 노출되므로
      // 번역된 행동 가능 문구만 전달한다 (이슈 #350, #313 원칙).
      String body = e.getResponseBodyAsString();
      log.error("AI agent proactive failed with status {}: {}", e.getStatusCode(), body);
      throw new ProactiveJobException(userFacingMessageFor(body), e);
    } catch (Exception e) {
      log.error("AI agent proactive request failed: {}", e.getMessage(), e);
      throw new ProactiveJobException(AGENT_CALL_FAILED_MESSAGE, e);
    }
  }

  /** 에이전트 호출 자체가 실패했을 때(네트워크·5xx 등) 사용자에게 보여줄 문구. 시간 초과는 {@link #timeoutMessage}(WD-766). */
  static final String AGENT_CALL_FAILED_MESSAGE =
      "AI 에이전트 호출에 실패해 리포트를 생성하지 못했습니다. 잠시 후 다시 시도하거나 관리 > 설정에서 AI 설정을 확인해 주세요.";

  /**
   * 제한 시간 {@code limit} 을 넘겼을 때의 문구. 시간은 설정값에서 만든다 — 상수 「15분」 을 박아 두면 설정을 바꾼 뒤 문구가 거짓말을 한다(WD-766).
   */
  static String timeoutMessage(Duration limit) {
    return "AI 응답이 제한 시간("
        + limitLabel(limit)
        + ")을 넘겨 리포트를 만들지 못했습니다. 잠시 후 다시 시도하거나 작업 범위를 줄여 주세요.";
  }

  /** 제한 시간 표기 — 분 단위로 나누어떨어지면 「N분」, 아니면 「N초」(최소 1초 — 「0초」는 원인을 가린다). */
  private static String limitLabel(Duration limit) {
    long s = Math.max(1, limit.toSeconds());
    return s % 60 == 0 ? (s / 60) + "분" : s + "초";
  }

  /**
   * 에이전트 오류 응답 본문의 {@code code}를 보고 사용자 문구를 고른다.
   *
   * <p>ai-agent는 인증/쿼터 실패를 {@code AGENT_AUTH_OR_QUOTA_FAILURE} 코드로 구분해 알려준다. 이 경우 사용자가 실제로 취할 수 있는
   * 조치(인증 정보 확인)를 안내한다. WD-765: 위임 경로에서 리포트 파일이 저장되지 않으면 {@code AGENT_REPORT_NOT_SAVED} 를 보내므로 「호출
   * 실패」 대신 저장 실패 문구를 고른다.
   */
  private String userFacingMessageFor(String responseBody) {
    if (responseBody != null && responseBody.contains("AGENT_AUTH_OR_QUOTA_FAILURE")) {
      return ProactiveResultValidator.USER_FACING_FAILURE_MESSAGE;
    }
    if (responseBody != null && responseBody.contains("AGENT_REPORT_NOT_SAVED")) {
      return ProactiveResultValidator.REPORT_NOT_SAVED_MESSAGE;
    }
    return AGENT_CALL_FAILED_MESSAGE;
  }

  private ProactiveResult parseResponse(String responseBody) throws Exception {
    Map<String, Object> responseMap =
        objectMapper.readValue(responseBody, new TypeReference<>() {});

    String title = (String) responseMap.getOrDefault("title", null);

    List<Map<String, Object>> rawSections =
        JsonConversions.convertValue(
            objectMapper,
            responseMap.getOrDefault("sections", List.of()),
            new TypeReference<>() {});

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

    return new ProactiveResult(
        title,
        sections,
        usage,
        htmlContent,
        summary,
        parseNumberCheck(responseMap.get("numberCheck")));
  }

  /** 응답의 numberCheck를 안전하게 변환한다. 필드가 없거나 형이 다르면 null을 반환해 이전 응답과 호환되며, 검증 표식 때문에 실행이 실패하지 않게 한다. */
  private ProactiveResult.NumberCheck parseNumberCheck(Object raw) {
    if (!(raw instanceof Map<?, ?> map)) {
      return null;
    }
    boolean checked = Boolean.TRUE.equals(map.get("checked"));
    List<ProactiveResult.UnverifiedNumber> unverified = new ArrayList<>();
    if (map.get("unverified") instanceof List<?> items) {
      for (Object item : items) {
        if (item instanceof Map<?, ?> m && m.get("text") instanceof String text) {
          unverified.add(
              new ProactiveResult.UnverifiedNumber(
                  text, m.get("context") instanceof String c ? c : ""));
        }
      }
    }
    int total = map.get("unverifiedTotal") instanceof Number n ? n.intValue() : unverified.size();
    // 음수 total 은 notificationSummary 가 표식 없음으로 취급한다.
    return new ProactiveResult.NumberCheck(checked, unverified, total);
  }
}
