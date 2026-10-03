package com.smartfirehub.embedding.config;

import static com.smartfirehub.support.EmbeddingTestFixtures.axis;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.smartfirehub.dataset.rowsearch.SearchIndexStateRepository;
import com.smartfirehub.dataset.search.DatasetEmbeddingRepository;
import com.smartfirehub.document.dto.Chunk;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.embedding.config.dto.EmbeddingConfigRequest;
import com.smartfirehub.embedding.reembed.TenantReembedJob;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.EmbeddingTestFixtures;
import com.smartfirehub.support.EmbeddingTestFixtures.DocFixture;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
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
 * 저장 시 판정식으로만 잡을 투입한다(이전 문서와 비교하지 않는다). 청크 2건이 1024/bge-m3 로 임베딩된 테넌트에서 여러 저장을 해 본다. probe 는 스파이로
 * 차원만 돌려준다.
 */
class EmbeddingSaveReembedTriggerTest extends IntegrationTestBase {

  private static final String OLLAMA_A = "http://ollama-a.internal:11434";
  private static final String OLLAMA_B = "http://ollama-b.internal:11434";

  @DynamicPropertySource
  static void allow(DynamicPropertyRegistry r) {
    r.add("app.embedding.ollama-allowed-base-urls", () -> OLLAMA_A + "," + OLLAMA_B);
  }

  @Autowired private EmbeddingSettingsService settingsService;
  @Autowired private DocumentChunkRepository chunks;
  @Autowired private DatasetEmbeddingRepository datasets;
  @Autowired private SearchIndexStateRepository searchIndexStates;
  @Autowired private DSLContext dsl;
  @MockitoSpyBean private EmbeddingProviderFactory providerFactory;
  @MockitoBean private TenantReembedJob reembedJob;

  private long tenant;
  private DocFixture doc;

  @BeforeEach
  void seed() {
    tenant = TenantRlsTestSupport.createActiveTenant(dsl, "emb-trig");
    doc =
        inTenantFixture(tenant, () -> EmbeddingTestFixtures.createDocumentDataset(dsl, "embtrig"));
    TenantContext.runScoped(
        tenant,
        () ->
            chunks.insertBatch(
                doc.fileId(),
                doc.datasetId(),
                List.of(new Chunk(0, "a", 1), new Chunk(1, "b", 1)),
                List.of(axis(1024, 0), axis(1024, 1)),
                new EmbeddingSpace(EmbeddingDimension.D1024, "bge-m3")));
    TenantContext.set(tenant);
  }

  @AfterEach
  void cleanup() {
    TenantRlsTestSupport.deleteOwnDatasetRows(dsl, fixtureTransactionTemplate, tenant);
    TenantRlsTestSupport.deleteTenants(dsl, tenant);
    TenantRlsTestSupport.deleteUser(dsl, doc.userId());
  }

  private void save(String baseUrl, String model, int measured) {
    doReturn(measured).when(providerFactory).probeDimension(any());
    settingsService.save(new EmbeddingConfigRequest("OLLAMA", model, baseUrl, null), null);
  }

  @Test
  void sameModelAllEmbeddedDoesNotEnqueue() {
    save(OLLAMA_A, "bge-m3", 1024);
    verify(reembedJob, never()).enqueue(anyLong());
  }

  @Test
  void modelChangeEnqueues() {
    save(OLLAMA_A, "bge-m3-v2", 1024);
    verify(reembedJob).enqueue(tenant);
  }

  @Test
  void baseUrlOnlyChangeDoesNotEnqueue() {
    save(OLLAMA_A, "bge-m3", 1024);
    save(OLLAMA_B, "bge-m3", 1024);
    verify(reembedJob, never()).enqueue(anyLong());
  }

  @Test
  void sameModelNameButMeasuredDimensionDiffersEnqueues() {
    // 1024 벡터만 있는데 같은 모델명이 1536 으로 측정됐다(예: OpenAI 3-small 축소 → native) — _1536 에 없으므로 투입.
    save(OLLAMA_A, "bge-m3", 1536);
    verify(reembedJob).enqueue(tenant);
  }

  @Test
  void unknownModelVectorsEnqueue() {
    // V131 이 모델 NULL 벡터를 '<unknown>' 으로 옮긴 상태를 재현한다.
    List<Long> ids =
        inTenantFixture(
            tenant,
            () -> dsl.fetch("SELECT id FROM document_chunk ORDER BY id").getValues(0, Long.class));
    TenantContext.runScoped(
        tenant,
        () ->
            chunks.upsertEmbeddings(
                new EmbeddingSpace(EmbeddingDimension.D1024, "<unknown>"),
                List.of(ids.get(0)),
                List.of(axis(1024, 0))));
    save(OLLAMA_A, "bge-m3", 1024);
    verify(reembedJob).enqueue(tenant);
  }

  @Test
  void datasetOnlyBacklogEnqueues() {
    // 판정식의 데이터셋 절반: 청크는 전부 현재 공간인데 카탈로그 행만 벡터가 없다 — 그래도 투입해야 한다.
    datasets.upsertSourceText(doc.datasetId(), "화재 카탈로그");
    save(OLLAMA_A, "bge-m3", 1024);
    verify(reembedJob).enqueue(tenant);
  }

  @Test
  void impactCountsWhatTheJudgementCounts() {
    // 세 항목을 모두 채운다: 청크 2(시드), 카탈로그 1(bge-m3/1024), 행 검색 색인 1(bge-m3/1024 로 색인됨).
    datasets.upsertSourceText(doc.datasetId(), "화재 카탈로그");
    datasets.upsertEmbedding(
        new EmbeddingSpace(EmbeddingDimension.D1024, "bge-m3"), doc.datasetId(), axis(1024, 0));
    searchIndexStates.createIfAbsent(doc.datasetId());
    searchIndexStates.resetForFullPass(doc.datasetId(), "h", "bge-m3", 1024, 0L);

    var sameSpace = settingsService.impact("bge-m3", 1024);
    assertThat(sameSpace.chunks()).isZero();
    assertThat(sameSpace.datasets()).isZero();
    assertThat(sameSpace.rowSearchIndexes()).isZero();
    var otherSpace = settingsService.impact("text-embedding-3-small", 1536);
    assertThat(otherSpace.chunks()).isEqualTo(2);
    assertThat(otherSpace.datasets()).isEqualTo(1);
    assertThat(otherSpace.rowSearchIndexes()).isEqualTo(1);
  }
}
