package com.smartfirehub.vectorstore;

import static org.mockito.Mockito.verify;

import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.document.service.DocumentIngestionService;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 데이터셋·문서를 지우는 경로가 벡터 저장소 포트의 「전부 지우기」를 부르는지 확인한다(ADR 0002 결정 6 — 명시 삭제).
 *
 * <p>pgvector 구현에서는 FK CASCADE 가 이미 지우므로 이 호출은 아무것도 하지 않는다. 별도 벡터 저장소(Qdrant)는 CASCADE 가 없어 이 호출이
 * 유일한 정리 경로라, 호출 자체를 고정해 둔다. 실제 pgvector 행이 함께 사라지는지는 기존 CASCADE 동작이라 여기서 다시 보지 않는다.
 */
class VectorIndexExplicitDeleteTest extends IntegrationTestBase {

  @MockitoSpyBean private DatasetVectorIndex datasetVectorIndex;
  @MockitoSpyBean private ChunkVectorIndex chunkVectorIndex;

  @Autowired private DSLContext dsl;
  @Autowired private DatasetService datasetService;
  @Autowired private DocumentIngestionService ingestionService;

  private long tenant;
  private DocFixture doc;

  @BeforeEach
  void seed() {
    tenant = TenantRlsTestSupport.createActiveTenant(dsl, "vxdel");
    doc = inTenantFixture(tenant, () -> EmbeddingTestFixtures.createDocumentDataset(dsl, "vxdel"));
  }

  @AfterEach
  void cleanup() {
    TenantRlsTestSupport.deleteOwnDatasetRows(dsl, fixtureTransactionTemplate, tenant);
    TenantRlsTestSupport.deleteTenants(dsl, tenant);
    TenantRlsTestSupport.deleteUser(dsl, doc.userId());
  }

  @Test
  void 데이터셋을_지우면_카탈로그와_청크_색인을_전부_지운다() {
    TenantContext.runScoped(tenant, () -> datasetService.deleteDataset(doc.datasetId()));

    verify(datasetVectorIndex).deleteAll(tenant, List.of(doc.datasetId()));
    verify(chunkVectorIndex).deleteAllOfDatasets(tenant, List.of(doc.datasetId()));
  }

  @Test
  void 문서를_지우면_그_문서의_청크_색인을_전부_지운다() {
    TenantContext.runScoped(tenant, () -> ingestionService.deleteDocument(doc.fileId()));

    verify(chunkVectorIndex).deleteAllOfDocument(tenant, doc.fileId());
  }
}
