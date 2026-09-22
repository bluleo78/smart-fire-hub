package com.smartfirehub.ai;

import static com.smartfirehub.support.SettingsTestSupport.upsertSystemSetting;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.pipeline.service.executor.AiAgentClient;
import com.smartfirehub.settings.model.AiCredential;
import com.smartfirehub.settings.model.UnknownAgentTypeException;
import com.smartfirehub.settings.repository.TenantSettingsRepository;
import com.smartfirehub.settings.service.AiCredentialService;
import com.smartfirehub.settings.service.AiCredentialService.AiCredentialUpsert;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SettingsTestSupport;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * ai-agent 컨테이너의 ambient {@code ANTHROPIC_API_KEY}/{@code CLAUDE_CODE_OAUTH_TOKEN} 으로
 * 조용히 폴백하는 과금 혼입 회귀(6b1c6383)를 막는다(Task 4).
 *
 * <p>{@link AiAgentClient#buildClassifyBody} 를 HTTP 없이 직접 불러 검증한다(Ruling #4 —
 * classify() 에서 바디 조립을 추출한 이유가 바로 이 테스트다). {@code ai.credential} 키의
 * 정리 방식은 {@code AiCredentialServiceTest} 와 같다 — 공유 테스트 DB 에 이 클래스가 심은 값이
 * 남으면 기본 테넌트(1번)를 쓰는 다른 테스트의 {@code resolve()} 가 영향을 받는다. 이 키는
 * package-private({@code AiCredentialService.KEY}, {@code settings.service} 패키지 전용)이라
 * 이 클래스({@code com.smartfirehub.ai})에서는 리터럴을 쓴다.
 */
class AmbientKeyNeverUsedTest extends IntegrationTestBase {

  private static final Long USER = null;
  private static final String KEY = "ai.credential";

  @Autowired private AiAgentClient aiAgentClient;
  @Autowired private AiCredentialService aiCredentialService;
  @Autowired private TenantSettingsRepository tenantSettingsRepository;
  @Autowired private DSLContext dsl;
  @Autowired private EncryptionService encryptionService;

  private String platformOriginal;

  @BeforeEach
  void captureOriginalPlatformValue() {
    platformOriginal = SettingsTestSupport.rawSystemSettingValue(dsl, KEY);
  }

  @AfterEach
  void cleanup() {
    tenantSettingsRepository.delete(KEY);
    // opencode 가드 테스트가 심는 테넌트 오버라이드 정리 — 남으면 기본 테넌트를 쓰는 다른
    // 테스트의 ai.model 조회에 영향을 준다.
    tenantSettingsRepository.delete("ai.model");
    SettingsTestSupport.restoreSystemSettingValue(dsl, KEY, platformOriginal);
  }

  private AiAgentClient.ClassifyRequest classifyRequest() {
    return new AiAgentClient.ClassifyRequest(
        List.of(Map.of("id", 1, "text", "A"), Map.of("id", 2, "text", "B")),
        "text",
        List.of(Map.of("name", "label", "type", "TEXT")));
  }

  /**
   * 이것이 없으면 ai-agent 가 컨테이너의 ambient {@code ANTHROPIC_API_KEY} 로 폴백한다 — 이
   * 브랜치가 막으려는 과금 혼입 그 자체다. {@code Opencode.apiKey}(OpenAI 호환 키)가 바디에
   * 실려야 하고, {@code providerId}/{@code baseUrl} 도 함께 실려야 한다.
   */
  @Test
  void opencode_자격증명이면_요청_바디에_provider_설정이_실린다() {
    aiCredentialService.save(
        new AiCredentialUpsert(
            "opencode",
            Map.of("providerId", "openai", "baseURL", "https://api.openai.com/v1"),
            Map.of("apiKey", "sk-oai")),
        USER);
    // ai.model 을 opencode 형식(providerId/modelId)으로 맞춰 둔다 — 안 그러면 기본값
    // "claude-sonnet-5"(슬래시 없음)이 새 모델 형식 가드(전체 브랜치 리뷰 I3)에 걸려 이 테스트가
    // 검증하려는 바디 조립까지 못 간다.
    tenantSettingsRepository.upsert("ai.model", "openai/gpt-4o", USER);

    Map<String, Object> body = aiAgentClient.buildClassifyBody(classifyRequest());

    assertThat(body).containsEntry("agentType", "opencode");
    assertThat(body).containsEntry("providerId", "openai");
    assertThat(body).containsEntry("baseUrl", "https://api.openai.com/v1");
    assertThat(body).containsEntry("apiKey", "sk-oai");
  }

