package com.smartfirehub.tenant;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V90 document 도메인 RLS 격리를 양방향으로 검증한다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 붙이지 않는다 — 테넌트를 바꿔 가며 여러 트랜잭션을
 * 열어야 하고, 하나의 테스트 트랜잭션에 묶이면 GUC 가 처음 값으로 고정된다.
 */
class DocumentDomainRlsTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;

  private TransactionTemplate tx;
  private long tenantA;
  private long tenantB;
  private Long createdUserId;

  @BeforeEach
  void createTenants() {
    tx = new TransactionTemplate(transactionManager);
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "rls-doc-a");
    tenantB = TenantRlsTestSupport.createActiveTenant(dsl, "rls-doc-b");
    createdUserId = TenantRlsTestSupport.insertUser(dsl, "docuser");
  }

  @AfterEach
  void cleanup() {
    deleteOwnRows(tenantA);
    deleteOwnRows(tenantB);
    TenantRlsTestSupport.deleteUser(dsl, createdUserId);
    TenantRlsTestSupport.deleteTenants(dsl, tenantA, tenantB);
    TenantContext.clear();
  }

  @Test
  void documentFileIsIsolated() {
    TenantRlsTestSupport.assertTwoSidedIsolation(
        tx,
        dsl,
        tenantA,
        tenantB,
        "document_file",
        () -> insertDocumentFile(insertDataset("문서격리")));
  }

  @Test
  void documentChunkIsIsolated() {
    TenantRlsTestSupport.assertTwoSidedIsolation(
        tx,
        dsl,
        tenantA,
        tenantB,
        "document_chunk",
        () -> {
          Long datasetId = insertDataset("청크격리");
          return insertDocumentChunk(datasetId, insertDocumentFile(datasetId));
        });
  }

  // ── 픽스처 ────────────────────────────────────────────────────────────


  private Long insertDataset(String namePrefix) {
    long suffix = TenantRlsTestSupport.nextTenantId();
    return dsl.insertInto(table(name("dataset")))
        .set(field(name("name"), String.class), namePrefix + "-" + suffix)
        .set(field(name("table_name"), String.class), "tbl_doc_" + suffix)
        .set(field(name("storage_type"), String.class), "DOCUMENT")
        .set(field(name("origin_type"), String.class), "SOURCE")
        .set(field(name("created_by"), Long.class), createdUserId)
        .returning(field(name("id"), Long.class))
        .fetchOne()
        .get(field(name("id"), Long.class));
  }

  /** tenant_id 는 명시하지 않는다 — DEFAULT 가 GUC 에서 채우는 것을 함께 검증한다. */
  private Long insertDocumentFile(Long datasetId) {
    long suffix = TenantRlsTestSupport.nextTenantId();
    return dsl.insertInto(table(name("document_file")))
        .set(field(name("dataset_id"), Long.class), datasetId)
        .set(field(name("original_name"), String.class), "doc-" + suffix + ".pdf")
        .set(field(name("mime_type"), String.class), "application/pdf")
        .set(field(name("file_size"), Long.class), 1024L)
        .set(field(name("storage_path"), String.class), "/tmp/doc-" + suffix)
        .set(field(name("status"), String.class), "PENDING")
        .set(field(name("uploaded_by"), Long.class), createdUserId)
        .returning(field(name("id"), Long.class))
        .fetchOne()
        .get(field(name("id"), Long.class));
  }

  private Long insertDocumentChunk(Long datasetId, Long documentFileId) {
    return dsl.insertInto(table(name("document_chunk")))
        .set(field(name("document_file_id"), Long.class), documentFileId)
        .set(field(name("dataset_id"), Long.class), datasetId)
        .set(field(name("chunk_index"), Integer.class), 0)
        .set(field(name("content"), String.class), "본문 청크")
        .returning(field(name("id"), Long.class))
        .fetchOne()
        .get(field(name("id"), Long.class));
  }

  /** 자기 테넌트 행만 지운다(RLS 스코프). 자식 → 부모 순서. */
  private void deleteOwnRows(long tenantId) {
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        tenantId,
        () -> {
          dsl.deleteFrom(table(name("document_chunk"))).execute();
          dsl.deleteFrom(table(name("document_file"))).execute();
          dsl.deleteFrom(table(name("dataset"))).execute();
        });
  }
}
