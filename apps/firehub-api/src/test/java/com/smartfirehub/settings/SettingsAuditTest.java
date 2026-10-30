package com.smartfirehub.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.settings.ChannelSettingsService;
import com.smartfirehub.settings.model.AiBehaviorDefaults;
import com.smartfirehub.settings.model.AiCredentialSlot;
import com.smartfirehub.settings.repository.TenantSettingsRepository;
import com.smartfirehub.settings.service.AiCredentialService;
import com.smartfirehub.settings.service.AiCredentialService.AiCredentialUpsert;
import com.smartfirehub.settings.service.SettingsOverridePolicy;
import com.smartfirehub.settings.service.SettingsService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.user.exception.UserNotFoundException;
import com.smartfirehub.user.service.UserService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * AI 자격증명·AI 설정·SMTP 설정·알림 채널 사용·프로필 변경 감사(WD-465) 통합 테스트.
 *
 * <p>검증: 각 행위가 audit_log 에 정확히 한 행(기대 resource·action·changedFields)을 남기고, 바뀐 것이 없는 저장·이미 미설정인 해제와
 * 거부된 요청(400·404)은 아무것도 남기지 않으며, API 키·OAuth 토큰·SMTP 비밀번호·시스템 프롬프트 같은 값이 설명·metadata 에 새지 않는다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 서비스 트랜잭션의 실제 커밋·롤백으로 감사가 남거나 남지 않는지 본다. 기본 테넌트(1번)의
 * 설정 행은 다른 테스트 클래스와 공유되므로 시작·끝에 이 테스트가 다루는 키를 모두 지운다.
 */
class SettingsAuditTest extends IntegrationTestBase {

  /** 값 누출 검사용 표식 — 비밀·프롬프트 값에 넣고 감사 설명·metadata 에 없어야 한다. */
  private static final String MARKER = "MARKER_WD465_SECRET";

  @Autowired private AiCredentialService aiCredentialService;
  @Autowired private SettingsService settingsService;
  @Autowired private ChannelSettingsService channelSettingsService;
  @Autowired private UserService userService;
  @Autowired private TenantSettingsRepository tenantSettingsRepository;
  @Autowired private DSLContext dsl;
  @Autowired private ObjectMapper objectMapper;

  private Long actor;

  /** 감사 행 한 줄(검증용 투영). */
  private record Audit(
      String action,
      String resource,
      String resourceId,
      String username,
      String description,
      JsonNode metadata) {}

  @BeforeEach
  void setUp() {
    actor = TenantRlsTestSupport.insertUser(dsl, "wd465_actor");
    clearSettingRows();
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    clearSettingRows();
    // 감사 행은 user_id FK 라 사용자보다 먼저 지운다(채널 설정 행은 사용자 삭제에 CASCADE).
    inTenantFixture(() -> dsl.execute("DELETE FROM audit_log WHERE user_id = ?", actor));
    TenantRlsTestSupport.deleteUser(dsl, actor);
  }

  /** 이 테스트가 다루는 테넌트 설정 행(자격증명 두 슬롯 + 분류 모델 + AI 동작 6키 + SMTP 6키)을 지운다. */
  private void clearSettingRows() {
    List<String> keys = new ArrayList<>(AiCredentialSlot.ownedKeys());
    keys.addAll(AiBehaviorDefaults.keys());
    keys.addAll(SettingsOverridePolicy.smtpKeys());
    inTenantFixture(() -> keys.forEach(tenantSettingsRepository::delete));
  }

  // ── 도우미 ────────────────────────────────────────────────────────────────

  private List<Audit> audits(String resource) {
    return inTenantFixture(
        () ->
            dsl.fetch(
                    "SELECT action_type, resource, resource_id, username, description,"
                        + " metadata::text AS m FROM audit_log WHERE user_id = ? AND resource = ?"
                        + " ORDER BY id",
                    actor,
                    resource)
                .map(
                    r ->
                        new Audit(
                            r.get(0, String.class),
                            r.get(1, String.class),
                            r.get(2, String.class),
                            r.get(3, String.class),
                            r.get(4, String.class),
                            readJson(r.get(5, String.class)))));
  }

