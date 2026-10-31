package com.smartfirehub.embedding.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.embedding.EmbeddingException;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.embedding.config.dto.EmbeddingConfigRequest;
import com.smartfirehub.embedding.reembed.TenantReembedJob;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.settings.repository.TenantSettingsRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 임베딩 설정 변경 감사(WD-469) 통합 테스트.
 *
 * <p>검증: 임베딩 설정 저장이 audit_log 에 정확히 한 행(resource=embedding_settings, 처음은 CREATE·이후 UPDATE, 바뀐 항목 이름
 * changedFields)을 남기고, 같은 값 재저장과 거부된 요청(검증 실패·연결 테스트 실패·미지원 차원·권한 없는 자체 호스팅 선언)은 아무것도 남기지 않으며, API
 * 키 값은 설명·metadata 어디에도 새지 않는다(WD-465 SettingsAuditTest 와 같은 기준).
 *
 * <p>외부 호출은 막는다 — probe 는 스파이로 차원만 돌려주고, OpenAI Base URL 의 SSRF 가드(DNS 해석)는 스파이로 건너뛴다(가드 자체는
 * EmbeddingTargetGuardTest 가 본다). 기본 테넌트(1번)의 임베딩 설정 행은 다른 테스트 클래스와 공유되므로 시작·끝에 지운다.
 */
class EmbeddingSettingsAuditTest extends IntegrationTestBase {

  /** 값 누출 검사용 표식 — API 키에 넣고 감사 설명·metadata 에 없어야 한다. */
  private static final String MARKER = "MARKER_WD469_SECRET";

  private static final String OPENAI = "https://api.openai.com";

  /** Ollama 주소는 허용 목록에 넣어 가드를 정상 경로로 통과시킨다. */
  private static final String OLLAMA = "http://ollama-wd469.internal:11434";

  @DynamicPropertySource
  static void allowOllama(DynamicPropertyRegistry r) {
    r.add("app.embedding.ollama-allowed-base-urls", () -> OLLAMA);
  }

  @Autowired private EmbeddingSettingsService settingsService;
  @Autowired private TenantSettingsRepository tenantSettingsRepository;
  @Autowired private DSLContext dsl;
  @Autowired private ObjectMapper objectMapper;

  /** probe(외부 호출)를 차원만 돌려주게 바꾼다. */
  @MockitoSpyBean private EmbeddingProviderFactory providerFactory;

  /** OpenAI Base URL 의 DNS 해석 가드를 건너뛴다 — 네트워크 없는 CI 에서도 저장 흐름을 끝까지 탄다. */
  @MockitoSpyBean private EmbeddingTargetGuard targetGuard;

  /** 저장 후 재임베딩 잡 투입이 실제 배경 작업을 띄우지 않게 막는다. */
  @MockitoBean private TenantReembedJob reembedJob;

  private Long actor;

  /** 감사 행 한 줄(검증용 투영). */
  private record Audit(
      String action, String resourceId, String username, String description, JsonNode metadata) {}

