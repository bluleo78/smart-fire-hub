package com.smartfirehub.embedding;

import static com.smartfirehub.support.EmbeddingTestFixtures.axis;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.document.dto.Chunk;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.embedding.config.EmbeddingConfig;
import com.smartfirehub.embedding.config.EmbeddingConfigService;
import com.smartfirehub.embedding.config.EmbeddingProviderType;
import com.smartfirehub.embedding.reembed.TenantReembedJob;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.support.EmbeddingTestFixtures;
import com.smartfirehub.support.EmbeddingTestFixtures.DocFixture;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 배경 경로(행 검색 스윕이 쓰는 TenantScopedRunner, JobRunr 잡)가 요청 컨텍스트 없이도 <b>그 테넌트의</b> 설정으로
 * provider 를 만든다. 실제 팩토리 + 테넌트별 MockWebServer 두 대로, 요청이 어느 서버로 어떤 모델명으로 갔는지 본다.
 */
class EmbeddingTenantContextTest extends IntegrationTestBase {

  private static final MockWebServer SERVER_A = start();
  private static final MockWebServer SERVER_B = start();

  private static MockWebServer start() {
    MockWebServer s = new MockWebServer();
    try {
      s.start();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return s;
  }

  @DynamicPropertySource
  static void allow(DynamicPropertyRegistry r) {
    r.add(
        "app.embedding.ollama-allowed-base-urls",
        () -> SERVER_A.url("/").toString() + "," + SERVER_B.url("/").toString());
  }

  @AfterAll
  static void stop() throws IOException {
    SERVER_A.shutdown();
    SERVER_B.shutdown();
  }

  @Autowired private EmbeddingProviderFactory providerFactory;
  @Autowired private EmbeddingConfigService configService;
  @Autowired private TenantScopedRunner tenantScopedRunner;
  @Autowired private TenantReembedJob reembedJob;
  @Autowired private DocumentChunkRepository chunks;
  @Autowired private DSLContext dsl;

  private long tenantA;
  private long tenantB;
  private DocFixture docB;

  @BeforeEach
  void seed() {
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "ctx-a");
    tenantB = TenantRlsTestSupport.createActiveTenant(dsl, "ctx-b");
    store(tenantA, "model-a", SERVER_A);
    store(tenantB, "model-b", SERVER_B);
    docB = inTenantFixture(tenantB, () -> EmbeddingTestFixtures.createDocumentDataset(dsl, "ctxb"));
    // B 에 옛 모델 벡터 1건 — 잡이 model-b 로 다시 임베딩해야 한다.
    TenantContext.runScoped(
        tenantB,
        () ->
            chunks.insertBatch(
                docB.fileId(), docB.datasetId(), List.of(new Chunk(0, "b", 1)), List.of(axis(1024, 0)),
                new EmbeddingSpace(EmbeddingDimension.D1024, "old")));
  }

  @AfterEach
  void cleanup() {
    TenantRlsTestSupport.deleteOwnDatasetRows(dsl, fixtureTransactionTemplate, tenantB);
    TenantRlsTestSupport.deleteTenants(dsl, tenantA, tenantB);
    TenantRlsTestSupport.deleteUser(dsl, docB.userId());
  }

  private void store(long tenant, String model, MockWebServer server) {
    TenantContext.runScoped(
        tenant,
        () ->
            configService.store(
                new EmbeddingConfig(EmbeddingProviderType.OLLAMA, model, server.url("/").toString(), "", 0),
                EmbeddingDimension.D1024,
                null));
  }

  @Test
  void tenantScopedRunnerResolvesEachTenantsOwnConfig() {
    TenantContext.clear(); // 스윕 스레드처럼 요청 컨텍스트가 없다
    Map<Long, String> seen = new ConcurrentHashMap<>();
    tenantScopedRunner.forEachTenant(
        List.of(tenantA, tenantB), id -> seen.put(id, providerFactory.current().modelId()));
    assertThat(seen).containsEntry(tenantA, "model-a").containsEntry(tenantB, "model-b");
  }

  @Test
  void jobRunsWithPayloadTenantsConfig() throws Exception {
    StringBuilder vec = new StringBuilder("[");
    // 영벡터는 코사인 HNSW 에서 의미가 없으므로 첫 칸만 1 인 벡터를 돌려준다.
    for (int i = 0; i < 1024; i++) vec.append(i == 0 ? "" : ",").append(i == 0 ? "1.0" : "0.0");
    SERVER_B.enqueue(
        new MockResponse().setHeader("Content-Type", "application/json").setBody("{\"embeddings\":[" + vec + "]]}"));
    int aBefore = SERVER_A.getRequestCount();

    TenantContext.clear(); // JobRunr 워커처럼 컨텍스트 없이 호출
    reembedJob.run(tenantB);

    RecordedRequest req = SERVER_B.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(req).isNotNull();
    assertThat(req.getBody().readUtf8()).contains("\"model-b\"");
    assertThat(SERVER_A.getRequestCount()).isEqualTo(aBefore);
  }

  @Test
  void currentRejectsStoredBaseUrlThatNoLongerPassesGuard() {
    // 저장 시점 가드만으로는 부족하다(A2): 문서가 가드를 거치지 않고 바뀌었거나(직접 조작·허용 목록 축소) DNS 가
    // 바뀐 경우에도 실제 호출 직전에 다시 막아야 한다. 허용 목록 밖 사설 http 주소를 문서에 직접 써 넣는다 —
    // current() 는 네트워크 호출을 하지 않으므로 여기서 나는 예외는 가드에서만 나올 수 있다.
    TenantContext.runScoped(
        tenantA,
        () ->
            configService.store(
                new EmbeddingConfig(EmbeddingProviderType.OLLAMA, "model-a", "http://127.0.0.1:9", "", 0),
                EmbeddingDimension.D1024,
                null));

    assertThatThrownBy(() -> TenantContext.runScoped(tenantA, () -> providerFactory.current()))
        .isInstanceOf(EmbeddingException.class)
        .hasMessageContaining("운영자가 허용한 주소");
  }
}
