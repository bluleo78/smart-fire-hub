package com.smartfirehub.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.settings.model.AiCredential;
import com.smartfirehub.settings.service.AiCredentialService;
import com.smartfirehub.settings.service.SettingsService;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
  private final AiCredentialService aiCredentialService;

  @Value("${agent.internal-token}")
  private String internalToken;

  public AiAgentProxyService(
      @Value("${agent.url}") String agentUrl,
      ObjectMapper objectMapper,
      SettingsService settingsService,
      AiCredentialService aiCredentialService) {
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
    this.aiCredentialService = aiCredentialService;
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

  /**
   * OAuth 토큰을 ai-agent 에 검증시킨다.
   *
   * <p><b>토큰은 호출부가 넘긴다 — 여기서 다시 해석하지 않는다.</b> 예전에는 이 메서드가
   * {@code aiCredentialService.resolve()} 를 스스로 한 번 더 불러 유형별로 토큰을 골랐는데,
   * 유일한 호출부인 두 {@code getAuthStatus} 컨트롤러가 <b>이미 같은 {@code resolve()} 로
   * 판정해 그 값을 손에 쥔 채</b> 이 메서드를 불렀다 — 요청 1건당 두 평면 SELECT + AES-GCM
   * 복호화가 정확히 두 번 돌았다. 어느 유형에서 무엇이 토큰인지(그리고 어떤 유형이 이 경로
   * 자체를 타면 안 되는지)를 정하는 것은 그 컨트롤러의 exhaustive switch 의 책임이다 —
   * 그 switch 는 {@code AiCredential} 에 변형이 늘면 컴파일 오류로 막히고,
   * {@code AiCredentialSwitchGuardTest} 가 {@code default} 탈출구까지 소스 텍스트로 금지한다.
   *
   * @param oauthToken 호출부가 자격증명에서 꺼낸 OAuth 토큰. 비어 있으면(빈/공백) ai-agent 를
   *     부르지 않고 즉시 {@code invalid} 를 돌려준다 — "미설정"을 네트워크 왕복 없이 답한다.
   */
  public String verifyCliToken(String oauthToken) {
    if (oauthToken.isBlank()) {
      return "{\"valid\":false}";
    }
    // JSON 인젝션 방지: 문자열 연결이 아닌 ObjectMapper로 안전하게 직렬화한다.
    // 백슬래시(\\), 따옴표("), 줄바꿈(\n), 탭(\t) 등 모든 JSON 특수문자가 자동 이스케이프된다.
    String body = serializeBody(Map.of("token", oauthToken));
    return webClient
        .post()
        .uri("/agent/cli-auth/verify")
        .header("Authorization", "Internal " + internalToken)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .retrieve()
        .bodyToMono(String.class)
        .block(Duration.ofSeconds(40));
  }

  /**
   * Anthropic API 키를 ai-agent 에 검증시킨다.
   *
   * <p><b>키는 호출부가 넘긴다</b>(위 {@link #verifyCliToken(String)} 와 같은 이유 — 요청당
   * {@code resolve()} 가 두 번 돌던 것을 한 번으로 줄인다). <b>어떤 유형의 키를 여기로 보낼지는
   * 호출부 switch 가 정한다</b> — 특히 {@code Opencode.apiKey} 는 OpenAI 호환 키라 이
   * 엔드포인트(Anthropic 키 검증)로 보내면 안 되고, 두 {@code getAuthStatus} 컨트롤러의
   * switch 가 opencode 를 이 경로가 아니라 "해당 없음" 응답으로 보낸다.
   *
   * @param apiKey 호출부가 자격증명에서 꺼낸 Anthropic API 키. 비어 있으면 ai-agent 를 부르지
   *     않고 즉시 {@code invalid} 를 돌려준다.
   */
  public String verifyApiKey(String apiKey) {
    if (apiKey.isBlank()) {
      return "{\"valid\":false}";
    }
    // JSON 인젝션 방지: 문자열 연결이 아닌 ObjectMapper로 안전하게 직렬화한다.
    String body = serializeBody(Map.of("apiKey", apiKey));
    return webClient
        .post()
        .uri("/agent/api-key/verify")
        .header("Authorization", "Internal " + internalToken)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .retrieve()
        .bodyToMono(String.class)
        .block(Duration.ofSeconds(40));
  }

  /**
   * Map을 JSON 문자열로 직렬화한다. ObjectMapper는 모든 JSON 특수문자를 안전하게 이스케이프하므로 토큰/API키에 따옴표·백슬래시·제어문자가 포함되어도
   * JSON 구조가 깨지지 않는다.
   */
  private String serializeBody(Map<String, ?> payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      // Map 직렬화는 실패할 수 없으므로 발생 시 즉시 런타임 예외로 노출
      throw new IllegalStateException("Failed to serialize verification body", e);
    }
  }

  /**
   * {@code streamChat} 이 요청 바디에 실을 필드들. 유형별로 쓰이지 않는 필드는 이미 빈 문자열로
   * 정규화돼 있다 — 호출부가 다시 agentType 을 보고 걸러낼 필요가 없다.
   *
   * <p>{@code providerId}/{@code baseUrl}/{@code reasoningEffort} 는 opencode 전용이다(Ruling
   * #32) — 다른 유형에서는 빈 문자열로 채워진다.
   */
  private record ResolvedChatCredential(
      String agentType,
      String apiKey,
      String oauthToken,
      String providerId,
      String baseUrl,
      String reasoningEffort) {}

  /**
   * {@link AiCredentialService#resolve()} 를 유형별로 나눠 {@link ResolvedChatCredential} 로
   * 바꾼다. switch 라 새 유형이 늘면 이 메서드가 컴파일 오류로 강제 갱신된다.
   *
   * <p>{@code opencode} 는 {@code Opencode.apiKey}(OpenAI 호환 키)를 그대로 {@code apiKey} 로
   * 싣는다 — 2026-06-23 의 "옵션 3: 배포 환경 opencode auth 상속" 결정은 2026-09-19(이슈 #693)에
   * 뒤집혔다. ai-agent 의 {@code buildOpenCodeConfig} 가 이제 provider 블록(baseURL/apiKey)을
   * 요청 바디로 직접 받아 쓰므로, 여기서 비워 보내면 그 provider 블록이 빈 채로 조립돼 채팅이
   * 깨진다. {@code cli} 는 apiKey 개념이 없고, {@code cli-api} 는 oauthToken 을 쓰지 않는다.
   */
  private ResolvedChatCredential resolveChatCredential() {
    return switch (aiCredentialService.resolve()) {
      case AiCredential.Sdk sdk ->
          new ResolvedChatCredential("sdk", sdk.apiKey(), sdk.oauthToken(), "", "", "");
      case AiCredential.Cli cli ->
          new ResolvedChatCredential("cli", "", cli.oauthToken(), "", "", "");
      case AiCredential.CliApi cliApi ->
          new ResolvedChatCredential("cli-api", cliApi.apiKey(), "", "", "", "");
      case AiCredential.Opencode oc ->
          new ResolvedChatCredential(
              "opencode", oc.apiKey(), "", oc.providerId(), oc.baseUrl(), oc.reasoningEffort());
    };
  }

  /**
   * ai-agent 의 세션 트랜스크립트를 가져온다.
   *
   * <p>테넌트를 쿼리 파라미터로 실어 보낸다 — ai-agent 는 트랜스크립트를 테넌트별 디렉터리에
   * 저장하므로 어느 테넌트의 것을 읽을지 알아야 하고, 동시에 세션 귀속 표식과 대조하는 심층방어
   * 게이트의 입력이 된다. 1차 소유권 검증은 호출 전에 {@code verifySessionOwnership} 이 이미
   * 수행한다({@code AiController.getSessionMessages}).
   */
  public String getSessionHistory(String sessionId) {
    long tenantId = TenantContext.require("AI 세션 이력 조회");
    return webClient
        .get()
        .uri(
            uriBuilder ->
                uriBuilder
                    .path("/agent/history/{sessionId}")
                    .queryParam("tenantId", tenantId)
                    .build(sessionId))
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

    // 실행 테넌트를 여기서 확정한다 — 이 메서드는 아직 요청 스레드이므로 컨텍스트가 살아 있고,
    // 아래 Flux 구독은 다른 스레드로 넘어가므로 그때 읽으면 이미 비어 있다. ai-agent 는 이 값으로
    // 워크스페이스·트랜스크립트 경로를 테넌트별로 가른다(경로 스코핑 전용 — MCP 도구가 되돌아올 때의
    // 테넌트는 API 가 멤버십에서 다시 파생한다).
    long tenantId = TenantContext.require("AI 챗 프록시");

    // aiSettings 는 이후 키 단위로만 읽히고(아래 requestBody 조립) 맵 자체가 요청 바디에 실리지
    // 않는다. ai.credential 은 이 맵에 나타나지 않는다 — SettingsService.getAsMap 이 그 키를
    // 범용 경로에서 걸러낸다(비밀이 하위 필드에 있어 이 맵의 마스킹 계약으로는 다룰 수 없다).
    // 비밀 값은 아래에서 resolveChatCredential()(AiCredentialService.resolve())에서만 명시적으로 실린다.
    Map<String, String> aiSettings = settingsService.getAsMap("ai");

    // agentType 의 출처는 AiCredentialService.resolve() 하나뿐이어야 한다. 예전에는
    // aiSettings.getOrDefault("ai.agent_type", "sdk") 로 따로 읽었는데, getAsMap 은 getValue()
    // 의 rejectBundleKey 를 거치지 않으므로 번들 단일 키 금지 규칙을 조용히 우회하는 뒷문이었다
    // — 그리고 getOrDefault 는 키가 존재하면 빈 문자열도 그대로 돌려주므로(정규화 없음), 빈 값이
    // 저장되면 sdk 폴백이 아니라 else 분기(cli-api)로 떨어져 엉뚱하게 API 키를 요구했다. 같은
    // 상태를 보는 AiAgentClient.classify()/AiController.getAuthStatus() 는 타입 있는 AiCredential
    // 을 보므로, 두 소스가 있으면 한쪽만 고쳐지는 사고가 난다. 그래서 agentType 은 아래 resolve()
    // 결과에서만 파생한다 — 여기서 다시 aiSettings 를 읽지 말 것.
    //
    // resolve() 는 Flux 구독 전, 이 메서드가 아직 요청 스레드에서 동기 실행되는 동안 부른다 —
    // 알 수 없는 agentType 이면 UnknownAgentTypeException 을 그대로 던지고, 그 예외가
    // AiController.chat() 까지 전파돼 500 이 된다(fail-closed). 구독 콜백 안에서 불렀다면 이
    // 예외가 리액터 에러 경로로 흘러 원인 불명의 조용한 실패가 됐을 것이다 — 6b1c6383 과 같은
    // 모양의 회귀를 만들지 않으려면 이 위치가 중요하다.
    ResolvedChatCredential resolved = resolveChatCredential();
    String agentType = resolved.agentType();

    // 인증 수단 검증: cli/sdk=OAuth 토큰(sdk는 API 키와 양자택일), cli-api=API 키, opencode=배포측 인증(검증 불필요)
    //
    // 공백 문자열을 "없음"으로 취급하는 정규화는 아래 hasApiKey/hasOauthToken 판정에 있다 —
    // resolveChatCredential() 이 유형별로 쓰이지 않는 필드를 이미 빈 문자열로 정규화해 돌려주므로
    // (예: cli-api 의 oauthToken, opencode 의 apiKey/oauthToken) 여기서 다시 agentType 을 보지
    // 않아도 같은 결과가 나온다.
    boolean hasApiKey = !resolved.apiKey().isBlank();
    boolean hasOauthToken = !resolved.oauthToken().isBlank();
    boolean missingCredential;
    // opencode 전용: 모델 형식 위반(슬래시 없음 또는 providerId 불일치) 여부. providerId/baseUrl
    // 이 이미 빈 경우에는 아래에서 계산하지 않는다(missingCredential 가 먼저 걸린다) — 그 경우
    // opencodeModel 은 사용되지 않으므로 빈 문자열로 둬도 안전하다.
    boolean opencodeModelFormatInvalid = false;
    String opencodeModel = "";
    if ("opencode".equals(agentType)) {
      // 옵션 3 폐기(2026-09-19, 이슈 #693) — provider 설정(providerId/baseUrl)이 없으면
      // ai-agent 의 buildOpenCodeConfig 가 배포 측 전역 설정으로 조용히 떨어질 여지를 주지
      // 않고, 여기서 먼저 사용자에게 보이는 오류로 끝낸다(다른 세 유형과 같은 fail-closed).
      missingCredential = resolved.providerId().isBlank() || resolved.baseUrl().isBlank();
      if (!missingCredential) {
        // ai.model 이 opencode 형식(providerId/modelId)이 아니면(예: sdk 시절 값
        // "claude-sonnet-5" 가 남아 있는 채로 opencode 로 전환) ai-agent 의
        // buildOpenCodeConfig 가 "providerId/modelId 형식이어야 합니다" 로 throw 한다 — 그
        // 시점은 이미 SSE 헤더가 나간 뒤(chat.ts 가 헤더를 먼저 쓰고서 provider.execute() 를
        // 부른다)라, 프론트엔드는 구체적 원인 없이 "Agent 처리 중 오류가 발생했습니다" 만 본다.
        // OpencodeCredentialValidation.checkProviderConsistency(저장 시 검증)는 저장 순서상
        // 슬래시 없는 값을 통과시켜야 하는 이유(순환 잠금 회피, 그 클래스 javadoc 참고)가
        // 있지만, 여기는 저장이 아니라 **실제 호출 시점**이라 그 이유가 성립하지 않는다 —
        // 슬래시가 없으면 무조건 형식 위반으로 막는다(저장 시 검증보다 엄격).
        opencodeModel = aiSettings.getOrDefault("ai.model", "claude-sonnet-5");
        int slash = opencodeModel.indexOf('/');
        opencodeModelFormatInvalid =
            slash < 0 || !opencodeModel.substring(0, slash).equals(resolved.providerId());
      }
    } else if ("cli".equals(agentType)) {
      missingCredential = !hasOauthToken;
    } else if ("sdk".equals(agentType)) {
      // sdk 는 API 키 또는 OAuth 토큰 중 하나만 있어도 인증 가능(OAuth 우선).
      missingCredential = !hasApiKey && !hasOauthToken;
    } else { // cli-api
      missingCredential = !hasApiKey;
    }
    if (missingCredential || opencodeModelFormatInvalid) {
      try {
        String errorMessage;
        if (opencodeModelFormatInvalid) {
          errorMessage =
              "AI 모델(" + opencodeModel + ")이 opencode 형식(공급자/모델)이 아니거나 선택한 공급자와 일치하지 않습니다. 관리자 설정에서 모델을 다시 선택하세요.";
        } else if ("cli".equals(agentType)) {
          errorMessage = "Claude CLI OAuth 토큰이 설정되지 않았습니다. 관리자 설정에서 토큰을 등록하세요.";
        } else if ("sdk".equals(agentType)) {
          errorMessage = "AI API 키 또는 OAuth 토큰이 설정되지 않았습니다. 관리자 설정에서 등록하세요.";
        } else if ("opencode".equals(agentType)) {
          errorMessage = "AI 공급자 설정(공급자/기본 URL)이 완전하지 않습니다. 관리자 설정에서 opencode 설정을 확인하세요.";
        } else {
          errorMessage = "AI API 키가 설정되지 않았습니다. 관리자 설정에서 API 키를 등록하세요.";
        }
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
    requestBody.put("tenantId", tenantId);
    if (fileIds != null && !fileIds.isEmpty()) {
      requestBody.put("fileIds", fileIds);
    }
    // opencode/cli 는 resolveChatCredential() 이 이미 apiKey 를 빈 문자열로 정규화해 두므로
    // hasApiKey 가 자연히 거짓이다 — agentType 을 다시 보는 별도 가드가 필요 없다.
    if (hasApiKey) {
      requestBody.put("apiKey", resolved.apiKey());
    }
    requestBody.put("agentType", agentType);
    // cli 또는 sdk 에서 OAuth 토큰이 있으면 body 에 주입(중립 키 oauthToken) — 그 두 형태 조건은
    // hasOauthToken 자체가 이미 담고 있다. sdk 에서 apiKey 와 함께 있으면 ai-agent 가 OAuth 를
    // 우선 선택한다.
    if (hasOauthToken) {
      requestBody.put("oauthToken", resolved.oauthToken());
    }
    // opencode 전용 — ai-agent 의 buildOpenCodeConfig 가 provider 블록(baseURL/apiKey/model)을
    // 조립하는 데 쓴다(필드명은 AiAgentClient.buildClassifyBody 와 동일해야 한다 — ai-agent 가
    // 그 이름으로 읽는다). providerId/baseUrl 은 missingCredential 가드가 이미 비어있지 않음을
    // 보장하므로 무조건 싣고, reasoningEffort 는 빈 값이면 "설정 안 함"이라 아예 생략한다(빈
    // 문자열을 그대로 보내면 ai-agent 쪽에서 opencode 가 400 을 반환한다).
    if ("opencode".equals(agentType)) {
      requestBody.put("providerId", resolved.providerId());
      requestBody.put("baseUrl", resolved.baseUrl());
      if (!resolved.reasoningEffort().isBlank()) {
        requestBody.put("reasoningEffort", resolved.reasoningEffort());
      }
    }
    requestBody.put("model", aiSettings.getOrDefault("ai.model", "claude-sonnet-5"));
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
                        "type",
                        "error",
                        "message",
                        "Agent connection failed: " + error.getMessage()));
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
