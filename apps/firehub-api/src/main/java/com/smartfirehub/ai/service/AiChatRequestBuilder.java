package com.smartfirehub.ai.service;

import com.smartfirehub.settings.model.AiBehaviorDefaults;
import com.smartfirehub.settings.model.AiCredential;
import com.smartfirehub.settings.service.AiCredentialService;
import com.smartfirehub.settings.service.SettingsService;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ai-agent {@code POST /agent/chat} 요청 바디를 조립한다 — 웹 채팅({@link AiAgentProxyService})과
 * Slack 인바운드({@link AiAgentBatchClient})가 같은 규칙을 쓰도록 한 곳에 둔다(이슈 #709).
 *
 * <p><b>테넌트 ID 는 호출부가 넘긴다</b> — 컨텍스트 확인은 호출부의 가드 한 곳에서 한다
 * ({@code SlackInboundService.dispatch} 의 {@code MissingTenantScopeException} catch 참고).
 */
@Component
@Slf4j
public class AiChatRequestBuilder {

  private final SettingsService settingsService;
  private final AiCredentialService aiCredentialService;

  public AiChatRequestBuilder(
      SettingsService settingsService, AiCredentialService aiCredentialService) {
    this.settingsService = settingsService;
    this.aiCredentialService = aiCredentialService;
  }

  /**
   * 조립 결과. {@code problem} 이 있으면 ai-agent 를 부르지 말고 그 문구를 사용자에게 보여 준다.
   *
   * @param body 요청 바디. {@code problem} 이 있으면 {@code null}
   * @param problem 자격증명 미설정·모델 형식 불일치 안내 문구(한국어, 사용자 노출용). 없으면 {@code null}
   */
  public record Prepared(Map<String, Object> body, String problem) {}

  /**
   * 현재 테넌트의 자격증명과 AI 동작 설정으로 채팅 요청 바디를 만든다.
   *
   * <p>반드시 테넌트 컨텍스트 안에서 동기로 부른다 — {@link AiCredentialService#resolve()} 는
   * 컨텍스트가 없으면 예외 대신 빈(미완성) 자격증명을 돌려준다.
   *
   * @param tenantId 호출부가 확인한 실행 테넌트 — ai-agent 가 워크스페이스·트랜스크립트 경로를 가른다
   * @param sessionId ai-agent 세션 ID. 새 세션이면 빈 문자열
   */
  public Prepared prepare(long tenantId, Long userId, String sessionId, String message) {
    // agentType 의 출처는 resolve() 하나뿐이다 — 설정 맵에서 따로 읽지 말 것(두 출처가 있으면
    // 한쪽만 고쳐진다). 사용 가능 판정은 AiCredential 의 유형별 메서드에 있다(이슈 #695).
    // 미설정이면 설정 맵(조회 2회)을 읽기 전에 끝낸다.
    AiCredential credential = aiCredentialService.resolve();
    if (!credential.isComplete()) {
      return new Prepared(null, credential.incompleteMessage());
    }

    // ai.credential 은 이 맵에 나타나지 않는다 — SettingsService.getAsMap 이 그 키를 범용 경로에서
    // 걸러낸다. 비밀 값은 아래 credential.applyTo() 로만 바디에 들어간다.
    Map<String, String> aiSettings = settingsService.getAsMap("ai");

    // ai.model 은 유형과 무관하게 한 번 읽어 넘긴다 — 모델 제약이 없는 유형에서는 modelProblem 이
    // 항상 null 이다. opencode 에서 형식이 틀린 모델은 ai-agent 가 SSE 헤더를 보낸 뒤에야 실패해
    // 원인이 사라지므로 여기서 먼저 막는다.
    String model = aiSettings.get("ai.model");
    String problem = credential.modelProblem(model);
    if (problem != null) {
      return new Prepared(null, problem);
    }

    Map<String, Object> body = new HashMap<>();
    body.put("message", message != null ? message : "");
    body.put("sessionId", sessionId != null ? sessionId : "");
    body.put("userId", userId);
    body.put("tenantId", tenantId);
    // 자격증명 필드(agentType/apiKey/oauthToken/providerId/baseUrl/reasoningEffort)는 유형별
    // applyTo() 가 채운다 — 분류·프로액티브 경로와 같은 메서드를 공유한다(이슈 #695).
    credential.applyTo(body);
    body.put("model", model);
    // AI 동작 키는 aiSettings 에 항상 들어 있다(SettingsService.getAsMap 이 코드 기본값을 깐다).
    // 아래 폴백은 저장된 값이 숫자로 파싱되지 않을 때만 쓰인다.
    body.put("maxTurns", parseIntSafe(aiSettings.get("ai.max_turns"), AiBehaviorDefaults.MAX_TURNS));
    body.put("systemPrompt", aiSettings.get("ai.system_prompt"));
    body.put(
        "temperature",
        parseDoubleSafe(aiSettings.get("ai.temperature"), AiBehaviorDefaults.TEMPERATURE));
    body.put("maxTokens", parseIntSafe(aiSettings.get("ai.max_tokens"), AiBehaviorDefaults.MAX_TOKENS));
    body.put(
        "sessionMaxTokens",
        parseIntSafe(aiSettings.get("ai.session_max_tokens"), AiBehaviorDefaults.SESSION_MAX_TOKENS));
    return new Prepared(body, null);
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
}