  /** 이 사용자의 감사 행 전체 수 — 거부·무변경 요청이 어느 resource 로도 남지 않았는지 본다. */
  private int allAuditCount() {
    return inTenantFixture(
        () -> dsl.fetchCount(dsl.selectFrom("audit_log").where("user_id = ?", actor)));
  }

  private JsonNode readJson(String json) {
    try {
      return json == null ? null : objectMapper.readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void assertNoLeak(List<Audit> rows) {
    for (Audit a : rows) {
      assertThat(a.description()).doesNotContain(MARKER);
      assertThat(String.valueOf(a.metadata())).doesNotContain(MARKER);
    }
  }

  private static List<String> changedFields(Audit a) {
    List<String> out = new ArrayList<>();
    a.metadata().get("changedFields").forEach(n -> out.add(n.asText()));
    return out;
  }

  private static AiCredentialUpsert sdk(String apiKey) {
    return new AiCredentialUpsert("sdk", Map.of(), Map.of("apiKey", apiKey));
  }

  // ── AI 자격증명 ───────────────────────────────────────────────────────────

  @Test
  void 채팅_자격증명_첫_저장은_등록_한_행이고_키_값은_남지_않는다() {
    aiCredentialService.save(sdk(MARKER + "-key-1"), actor);

    List<Audit> rows = audits("ai_credential");
    assertThat(rows).hasSize(1);
    Audit a = rows.get(0);
    assertThat(a.action()).isEqualTo("CREATE");
    assertThat(a.resourceId()).isEqualTo("CHAT");
    assertThat(a.username()).startsWith("wd465_actor");
    assertThat(a.description()).isEqualTo("AI 자격증명 등록: 채팅");
    assertThat(a.metadata().get("agentType").asText()).isEqualTo("sdk");
    assertNoLeak(rows);
  }

  @Test
  void 같은_키를_다시_저장하면_남지_않고_키를_바꾸면_수정_한_행에_항목_이름만_남는다() {
    aiCredentialService.save(sdk(MARKER + "-key-1"), actor);
    // 암호문은 저장마다 IV 가 달라지지만 평문이 같으므로 변경이 아니다.
    aiCredentialService.save(sdk(MARKER + "-key-1"), actor);
    // 생략 = 유지 — 아무것도 바뀌지 않는다.
    aiCredentialService.save(new AiCredentialUpsert("sdk", Map.of(), Map.of()), actor);
    assertThat(audits("ai_credential")).hasSize(1);

    aiCredentialService.save(sdk(MARKER + "-key-2"), actor);

    List<Audit> rows = audits("ai_credential");
    assertThat(rows).hasSize(2);
    Audit a = rows.get(1);
    assertThat(a.action()).isEqualTo("UPDATE");
    assertThat(changedFields(a)).containsExactly("apiKey");
    assertThat(a.description()).isEqualTo("AI 자격증명 수정: 채팅 (바뀐 항목: API 키)");
    assertNoLeak(rows);
  }

  @Test
  void 유형을_바꾸면_유형과_비밀_이름이_바뀐_항목으로_남는다() {
    aiCredentialService.save(sdk(MARKER + "-key-1"), actor);
    aiCredentialService.save(
        new AiCredentialUpsert("cli", Map.of(), Map.of("oauthToken", MARKER + "-oat")), actor);

    Audit a = audits("ai_credential").get(1);
    assertThat(a.action()).isEqualTo("UPDATE");
    assertThat(changedFields(a)).containsExactlyInAnyOrder("agentType", "apiKey", "oauthToken");
    assertThat(a.metadata().get("agentType").asText()).isEqualTo("cli");
    assertNoLeak(audits("ai_credential"));
  }

  @Test
  void 거부된_자격증명_저장은_감사를_남기지_않는다() {
    // 알 수 없는 유형(400)
    assertThatThrownBy(
            () ->
                aiCredentialService.save(
                    new AiCredentialUpsert("martian", Map.of(), Map.of()), actor))
        .isInstanceOf(IllegalArgumentException.class);
    // 비밀 없는 sdk(400)
    assertThatThrownBy(
            () ->
                aiCredentialService.save(new AiCredentialUpsert("sdk", Map.of(), Map.of()), actor))
        .isInstanceOf(IllegalArgumentException.class);
    // 분류 모델 공백(400)
    assertThatThrownBy(() -> aiCredentialService.saveClassify(sdk(MARKER), " ", actor))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(allAuditCount()).isZero();
  }

  @Test
  void 분류_자격증명_등록_모델_변경_삭제가_각각_한_행이고_빈_해제는_남지_않는다() {
    aiCredentialService.saveClassify(sdk(MARKER + "-c1"), "claude-haiku", actor);
    aiCredentialService.saveClassify(sdk(MARKER + "-c1"), "claude-haiku", actor); // 무변경
    aiCredentialService.saveClassify(sdk(MARKER + "-c1"), "claude-sonnet", actor);
    aiCredentialService.clearClassify(actor);
    aiCredentialService.clearClassify(actor); // 이미 미설정 — 멱등 해제

    List<Audit> rows = audits("ai_credential");
    assertThat(rows).extracting(Audit::action).containsExactly("CREATE", "UPDATE", "DELETE");
    assertThat(rows).extracting(Audit::resourceId).containsOnly("CLASSIFY");
    assertThat(changedFields(rows.get(1))).containsExactly("model");
    assertThat(rows.get(2).description()).isEqualTo("AI 자격증명 삭제: 분류");
    assertThat(rows.get(2).metadata().get("agentType").asText()).isEqualTo("sdk");
    assertNoLeak(rows);
  }

  // ── AI 동작 설정 ──────────────────────────────────────────────────────────

  @Test
  void AI_설정을_기본값_그대로_저장하면_남지_않고_바꾼_키_이름만_한_행에_남는다() {
    Map<String, String> defaults = new HashMap<>();
    AiBehaviorDefaults.keys().forEach(k -> defaults.put(k, AiBehaviorDefaults.defaultOf(k)));
    settingsService.updateSettings(defaults, actor);
    assertThat(audits("ai_settings")).isEmpty();

    Map<String, String> changed = new HashMap<>(defaults);
    changed.put("ai.model", "claude-opus-test");
    changed.put("ai.system_prompt", MARKER + " 프롬프트 본문");
    settingsService.updateSettings(changed, actor);
    settingsService.updateSettings(changed, actor); // 같은 값 다시 저장 — 무변경

    List<Audit> rows = audits("ai_settings");
    assertThat(rows).hasSize(1);
    Audit a = rows.get(0);
    assertThat(a.action()).isEqualTo("UPDATE");
    assertThat(a.resourceId()).isEqualTo("ai");
    assertThat(changedFields(a)).containsExactly("ai.model", "ai.system_prompt");
    assertThat(a.description()).isEqualTo("AI 설정 수정 (바뀐 항목: 모델·시스템 프롬프트)");
    assertNoLeak(rows);
  }

  @Test
  void 거부된_AI_설정_저장은_감사를_남기지_않는다() {
    assertThatThrownBy(
            () ->
                settingsService.updateSettings(Map.of("ai.model", "x", "ai.max_turns", "0"), actor))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(allAuditCount()).isZero();
  }

  // ── SMTP 설정 ─────────────────────────────────────────────────────────────

  private static Map<String, String> smtp(String password) {
    return Map.of(
        "smtp.host", "smtp.example.com",
        "smtp.port", "587",
        "smtp.username", "mailer",
        "smtp.password", password,
        "smtp.starttls", "true",
        "smtp.from_address", "noreply@example.com");
  }

  @Test
  void SMTP_등록_수정_삭제가_각각_한_행이고_비밀번호_값은_남지_않는다() {
    settingsService.updateSettings(smtp(MARKER + "-pw1"), actor);
    // 같은 평문 비밀번호·마스크 센티널은 변경이 아니다.
    settingsService.updateSettings(smtp(MARKER + "-pw1"), actor);
    settingsService.updateSettings(Map.of("smtp.password", "****"), actor);
    settingsService.updateSettings(Map.of("smtp.password", MARKER + "-pw2"), actor);
    settingsService.clearSmtpSettings(actor);
    settingsService.clearSmtpSettings(actor); // 이미 미설정 — 멱등 해제

    List<Audit> rows = audits("smtp_settings");
    assertThat(rows).extracting(Audit::action).containsExactly("CREATE", "UPDATE", "DELETE");
    assertThat(rows).extracting(Audit::resourceId).containsOnly("smtp");
    assertThat(changedFields(rows.get(0))).hasSize(6).contains("smtp.password");
    assertThat(changedFields(rows.get(1))).containsExactly("smtp.password");
    assertThat(rows.get(1).description()).isEqualTo("SMTP 설정 수정 (바뀐 항목: 비밀번호)");
    assertThat(rows.get(2).description()).isEqualTo("SMTP 설정 삭제");
    assertThat(changedFields(rows.get(2))).hasSize(6);
    assertNoLeak(rows);
  }

  @Test
  void 거부된_SMTP_저장은_감사를_남기지_않는다() {
    assertThatThrownBy(
            () ->
                settingsService.updateSettings(
                    Map.of("smtp.host", "smtp.example.com", "smtp.port", "99999"), actor))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(allAuditCount()).isZero();
  }

  @Test
  void AI_와_SMTP_키를_한_번에_저장하면_묶음별로_한_행씩_남는다() {
    settingsService.updateSettings(
        Map.of("ai.max_turns", "7", "smtp.host", "smtp.example.com"), actor);

    assertThat(audits("ai_settings"))
        .singleElement()
        .satisfies(a -> assertThat(changedFields(a)).containsExactly("ai.max_turns"));
    assertThat(audits("smtp_settings"))
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.action()).isEqualTo("CREATE");
              assertThat(changedFields(a)).containsExactly("smtp.host");
            });
  }

  // ── 알림 채널 사용 설정 ───────────────────────────────────────────────────

  @Test
  void 알림_채널_끄기_켜기가_상태_변경으로_남고_같은_상태로_바꾸면_남지_않는다() {
    channelSettingsService.updatePreference(actor, ChannelType.EMAIL, false);
    channelSettingsService.updatePreference(actor, ChannelType.EMAIL, false); // 이미 꺼짐
    channelSettingsService.updatePreference(actor, ChannelType.EMAIL, true);
    channelSettingsService.updatePreference(actor, ChannelType.SLACK, true); // 기본값이 켜짐

    List<Audit> rows = audits("user_channel_preference");
    assertThat(rows).extracting(Audit::action).containsExactly("STATUS_CHANGE", "STATUS_CHANGE");
    assertThat(rows).extracting(Audit::resourceId).containsOnly("EMAIL");
    assertThat(rows.get(0).description()).isEqualTo("알림 채널 사용 끄기: 이메일");
    assertThat(rows.get(1).description()).isEqualTo("알림 채널 사용 켜기: 이메일");
    assertThat(changedFields(rows.get(0))).containsExactly("enabled");
    assertThat(rows.get(0).metadata().get("enabled").asBoolean()).isFalse();
  }

  @Test
  void 거부된_채널_변경은_감사를_남기지_않는다() {
    assertThatThrownBy(
            () -> channelSettingsService.updatePreference(actor, ChannelType.CHAT, false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(allAuditCount()).isZero();
  }

  // ── 프로필 ────────────────────────────────────────────────────────────────

  @Test
  void 프로필_수정은_바뀐_항목_이름만_남기고_값은_남기지_않는다() {
    String newEmail = MARKER.toLowerCase() + "@example.com";
    userService.updateProfile(actor, "Test User", newEmail); // 이름은 그대로, 이메일만 변경
    userService.updateProfile(actor, "Test User", newEmail); // 무변경

    List<Audit> rows = audits("user");
    assertThat(rows).hasSize(1);
    Audit a = rows.get(0);
    assertThat(a.action()).isEqualTo("UPDATE");
    assertThat(a.resourceId()).isEqualTo(String.valueOf(actor));
    assertThat(changedFields(a)).containsExactly("email");
    assertThat(a.description()).startsWith("프로필 수정: wd465_actor").endsWith("(바뀐 항목: 이메일)");
    assertThat(a.description()).doesNotContainIgnoringCase(MARKER);
    assertThat(String.valueOf(a.metadata())).doesNotContainIgnoringCase(MARKER);
  }

  @Test
  void 없는_사용자의_프로필_수정은_감사를_남기지_않는다() {
    assertThatThrownBy(() -> userService.updateProfile(-1L, "x", "x@example.com"))
        .isInstanceOf(UserNotFoundException.class);
    assertThat(
            inTenantFixture(
                () ->
                    dsl.fetchCount(
                        dsl.selectFrom("audit_log")
                            .where("resource = 'user' AND resource_id = '-1'"))))
        .isZero();
  }
}
