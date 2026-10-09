package com.smartfirehub.embedding.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import com.smartfirehub.embedding.EmbeddingException;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.embedding.config.dto.EmbeddingConfigRequest;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.ProviderHosting;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.io.IOException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 설정 저장 흐름(형식 검증 → probe 로 차원 측정 → 문서 저장)을 실제 tenant_settings(RLS)로 검증한다.
 *
 * <p>probe 는 대부분 스파이로 차원만 돌려준다(외부 호출 없음). Ollama 한 건은 MockWebServer 를 허용 목록에 넣어 실제 HTTP 까지 통과시킨다 —
 * 허용 목록 주입이 실제로 배선됐는지 보는 유일한 테스트다.
 */
class EmbeddingSettingsServiceTest extends IntegrationTestBase {

  private static final MockWebServer OLLAMA = startServer();

  private static MockWebServer startServer() {
    MockWebServer s = new MockWebServer();
    try {
      s.start();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return s;
  }

  @DynamicPropertySource
  static void allowOllama(DynamicPropertyRegistry r) {
    r.add("app.embedding.ollama-allowed-base-urls", () -> OLLAMA.url("/").toString());
  }

  @AfterAll
  static void stopServer() throws IOException {
    OLLAMA.shutdown();
  }

  @Autowired private EmbeddingSettingsService settingsService;
  @Autowired private EmbeddingConfigService configService;
  @Autowired private DSLContext dsl;
  @MockitoSpyBean private EmbeddingProviderFactory providerFactory;

  private long tenantId;

  @BeforeEach
  void newTenant() {
    tenantId = TenantRlsTestSupport.createActiveTenant(dsl, "emb-cfg");
    TenantContext.set(tenantId);
  }

  @AfterEach
  void cleanup() {
    TenantRlsTestSupport.deleteTenants(dsl, tenantId); // tenant_settings 는 tenant FK CASCADE
  }

  private static EmbeddingConfigRequest ollama(String model) {
    return new EmbeddingConfigRequest("OLLAMA", model, OLLAMA.url("/").toString(), null, null);
  }

  @Test
  void unconfiguredViewIsNotConfigured() {
    assertThat(settingsService.view().configured()).isFalse();
    assertThat(configService.resolve()).isEmpty();
  }

  @Test
  void saveMeasuresDimensionViaRealOllamaCallAndStoresDocument() {
    StringBuilder vec = new StringBuilder("[");
    // 영벡터는 코사인에서 의미가 없으므로 첫 칸만 1 인 벡터를 돌려준다.
    for (int i = 0; i < 1024; i++) vec.append(i == 0 ? "" : ",").append(i == 0 ? "1.0" : "0.0");
    vec.append("]");
    OLLAMA.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("{\"embeddings\":[" + vec + "]}"));

    var view = settingsService.save(ollama("bge-m3"), null);

    assertThat(view.configured()).isTrue();
    assertThat(view.dimension()).isEqualTo(1024);
    assertThat(view.model()).isEqualTo("bge-m3");
    assertThat(configService.resolve())
        .get()
        .extracting(EmbeddingConfig::dimension)
        .isEqualTo(1024);
  }

  @Test
  void unsupportedMeasuredDimensionIsRejectedAndNothingStored() {
    doReturn(768).when(providerFactory).probeDimension(any());
    assertThatThrownBy(() -> settingsService.save(ollama("nomic-embed-text"), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("지원하지 않는 차원 768 (지원: 1024, 1536)");
    assertThat(configService.resolve()).isEmpty();
  }

  @Test
  void providerFailureCauseIsPassedThrough() {
    doThrow(new EmbeddingException("OpenAI 임베딩 호출 실패: 401 Unauthorized"))
        .when(providerFactory)
        .probeDimension(any());
    assertThatThrownBy(() -> settingsService.test(ollama("bge-m3")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("401 Unauthorized");
  }

  @Test
  void blankModelIsRejected() {
    assertThatThrownBy(() -> settingsService.test(ollama("  ")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("임베딩 모델은 비어있을 수 없습니다");
  }

  @Test
  void openAiWithoutAnyKeyIsRejected() {
    // Review Focus: OLLAMA→OPENAI 로 바꾸면서 키를 비우면(저장된 OpenAI 키 없음) 거부한다.
    // settingsService.test(...) 를 쓰면 prepare() 가 키 병합보다 먼저 EmbeddingTargetGuard 를 돌려
    // validateTargetOnly("https://api.openai.com") 가 실제 DNS 해석을 시도한다 — 네트워크/DNS 가 없는
    // CI 에서는 "API 키가 필요합니다" 대신 가드 실패 문구로 어긋난다. 바로 아래 테스트들처럼
    // prepareForTest(가드 제외, 병합 규칙 동일)로 키 병합만 검증한다.
    assertThatThrownBy(
            () ->
                configService.prepareForTest(
                    new EmbeddingConfigRequest(
                        "OPENAI", "text-embedding-3-small", "https://api.openai.com", "", null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("API 키가 필요합니다");
  }

  @Test
  void apiKeyIsEncryptedAtRestAndMaskedInView() {
    configService.store(
        new EmbeddingConfig(
            EmbeddingProviderType.OPENAI,
            "text-embedding-3-small",
            "https://api.openai.com",
            "sk-abcdef1234",
            0),
        com.smartfirehub.embedding.EmbeddingDimension.D1536,
        ProviderHosting.EXTERNAL,
        null);
    String raw =
        inTenantFixture(
            tenantId,
            () ->
                dsl.fetchOne("select value from tenant_settings where key = 'embedding.config'")
                    .get(0, String.class));
    assertThat(raw).doesNotContain("sk-abcdef1234");
    assertThat(settingsService.view().apiKeyMasked()).isEqualTo("****1234");
    assertThat(configService.resolve())
        .get()
        .extracting(EmbeddingConfig::apiKey)
        .isEqualTo("sk-abcdef1234");
  }

  @Test
  void omittedKeyKeepsStoredKeyWhenBaseUrlUnchanged() {
    configService.store(
        new EmbeddingConfig(
            EmbeddingProviderType.OPENAI,
            "text-embedding-3-small",
            "https://api.openai.com",
            "sk-stored",
            0),
        com.smartfirehub.embedding.EmbeddingDimension.D1536,
        ProviderHosting.EXTERNAL,
        null);
    // OpenAI 가드는 DNS 해석을 하므로 prepareForTest(가드 제외, 병합 규칙 동일)로 키 병합만 본다.
    EmbeddingConfig prepared =
        configService.prepareForTest(
            new EmbeddingConfigRequest(
                "OPENAI", "text-embedding-3-large", "https://api.openai.com/", null, null));
    assertThat(prepared.apiKey()).isEqualTo("sk-stored");
  }

  @Test
  void changedBaseUrlWithoutKeyIsRejected() {
    // Review Focus: Base URL 만 바꾸고 키를 비우면 저장된 키가 새 호스트로 실려 나가면 안 된다.
    configService.store(
        new EmbeddingConfig(
            EmbeddingProviderType.OPENAI,
            "text-embedding-3-small",
            "https://api.openai.com",
            "sk-stored",
            0),
        com.smartfirehub.embedding.EmbeddingDimension.D1536,
        ProviderHosting.EXTERNAL,
        null);
    assertThatThrownBy(
            () ->
                configService.prepareForTest(
                    new EmbeddingConfigRequest(
                        "OPENAI",
                        "text-embedding-3-small",
                        "https://evil.example.com",
                        null,
                        null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Base URL 을 바꾸면 API 키를 다시 입력해야 합니다");
  }
}