  /**
   * (전체 브랜치 리뷰 I3) opencode 모델 형식 가드가 분류 경로에도 있어야 한다 — chat
   * (AiAgentProxyService:274-292) 에는 이미 있던 검사다. ai.model 을 아예 설정하지 않으면
   * 기본값 "claude-sonnet-5" 가 그대로 쓰이는데, 슬래시가 없어 opencode 형식이 아니다.
   */
  @Test
  void opencode_자격증명인데_ai_model이_opencode_형식이_아니면_명시적으로_실패한다() {
    aiCredentialService.save(
        new AiCredentialUpsert(
            "opencode",
            Map.of("providerId", "openai", "baseURL", "https://api.openai.com/v1"),
            Map.of("apiKey", "sk-oai")),
        USER);
    // ai.model 미설정 — AiAgentClient 의 기본값("claude-sonnet-5")이 그대로 쓰인다.

    assertThatThrownBy(() -> aiAgentClient.buildClassifyBody(classifyRequest()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("opencode 형식");
  }

  @Test
  void sdk_자격증명이면_oauth_우선으로_실린다() {
    aiCredentialService.save(
        new AiCredentialUpsert("sdk", Map.of(), Map.of("oauthToken", "oat", "apiKey", "sk-ant")),
        USER);

    Map<String, Object> body = aiAgentClient.buildClassifyBody(classifyRequest());

    assertThat(body).containsEntry("oauthToken", "oat").containsEntry("apiKey", "sk-ant");
    assertThat(body).doesNotContainKey("baseUrl");
    assertThat(body).doesNotContainKey("providerId");
  }

  /**
   * (리뷰 라운드 1 지적) {@code buildClassifyBody} 의 4개 분기 중 {@code Cli}/{@code CliApi} 는
   * 지금까지 어떤 테스트도 보지 않았다 — 이 브랜치의 아홉 뮤턴트는 전부 Sdk/Opencode 만
   * 건드렸다. 여기서는 {@code cli} 가 {@code oauthToken} 없이 바디에 실리면(뮤턴트: 그 줄을
   * 지움) 잡아야 한다.
   */
  @Test
  void cli_자격증명이면_oauthToken이_실리고_apiKey는_없다() {
    aiCredentialService.save(new AiCredentialUpsert("cli", Map.of(), Map.of("oauthToken", "cli-oat")), USER);

    Map<String, Object> body = aiAgentClient.buildClassifyBody(classifyRequest());

    assertThat(body).containsEntry("agentType", "cli");
    assertThat(body).containsEntry("oauthToken", "cli-oat");
    assertThat(body).doesNotContainKey("apiKey");
  }

  /**
   * (리뷰 라운드 1 지적) {@code CliApi} 분기가 비어 있으면 — 뮤턴트: {@code agentType} 을
   * {@code "sdk"} 로 잘못 싣고 {@code apiKey} 를 아예 안 실음 — ai-agent 가 컨테이너의 ambient
   * {@code ANTHROPIC_API_KEY} 로 폴백한다. 6b1c6383 회귀 그 자체(과금 혼입)라 반드시 직접
   * 검증한다.
   */
  @Test
  void cliApi_자격증명이면_agentType이_cli_api이고_apiKey가_실린다() {
    aiCredentialService.save(new AiCredentialUpsert("cli-api", Map.of(), Map.of("apiKey", "sk-cliapi")), USER);

    Map<String, Object> body = aiAgentClient.buildClassifyBody(classifyRequest());

    assertThat(body).containsEntry("agentType", "cli-api");
    assertThat(body).containsEntry("apiKey", "sk-cliapi");
    assertThat(body).doesNotContainKey("oauthToken");
  }

  /**
   * #706 — 테넌트 행이 없으면 분류는 요청 바디를 만들기 전에 "설정되지 않았다" 문구로 실패한다.
   * {@code system_settings} 에 <b>완전한</b> 플랫폼 자격증명을 심어 두는 것이 핵심이다 — 그 행을
   * 읽는 폴백이 되살아나면 바디가 그 키로 조립돼 이 테스트가 RED 가 된다(행 없이 확인하면 공허하다).
   */
  @Test
  void 테넌트_자격증명이_없으면_플랫폼_행이_있어도_분류가_안내_문구로_실패한다() {
    upsertSystemSetting(
        dsl,
        KEY,
        "{\"v\":1,\"agentType\":\"sdk\",\"payload\":{},\"secret\":{\"apiKey\":\""
            + encryptionService.encrypt("sk-platform-must-not-leak")
            + "\"}}");

    assertThatThrownBy(() -> aiAgentClient.buildClassifyBody(classifyRequest()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(new AiCredential.Sdk("", "").incompleteMessage());
  }

  /**
   * 손으로 고친 행/롤백된 배포를 흉내낸다 — 서비스를 거치지 않고 저장소에 직접 쓴다
   * ({@code AiCredentialServiceTest.seedTenantRaw} 와 같은 패턴).
   */
  @Test
  void 알수없는_유형이면_분류가_명시적으로_실패한다() {
    tenantSettingsRepository.upsert(
        KEY, "{\"v\":1,\"agentType\":\"martian\",\"payload\":{},\"secret\":{}}", USER);

    assertThatThrownBy(() -> aiAgentClient.buildClassifyBody(classifyRequest()))
        .isInstanceOf(UnknownAgentTypeException.class);
  }
}
