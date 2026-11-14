package com.smartfirehub.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.smartfirehub.document.repository.DocumentFileRepository;
import com.smartfirehub.embedding.EmbeddingProvider;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.nio.file.Path;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 적재 순서(ADR 0002 결정 6, WD-720): 청크를 먼저 커밋하고 벡터를 나중에 쓴다. 벡터 쓰기가 실패해도 문서는 완료로 남고, 청크는 표시 열이 빈 채로 남아
 * 재임베딩 백로그가 채운다.
 *
 * <p>클래스에 {@code @Transactional} 을 붙이지 않는다 — 실제 잡처럼 단계마다 트랜잭션이 따로 커밋돼야 "청크 커밋 뒤 벡터 실패" 를 볼 수 있다.
 */
class DocumentIngestionVectorWriteFailureTest extends IntegrationTestBase {

  @Autowired private DocumentIngestionService ingestionService;
  @Autowired private DocumentFileRepository fileRepository;
  @Autowired private DSLContext dsl;
  @MockitoBean private EmbeddingProviderFactory embeddingProviderFactory;

  // 업로드 디렉터리를 임시 경로로 돌려 테스트가 ./uploads 에 파일을 남기지 않도록 한다.
  @TempDir static Path tempDir;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("firehub.file.upload-dir", () -> tempDir.toString());
  }

  private long tenant;
  private Long userId;

  @BeforeEach
  void seed() {
    tenant = TenantRlsTestSupport.createActiveTenant(dsl, "ingfail");
    userId = TenantRlsTestSupport.insertUser(dsl, "ingfail");
  }

  @AfterEach
  void cleanup() {
    TenantContext.clear();
    TenantRlsTestSupport.deleteOwnDatasetRows(dsl, fixtureTransactionTemplate, tenant);
    TenantRlsTestSupport.deleteTenants(dsl, tenant);
    TenantRlsTestSupport.deleteUser(dsl, userId);
  }

  @Test
  void vectorWriteFailure_keepsDocumentCompleted_andLeavesChunksInBacklog() {
    // 공급자는 1024 차원이라고 말하지만 7 차원 벡터를 준다 — 벡터 쓰기(pgvector 차원 검사)에서만 실패한다.
    EmbeddingProvider broken =
        new EmbeddingProvider() {
          public List<float[]> embed(List<String> texts) {
            return texts.stream().map(t -> new float[7]).toList();
          }

          public String modelId() {
            return "broken";
          }

          public int dimension() {
            return 1024;
          }
        };
    when(embeddingProviderFactory.current()).thenReturn(broken);
    Long datasetId =
        inTenantFixture(
            tenant,
            () ->
                dsl.fetchOne(
                        "INSERT INTO dataset(name, table_name, storage_type, origin_type, created_by)"
                            + " VALUES ('ingfail-set', ?, 'DOCUMENT', 'SOURCE', ?) RETURNING id",
                        "data.ingfail_" + tenant,
                        userId)
                    .get(0, Long.class));
    TenantContext.set(tenant);
    byte[] data = "소방 점검 보고서. 화재 예방 점검 결과.".repeat(20).getBytes();
    Long fileId = ingestionService.upload(datasetId, data, "r.txt", "text/plain", userId).id();

    ingestionService.processIngestion(fileId, tenant);

    var file =
        TenantContext.runScopedGet(tenant, () -> fileRepository.findById(fileId).orElseThrow());
    assertThat(file.status()).isEqualTo("COMPLETED");
    assertThat(file.chunkCount()).isPositive();
    // 청크는 커밋됐고 표시 열은 비어 있다 — 재임베딩 판정식이 집는다. 본문 해시는 채워져 있다(키워드 표시 판정용).
    var rows =
        inTenantFixture(
            tenant,
            () ->
                dsl.fetch(
                    "SELECT embedded_dim, content_hash FROM document_chunk WHERE document_file_id = ?",
                    fileId));
    assertThat(rows).hasSize(file.chunkCount());
    assertThat(rows).allSatisfy(r -> assertThat(r.get("embedded_dim")).isNull());
    assertThat(rows).allSatisfy(r -> assertThat(r.get("content_hash", String.class)).hasSize(64));
  }
}