  @BeforeEach
  void setUp() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    actor = TenantRlsTestSupport.insertUser(dsl, "wd469_actor");
    tenantSettingsRepository.delete(EmbeddingConfigService.KEY);
    doNothing().when(targetGuard).check(any(), any());
    doReturn(1536).when(providerFactory).probeDimension(any());
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    tenantSettingsRepository.delete(EmbeddingConfigService.KEY);
    // 감사 행은 user_id FK 라 사용자보다 먼저 지운다.
    inTenantFixture(() -> dsl.execute("DELETE FROM audit_log WHERE user_id = ?", actor));
    TenantRlsTestSupport.deleteUser(dsl, actor);
  }

  // ── 도우미 ────────────────────────────────────────────────────────────────

  private static EmbeddingConfigRequest openAi(String model, String apiKey) {
    return new EmbeddingConfigRequest("OPENAI", model, OPENAI, apiKey, null);
  }

  private List<Audit> audits() {
    return inTenantFixture(
        () ->
            dsl.fetch(
                    "SELECT action_type, resource_id, username, description, metadata::text AS m"
                        + " FROM audit_log WHERE user_id = ? AND resource = 'embedding_settings'"
                        + " ORDER BY id",
                    actor)
                .map(
                    r ->
                        new Audit(
                            r.get(0, String.class),
                            r.get(1, String.class),
                            r.get(2, String.class),
                            r.get(3, String.class),
                            readJson(r.get(4, String.class)))));
  }

  /** 이 사용자의 감사 행 전체 수 — 거부된 요청이 어느 resource 로도 남지 않았는지 본다. */
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

  private static List<String> changedFields(Audit a) {
    List<String> out = new ArrayList<>();
    a.metadata().get("changedFields").forEach(n -> out.add(n.asText()));
    return out;
  }

  private static void assertNoLeak(List<Audit> rows) {
    for (Audit a : rows) {
      assertThat(a.description()).doesNotContain(MARKER);
      assertThat(String.valueOf(a.metadata())).doesNotContain(MARKER);
    }
  }

  // ── 기록되는 변경 ──────────────────────────────────────────────────────────

  @Test
  void 첫_저장은_등록_한_행이고_키_값은_남지_않는다() {
    settingsService.save(openAi("text-embedding-3-small", MARKER + "-key-1"), actor);

    List<Audit> rows = audits();
    assertThat(rows).hasSize(1);
    Audit a = rows.get(0);
    assertThat(a.action()).isEqualTo("CREATE");
    assertThat(a.resourceId()).isEqualTo("embedding");
    assertThat(a.username()).startsWith("wd469_actor");
    assertThat(a.description()).isEqualTo("임베딩 설정 등록 (항목: 공급자·모델·기본 URL·API 키)");
    assertThat(a.metadata().get("name").asText()).isEqualTo("임베딩 설정");
    assertThat(changedFields(a)).containsExactly("provider", "model", "baseUrl", "apiKey");
    assertNoLeak(rows);
  }

  @Test
  void 같은_값을_다시_저장하면_남지_않고_키나_모델을_바꾸면_수정_한_행에_항목_이름만_남는다() {
    settingsService.save(openAi("text-embedding-3-small", MARKER + "-key-1"), actor);
    // 같은 키 다시 입력·키 생략(저장된 키 유지)·끝 슬래시만 다른 Base URL — 실효 값이 같으므로 기록하지 않는다.
    settingsService.save(openAi("text-embedding-3-small", MARKER + "-key-1"), actor);
    settingsService.save(openAi("text-embedding-3-small", null), actor);
    settingsService.save(
        new EmbeddingConfigRequest("OPENAI", "text-embedding-3-small", OPENAI + "/", null, null),
        actor);
    assertThat(audits()).hasSize(1);

    // 키만 바꾼다 → apiKey 하나.
    settingsService.save(openAi("text-embedding-3-small", MARKER + "-key-2"), actor);
    // 모델만 바꾼다(키 생략) → model 하나.
    settingsService.save(openAi("text-embedding-3-large", null), actor);

    List<Audit> rows = audits();
    assertThat(rows).hasSize(3);
    assertThat(rows.get(1).action()).isEqualTo("UPDATE");
    assertThat(rows.get(1).description()).isEqualTo("임베딩 설정 수정 (바뀐 항목: API 키)");
    assertThat(changedFields(rows.get(1))).containsExactly("apiKey");
    assertThat(rows.get(2).action()).isEqualTo("UPDATE");
    assertThat(rows.get(2).description()).isEqualTo("임베딩 설정 수정 (바뀐 항목: 모델)");
    assertThat(changedFields(rows.get(2))).containsExactly("model");
    assertNoLeak(rows);
  }

  @Test
  void 공급자를_바꾸면_공급자_기본_URL_API_키가_바뀐_항목으로_남는다() {
    settingsService.save(openAi("text-embedding-3-small", MARKER + "-key-1"), actor);
    doReturn(1024).when(providerFactory).probeDimension(any());

    // OpenAI → Ollama: 키는 쓰지 않으므로 비워진다(저장된 키 제거도 변경이다). 모델 이름도 바뀐다.
    settingsService.save(new EmbeddingConfigRequest("OLLAMA", "bge-m3", OLLAMA, null, null), actor);

    List<Audit> rows = audits();
    assertThat(rows).hasSize(2);
    assertThat(rows.get(1).action()).isEqualTo("UPDATE");
    assertThat(changedFields(rows.get(1)))
        .containsExactly("provider", "model", "baseUrl", "apiKey");
    assertNoLeak(rows);
  }

  // ── 거부된 요청 ────────────────────────────────────────────────────────────

  @Test
  void 검증_실패와_연결_테스트_실패와_미지원_차원은_감사를_남기지_않는다() {
    // 모델 비움(400)
    assertThatThrownBy(() -> settingsService.save(openAi("  ", MARKER + "-key-1"), actor))
        .isInstanceOf(IllegalArgumentException.class);
    // 처음 저장인데 OpenAI 키 없음(400)
    assertThatThrownBy(() -> settingsService.save(openAi("text-embedding-3-small", null), actor))
        .isInstanceOf(IllegalArgumentException.class);
    // 공급자 연결 실패(400)
    doThrow(new EmbeddingException("OpenAI 임베딩 호출 실패: 401 Unauthorized"))
        .when(providerFactory)
        .probeDimension(any());
    assertThatThrownBy(
            () -> settingsService.save(openAi("text-embedding-3-small", MARKER + "-k"), actor))
        .isInstanceOf(IllegalArgumentException.class);
    // 미지원 차원(400)
    doReturn(768).when(providerFactory).probeDimension(any());
    assertThatThrownBy(
            () -> settingsService.save(openAi("text-embedding-3-small", MARKER + "-k"), actor))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(allAuditCount()).isZero();
  }

  @Test
  void 권한_없는_자체_호스팅_선언은_감사를_남기지_않는다() {
    doReturn(1024).when(providerFactory).probeDimension(any());
    // 역할 없는 사용자 — security:settings 가 없어 자체 호스팅으로 올리는 선언은 403.
    assertThatThrownBy(
            () ->
                settingsService.save(
                    new EmbeddingConfigRequest("OLLAMA", "bge-m3", OLLAMA, null, "SELF_HOSTED"),
                    actor))
        .isInstanceOf(CodedApiException.class);

    assertThat(allAuditCount()).isZero();
  }
}
