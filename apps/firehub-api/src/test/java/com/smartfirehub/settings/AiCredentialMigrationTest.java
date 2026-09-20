package com.smartfirehub.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.settings.model.AiCredential;
import com.smartfirehub.settings.model.AiCredentialDocument;
import com.smartfirehub.settings.service.AiCredentialService;
import com.smartfirehub.settings.service.AiCredentialService.AiCredentialView;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * V122 마이그레이션(옛 3키 {@code ai.api_key}/{@code ai.cli_oauth_token}/{@code ai.agent_type} →
 * {@code ai.credential} 통합) 검증.
 *
 * <p><b>왜 SQL 을 "재생(replay)"하는가.</b> Flyway 는 forward-only 라 테스트 DB 부팅 시 V122 가
 * 이미 적용돼 있다. 그런데 부팅 시점의 {@code tenant_settings} 는 0행이다(어떤 마이그레이션도
 * 테넌트 평면에 옛 3키를 시드하지 않는다) — 그래서 테넌트 평면 변환 로직은 "실제로 부팅된 결과"만
 * 봐서는 검증할 수 없다. 이 테스트는 V122 파일 본문을 <b>그대로 읽어</b> 잘라낸 문(statement)을
 * 재실행한다 — 변환 규칙을 테스트 쪽에 다시 베껴 쓰지 않으므로, 파일이 바뀌면(뮤테이션이든 실수든)
 * 이 테스트도 그대로 반응한다.
 *
 * <p><b>테넌트 평면 재생은 RLS 로 안전하다.</b> {@code app_tenant} 커넥션에서 GUC 를 테스트 전용
 * 테넌트로 좁혀 두고 재생하므로, {@code INSERT ... SELECT ... GROUP BY} 가 "보는" 행은 그 테넌트
 * 행뿐이다 — 공유 테스트 DB 의 다른 테넌트를 건드리지 않는다(V114 의 RLS 정책이 owner 가 아닌
 * app_tenant 에는 그대로 걸린다). 반대로 플랫폼 평면({@code system_settings})은 RLS 가 없는 전역
 * 단일 행 집합이라 이렇게 격리할 수 없다 — {@link #플랫폼_평면도_같은_규칙으로_변환된다()} 가
 * 따로 다룬다.
 */
class AiCredentialMigrationTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private EncryptionService encryptionService;
  @Autowired private AiCredentialService aiCredentialService;

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String MIGRATION_SQL = loadMigrationSql();

  // V122 파일 본문에서 잘라낸 문 3개(가드 DO 블록 / 테넌트 INSERT / 변환 행 수 단언 DO 블록).
  // 플랫폼 INSERT 는 별도 필드(PLATFORM_INSERT_SQL)로 아래에 둔다 — system_settings 는 이미
  // 부팅 시 실행된 결과가 있어 재생 전 기존 행을 지워야 하므로 쓰임새가 다르다.
  private static final String GUARD_SQL;
  private static final String TENANT_INSERT_SQL;
  private static final String ASSERT_SQL;
  private static final String PLATFORM_INSERT_SQL;

  static {
    GUARD_SQL = segment(MIGRATION_SQL, "DO $$", 0, "END $$;");
    TENANT_INSERT_SQL = segment(MIGRATION_SQL, "INSERT INTO tenant_settings", 0, ";");
    int afterGuard = MIGRATION_SQL.indexOf("END $$;") + "END $$;".length();
    ASSERT_SQL = segment(MIGRATION_SQL, "DO $$", afterGuard, "END $$;");
    PLATFORM_INSERT_SQL = segment(MIGRATION_SQL, "INSERT INTO system_settings", 0, ";");
  }

  // 이 테스트가 만든 테넌트만 정리한다 — 공유 테스트 DB 의 다른 세션 픽스처와 섞이지 않는다.
  private final List<Long> createdTenants = new ArrayList<>();

  @AfterEach
  void cleanUpTenants() {
    if (!createdTenants.isEmpty()) {
      TenantRlsTestSupport.deleteTenants(dsl, createdTenants.toArray(new Long[0]));
      createdTenants.clear();
    }
  }

  // ---------- 헬퍼 ----------

  private static String loadMigrationSql() {
    try (InputStream in =
        AiCredentialMigrationTest.class.getResourceAsStream("/db/migration/V122__ai_credential.sql")) {
      if (in == null) throw new IllegalStateException("V122 마이그레이션 파일을 찾을 수 없다");
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * {@code full} 안에서 {@code startMarker}(첫 등장, {@code fromIndex} 이후) ~ {@code endMarker}
   * 까지를 잘라낸다.
   *
   * <p><b>취약점(실측했다).</b> {@code GUARD_SQL}/{@code ASSERT_SQL} 은 둘 다 "DO $$ ... END $$;"
   * 모양이라 이 헬퍼가 마커로 구분하지 못한다 — {@code fromIndex} 로 순서를 강제해 구분할 뿐이다.
   * 파일에서 가드 DO 블록 전체를 지우는 뮤테이션 검사를 실제로 돌려 보니(2026-09-19), "DO $$"가
   * 파일에 하나만 남아 {@code ASSERT_SQL} 을 잘라내는 {@code segment(..., "DO $$", afterGuard,
   * ...)} 호출이 시작 마커를 못 찾고 {@link IllegalStateException} 을 던졌다 — 이게 정적 초기화
   * 블록 안에서 일어나 클래스 전체가 {@code ExceptionInInitializerError} 로 로드 실패한다(가드
   * 테스트 하나만이 아니라 이 클래스의 7개 테스트 전부가 그 실행에서 함께 깨진다). 여전히 RED 는
   * 뜨지만 "opencode" 메시지와는 전혀 무관한 이유이고 실패 범위도 훨씬 넓다 — 파일에 테스트
   * 전용 앵커 주석을 넣지 않기로 한 대가다. 이 파일이 "DO $$ 블록 정확히 2개" 불변식을 벗어나면
   * (블록을 지우든 더 추가하든) 이 헬퍼부터 다시 봐야 한다.
   */
  private static String segment(String full, String startMarker, int fromIndex, String endMarker) {
    int start = full.indexOf(startMarker, fromIndex);
    if (start < 0) throw new IllegalStateException("시작 마커를 찾지 못했다: " + startMarker);
    int end = full.indexOf(endMarker, start);
    if (end < 0) throw new IllegalStateException("종료 마커를 찾지 못했다: " + endMarker);
    return full.substring(start, end + endMarker.length());
  }

  private long newTenant(String slugPrefix) {
    long tenantId = TenantRlsTestSupport.createActiveTenant(dsl, slugPrefix);
    createdTenants.add(tenantId);
    return tenantId;
  }

  /** 옛 3키 중 하나를 테넌트 평면에 직접 심는다(픽스처 — 마이그레이션 이전 상태 재현). */
  private void insertLegacyRow(long tenantId, String key, String value) {
    inTenantFixture(
        tenantId,
        () ->
            dsl.execute(
                "insert into tenant_settings (tenant_id, key, value, updated_by, updated_at) "
                    + "values (?, ?, ?, null, now())",
                tenantId,
                key,
                value));
  }

  /** V122 의 테넌트 평면 INSERT + 변환 행 수 단언을 파일 그대로 재생한다(실제 마이그레이션과 같은 순서). */
  private void replayTenantPlane(long tenantId) {
    inTenantFixture(
        tenantId,
        () -> {
          dsl.execute(TENANT_INSERT_SQL);
          dsl.execute(ASSERT_SQL);
        });
  }

  private Optional<String> rawTenantValue(long tenantId, String key) {
    return inTenantFixture(
        tenantId,
        () ->
            Optional.ofNullable(
                    dsl.fetchOne(
                        "select value from tenant_settings where tenant_id = ? and key = ?", tenantId, key))
                .map(r -> r.get(0, String.class)));
  }

  private Optional<String> rawPlatformValue(String key) {
    return Optional.ofNullable(dsl.fetchOne("select value from system_settings where key = ?", key))
        .map(r -> r.get(0, String.class));
  }

  private JsonNode parseJson(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // ---------- 테스트 ----------

  @Test
  @DisplayName("번들 행이 하나도 없으면 credential 을 만들지 않는다 (상속 유지)")
  void 번들_행이_하나도_없으면_credential_을_만들지_않는다() {
    // 상속 중인 테넌트를 소유자로 바꿔 버리면 화면이 "우리 조직이 직접 설정"이라고 거짓말하고
    // 플랫폼 키 로테이션도 이 테넌트에는 더 이상 닿지 않는다.
    long tenantId = newTenant("aicred-inherit");

    replayTenantPlane(tenantId);

    assertThat(rawTenantValue(tenantId, "ai.credential")).isEmpty();
  }

  @Test
  @DisplayName("agent_type 만 있어도 1단계 채움 규칙대로 유형별 변환된다")
  void agent_type만_있으면_1단계_채움_규칙대로_변환된다() {
    long tenantId = newTenant("aicred-typeonly");
    insertLegacyRow(tenantId, "ai.agent_type", "cli-api");

    replayTenantPlane(tenantId);

    String json = rawTenantValue(tenantId, "ai.credential").orElseThrow();
    JsonNode doc = parseJson(json);
    // v/payload 는 저장 형태의 필수 필드다 — AiCredentialDocument.parse 는 v 를 검증하지 않고
    // objectChild 가 없는 payload 를 조용히 빈 객체로 합성하므로, 원본 JSON 을 직접 파싱해서
    // 확인해야 "필드를 아예 안 넣어도 통과"하는 뮤테이션을 잡는다. v 는 미래 V(n+1) 이 스키마
    // 버전을 판별할 유일한 자리라 forward-only 세계에서 한 번 잘못 박히면 제자리에서 못 고친다.
    assertThat(doc.path("v").asInt()).isEqualTo(1);
    assertThat(doc.has("payload")).isTrue();
    assertThat(doc.path("agentType").asText()).isEqualTo("cli-api");
    // 자격증명 2키는 채움 규칙대로 빈 문자열이어야 한다 — secretNames 는 그 값을 "미설정"으로 읽는다.
    AiCredentialDocument parsed = AiCredentialDocument.parse(json);
    assertThat(parsed.secretCipher("apiKey")).isEmpty();
    assertThat(parsed.secretCipher("oauthToken")).isEmpty();

    // 해석 결과(AiCredentialService.resolve())도 같은 유형으로 나와야 한다 — 저장 형태뿐 아니라
    // 실제 소비 경로까지 맞는지 확인한다.
    AiCredential resolved = TenantContext.runScopedGet(tenantId, aiCredentialService::resolve);
    assertThat(resolved).isInstanceOf(AiCredential.CliApi.class);
    assertThat(((AiCredential.CliApi) resolved).apiKey()).isEmpty();
  }

  @Test
  @DisplayName("암호문은 재암호화 없이 그대로 옮겨지고, 해석 결과도 이전과 같다")
  void 암호문은_그대로_옮겨지고_해석_결과도_같다() {
    // 값 단위 AES 라 재암호화가 필요 없다 — 문자열이 조금이라도 바뀌면 복호화가 깨진다.
    String apiKeyCipher = encryptionService.encrypt("sk-test-real-key");
    String oauthCipher = encryptionService.encrypt("oauth-test-token");

    long tenantId = newTenant("aicred-cipher");
    insertLegacyRow(tenantId, "ai.agent_type", "sdk");
    insertLegacyRow(tenantId, "ai.api_key", apiKeyCipher);
    insertLegacyRow(tenantId, "ai.cli_oauth_token", oauthCipher);

    replayTenantPlane(tenantId);

    String json = rawTenantValue(tenantId, "ai.credential").orElseThrow();
    assertThat(parseJson(json).path("v").asInt()).isEqualTo(1);
    assertThat(parseJson(json).has("payload")).isTrue();
    AiCredentialDocument parsed = AiCredentialDocument.parse(json);
    assertThat(parsed.agentType()).isEqualTo("sdk");
    // 암호문 바이트 동일성 — 재암호화됐다면 (IV 가 매번 랜덤이라) 절대 같을 수 없다.
    assertThat(parsed.secretCipher("apiKey")).isEqualTo(apiKeyCipher);
    assertThat(parsed.secretCipher("oauthToken")).isEqualTo(oauthCipher);

    // 적용되는 값이 변환 전후로 같다 — 이 마이그레이션의 성공 기준.
    AiCredential resolved = TenantContext.runScopedGet(tenantId, aiCredentialService::resolve);
    assertThat(resolved).isEqualTo(new AiCredential.Sdk("oauth-test-token", "sk-test-real-key"));
  }

  @Test
  @DisplayName("ai.model 행은 건드리지 않는다 — 자격증명이 아니라 평면 설정 키로 남는다")
  void ai_model_행은_건드리지_않는다() {
    long tenantId = newTenant("aicred-modelonly");
    insertLegacyRow(tenantId, "ai.model", "claude-haiku-4-5");

    replayTenantPlane(tenantId);

    assertThat(rawTenantValue(tenantId, "ai.model")).contains("claude-haiku-4-5");
    assertThat(rawTenantValue(tenantId, "ai.credential")).isEmpty();
  }

  @Test
  @DisplayName("빈 비밀의 두 형태(평문 '' / 빈 문자열의 암호문) 모두 '미설정'으로 읽힌다")
  void 빈_비밀의_두_형태_모두_미설정으로_읽힌다() {
    // V31/V41 은 시스템 설정에 리터럴 ''를 시드했고(평문), encryptIfSecret 은 같은 두 키에 한해
    // 빈 값도 암호화했다(암호문). 마이그레이션은 이 둘을 구분하지 않고 그대로 복사만 한다 — 판정은
    // AiCredentialDocument.secretNames/AiCredentialService.decryptOrEmpty(ForDisplay) 가 "복호화
    // 결과가 비어 있는지"로 이미 하고 있다(Task 1/3, 이 마이그레이션 이전에 커밋됨).
    long tenantId = newTenant("aicred-twoblanks");
    insertLegacyRow(tenantId, "ai.agent_type", "sdk");
    insertLegacyRow(tenantId, "ai.api_key", ""); // 평문 빈 문자열 형태
    insertLegacyRow(tenantId, "ai.cli_oauth_token", encryptionService.encrypt("")); // 빈 값의 암호문 형태

    replayTenantPlane(tenantId);

    // tenantOwned/원본 행 존재를 먼저 못박는다 — 이게 없으면 테넌트 INSERT 가 아무것도 안 써도
    // read()/resolve() 가 플랫폼 값으로 폴백해 우연히 통과한다(플랫폼도 세 키가 빈 값이라 결과가
    // 같다). 이 단언이 실제로 테넌트 행을 봤다는 것을 고정한다.
    assertThat(rawTenantValue(tenantId, "ai.credential")).isPresent();
    AiCredentialView view = TenantContext.runScopedGet(tenantId, aiCredentialService::read);
    assertThat(view.tenantOwned()).isTrue();
    assertThat(view.secretFieldNames()).doesNotContain("apiKey", "oauthToken");

    AiCredential resolved = TenantContext.runScopedGet(tenantId, aiCredentialService::resolve);
    assertThat(resolved).isEqualTo(new AiCredential.Sdk("", ""));
  }

  @Test
  @DisplayName("opencode 테넌트가 있으면 가드가 마이그레이션을 중단시킨다")
  void opencode_테넌트가_있으면_가드가_중단시킨다() {
    // 설계 전제("opencode 사용자 없음")가 깨지면 필드 없는 빈 Opencode 레코드가 만들어져
    // 그 테넌트의 AI 기능이 통째로 죽는다 — 그래서 가드는 진행 대신 예외로 멈춰야 한다.
    long tenantId = newTenant("aicred-opencode");
    insertLegacyRow(tenantId, "ai.agent_type", "opencode");

    assertThatThrownBy(() -> inTenantFixture(tenantId, () -> dsl.execute(GUARD_SQL)))
        .hasMessageContaining("opencode");
  }

  @Test
  @DisplayName("Ruling #21 — 빈 agent_type 값도 가드가 막는다(fail-closed 우회 방지)")
  void 가드는_빈_agent_type도_막는다() {
    // coalesce(...,'sdk') 는 NULL 만 잡는다. tenant_settings.value 는 NOT NULL 이라 NULL 은
    // 애초에 나오지 않고, 리터럴 빈 문자열 ''은 그대로 agentType:"" 으로 옮겨진다.
    // AiCredentialService.resolve() 의 switch 는 이 값을 모르므로 UnknownAgentTypeException 을
    // 던지고 플랫폼으로도 폴백하지 않는다 — 되돌릴 방법 없이 죽는 테넌트가 생긴다.
    long tenantId = newTenant("aicred-guard-empty");
    insertLegacyRow(tenantId, "ai.agent_type", "");

    assertThatThrownBy(() -> inTenantFixture(tenantId, () -> dsl.execute(GUARD_SQL)))
        .hasMessageContaining("알 수 없는 ai.agent_type");
  }

  @Test
  @DisplayName("Ruling #21 — 언더스코어 오타(cli_api)도 가드가 막는다")
  void 가드는_언더스코어_오타도_막는다() {
    // 'cli_api'(언더스코어)는 알려진 4개 값(sdk/cli/cli-api/opencode) 중 어디에도 없다 —
    // coalesce 는 정확한 문자열 일치만 통과시키므로 이런 오타를 걸러내지 못하고, 사람이 손으로
    // 고친 행이나 예전 버전이 남긴 값이 그대로 옮겨지면 위와 같은 이유로 테넌트가 fail-closed 로
    // 멈춘다.
    long tenantId = newTenant("aicred-guard-typo");
    insertLegacyRow(tenantId, "ai.agent_type", "cli_api");

    assertThatThrownBy(() -> inTenantFixture(tenantId, () -> dsl.execute(GUARD_SQL)))
        .hasMessageContaining("알 수 없는 ai.agent_type");
  }

  @Test
  @DisplayName("플랫폼 평면도 같은 규칙으로 변환된다")
  void 플랫폼_평면도_같은_규칙으로_변환된다() {
    // 플랫폼 평면은 부팅 시 실제로 한 번 변환된다 — V31/V40/V41 이 ai.api_key=''/ai.cli_oauth_token=''
    // /ai.agent_type='sdk' 를 시드하므로 신선한 DB 에서도 항상 대상이 있다(테넌트 평면과 달리
    // "픽스처가 없으면 빈 채로 부팅"이 아니다). 그래서 재생이 아니라 "지우고 다시 재생"으로
    // 검증한다 — 그래야 이 테스트가 지금 디스크에 있는 파일 내용에 실제로 반응한다(안 그러면
    // 예전에 부팅 시 만들어진 행을 영원히 그대로 보게 되어 파일을 고쳐도 테스트가 안 움직인다).
    //
    // **부팅 시드 값 그대로는 검증력이 없다.** V31/V40/V41 의 시드가 정확히 sdk/''/'' 라 —
    // "agentType 을 'sdk' 로 하드코딩한 뮤테이션"도 "apiKey 를 ai.cli_oauth_token 에서 잘못
    // 끌어오는 뮤테이션"도 시드 값 자체가 소스 식과 자기 기본값을 구분 못 하게 만들어 결과가
    // 우연히 똑같이 나온다(리뷰에서 실제로 GREEN 으로 살아남았다). 그래서 재생 전에 3키를
    // **서로 다른, 기본값이 아닌** 값으로 UPDATE 해 두고 결과 문서에 그 값이 그대로 반영됐는지
    // 확인한다 — 끝나면 원래 시드 값으로 복원한다.
    Optional<String> beforeCredential = rawPlatformValue("ai.credential");
    assertThat(beforeCredential).as("부팅 시 플랫폼 ai.credential 이 이미 만들어져 있어야 한다").isPresent();
    String knownGoodCredentialJson = beforeCredential.get();
    String originalAgentType = rawPlatformValue("ai.agent_type").orElseThrow();
    String originalApiKey = rawPlatformValue("ai.api_key").orElseThrow();
    String originalOauthToken = rawPlatformValue("ai.cli_oauth_token").orElseThrow();

    String apiKeyCipher = encryptionService.encrypt("sk-platform-real-key");
    String oauthCipher = encryptionService.encrypt("oauth-platform-token"); // apiKey 와 평문이 달라야 크로스와이어를 잡는다.

    try {
      dsl.execute("update system_settings set value = ? where key = 'ai.agent_type'", "cli-api");
      dsl.execute("update system_settings set value = ? where key = 'ai.api_key'", apiKeyCipher);
      dsl.execute("update system_settings set value = ? where key = 'ai.cli_oauth_token'", oauthCipher);

      dsl.execute("delete from system_settings where key = 'ai.credential'");
      dsl.execute(PLATFORM_INSERT_SQL);

      String json = rawPlatformValue("ai.credential").orElseThrow();
      JsonNode doc = parseJson(json);
      // v/payload — 테넌트 평면 테스트와 같은 이유(원본 JSON 을 직접 봐야 "필드 누락" 뮤테이션을 잡는다).
      assertThat(doc.path("v").asInt()).isEqualTo(1);
      assertThat(doc.has("payload")).isTrue();
      assertThat(doc.path("agentType").asText()).isEqualTo("cli-api");

      AiCredentialDocument parsed = AiCredentialDocument.parse(json);
      // 크로스와이어 검사 — apiKey/oauthToken 이 서로 바뀌어 있었다면 여기서 걸린다.
      assertThat(parsed.secretCipher("apiKey")).isEqualTo(apiKeyCipher);
      assertThat(parsed.secretCipher("oauthToken")).isEqualTo(oauthCipher);

      TenantContext.clear();
      try {
        AiCredentialView view = aiCredentialService.read();
        assertThat(view.agentType()).isEqualTo("cli-api");
        assertThat(view.tenantOwned()).isFalse();
        assertThat(view.secretFieldNames()).containsExactlyInAnyOrder("apiKey", "oauthToken");
      } finally {
        TenantContext.set(DEFAULT_TEST_TENANT_ID);
      }
    } finally {
      // **무조건 복원한다 — "행이 없을 때만"이 아니다.** 뮤테이션이 "없는" 게 아니라 "틀린데
      // 있는" 행을 만들면(예: agentType 하드코딩) 예전 조건(`if (rawPlatformValue(...).isEmpty())`)
      // 은 통과시켜 잘못된 행이 공유 테스트 DB 에 영구히 남는다 — 실제로 이 실수가 한 번
      // 일어나 리뷰어가 손으로 고쳤다. 그래서 결과가 뭐든 일단 지우고, 시작 시점에 읽어 둔
      // 원본 JSON/3키 값을 그대로 다시 넣는다 — 재생 대상 SQL(PLATFORM_INSERT_SQL)에 기대지
      // 않는 독립적인 복원 경로다(그 문 자체가 뮤테이션으로 깨진 상태일 수 있기 때문).
      dsl.execute("delete from system_settings where key = 'ai.credential'");
      dsl.execute(
          "insert into system_settings (key, value, description) values (?, ?, ?)",
          "ai.credential",
          knownGoodCredentialJson,
          "AI 자격증명(유형별 구조)");
      dsl.execute("update system_settings set value = ? where key = 'ai.agent_type'", originalAgentType);
      dsl.execute("update system_settings set value = ? where key = 'ai.api_key'", originalApiKey);
      dsl.execute("update system_settings set value = ? where key = 'ai.cli_oauth_token'", originalOauthToken);
    }
  }

  /**
   * {@code system_settings.ai.agent_type} 만 잠깐 바꿔 GUARD_SQL 을 돌려 본 뒤 원복한다.
   *
   * <p>전체 브랜치 리뷰 C1 이전에는 두 가드 모두 {@code tenant_settings} 만 읽어 플랫폼 행이
   * 완전히 무방비였다 — 배포측 PVC(opencode.jsonc)가 강하게 시사하는 상태(플랫폼 기본값이
   * opencode)에서 V122 를 그대로 배포하면 플랫폼 행이 {@code agentType:"opencode",payload:{}} 로
   * 변환돼 그 값을 상속하는 테넌트 전부가 죽는다. 아래 세 테스트는 그 가드가 실제로 system_settings
   * 를 보는지 GUARD_SQL 을 직접 재생해 확인한다(v122 파일 본문 그대로 — 규칙을 테스트 쪽에 다시
   * 베끼지 않는다는 클래스 상단 원칙과 동일).
   */
  private void withPlatformAgentType(String value, Runnable body) {
    String original = rawPlatformValue("ai.agent_type").orElseThrow();
    try {
      dsl.execute("update system_settings set value = ? where key = 'ai.agent_type'", value);
      body.run();
    } finally {
      dsl.execute("update system_settings set value = ? where key = 'ai.agent_type'", original);
    }
  }

  @Test
  @DisplayName("전체 브랜치 리뷰 C1 — 플랫폼 ai.agent_type 이 opencode 면 가드가 중단시킨다")
  void 가드는_플랫폼_opencode도_막는다() {
    withPlatformAgentType(
        "opencode",
        () ->
            assertThatThrownBy(() -> dsl.execute(GUARD_SQL))
                .hasMessageContaining("opencode")
                .hasMessageContaining("플랫폼"));
  }

  @Test
  @DisplayName("전체 브랜치 리뷰 C1 — 플랫폼의 알 수 없는 agent_type(빈 문자열)도 가드가 막는다")
  void 가드는_플랫폼의_알수없는_agent_type도_막는다() {
    withPlatformAgentType(
        "",
        () ->
            assertThatThrownBy(() -> dsl.execute(GUARD_SQL))
                .hasMessageContaining("알 수 없는 플랫폼"));
  }

  @Test
  @DisplayName("전체 브랜치 리뷰 C1 — 플랫폼 agent_type 이 유효하면 가드를 통과하고 실제로 변환된다(cli, cli-api 외 케이스)")
  void 가드를_통과한_플랫폼_cli는_실제로_변환된다() {
    // 기존 플랫폼 변환 테스트는 cli-api 하나만 exercise 했다(전체 브랜치 리뷰 지적) — cli 는
    // oauthToken 만 쓰고 apiKey 는 쓰지 않아 cli-api 와 조립 규칙이 다르다.
    String originalAgentType = rawPlatformValue("ai.agent_type").orElseThrow();
    String originalOauthToken = rawPlatformValue("ai.cli_oauth_token").orElseThrow();
    String originalCredentialJson = rawPlatformValue("ai.credential").orElseThrow();
    String oauthCipher = encryptionService.encrypt("oauth-platform-cli-token");

    try {
      dsl.execute("update system_settings set value = ? where key = 'ai.agent_type'", "cli");
      dsl.execute("update system_settings set value = ? where key = 'ai.cli_oauth_token'", oauthCipher);

      // 가드는 통과해야 한다(예외 없음).
      dsl.execute(GUARD_SQL);

      dsl.execute("delete from system_settings where key = 'ai.credential'");
      dsl.execute(PLATFORM_INSERT_SQL);

      String json = rawPlatformValue("ai.credential").orElseThrow();
      AiCredentialDocument parsed = AiCredentialDocument.parse(json);
      assertThat(parsed.agentType()).isEqualTo("cli");
      assertThat(parsed.secretCipher("oauthToken")).isEqualTo(oauthCipher);

      TenantContext.clear();
      try {
        AiCredential resolved = aiCredentialService.resolve();
        assertThat(resolved).isEqualTo(new AiCredential.Cli("oauth-platform-cli-token"));
      } finally {
        TenantContext.set(DEFAULT_TEST_TENANT_ID);
      }
    } finally {
      // 무조건 복원한다 — 위 큰 플랫폼 변환 테스트와 같은 이유(원래 있던 값으로 되돌린다,
      // "지우고 없으면 넘어간다"가 아니다).
      dsl.execute("delete from system_settings where key = 'ai.credential'");
      dsl.execute(
          "insert into system_settings (key, value, description) values (?, ?, ?)",
          "ai.credential",
          originalCredentialJson,
          "AI 자격증명(유형별 구조)");
      dsl.execute("update system_settings set value = ? where key = 'ai.agent_type'", originalAgentType);
      dsl.execute(
          "update system_settings set value = ? where key = 'ai.cli_oauth_token'", originalOauthToken);
    }
  }
}
