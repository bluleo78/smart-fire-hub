package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.document.repository.DocumentFileRepository;
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
 * 배경 잡 스레드의 형태를 그대로 재현해, 리포지토리 접근이 실제로 데이터를 본다는 것을 단언한다.
 *
 * <p>왜 이 테스트가 필요한가: RLS 격리 값은 트랜잭션-로컬 GUC 이고, 그 GUC 는 {@code
 * TenantAwareTransactionManager.doBegin} 에서만 주입된다. {@code TenantContext.set()} 은 ThreadLocal 만
 * 바꾼다 — <b>그 뒤에 트랜잭션이 열리지 않으면 GUC 는 영원히 비어 있고 RLS 가 전 행을 차단한다.</b>
 * JobRunr 잡은 요청 컨텍스트도 앰비언트 트랜잭션도 없이 실행되므로 정확히 이 상황에 놓인다.
 *
 * <p><b>이 클래스에는 클래스 레벨 {@code @Transactional} 이 없다.</b> 그것이 이 테스트의 전부다 —
 * 붙이는 순간 테스트 트랜잭션이 GUC 를 공급해서 운영에는 없는 조건을 만들고, 결함이 보이지 않게 된다.
 * 기존 {@code BackgroundTenantPropagationTest} 는 시그니처만 리플렉션으로 확인하므로 이 결함을
 * 구조적으로 잡지 못한다.
 */
class BackgroundPathTransactionTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private DatasetRepository datasetRepository;
  @Autowired private DocumentFileRepository documentFileRepository;

  private TransactionTemplate tx;
  private long tenantId;
  private Long userId;
  private Long datasetId;
  private Long documentFileId;

  @BeforeEach
  void seed() {
    tx = new TransactionTemplate(transactionManager);
    tenantId = TenantRlsTestSupport.createActiveTenant(dsl, "bg-path");
    userId = TenantRlsTestSupport.insertUser(dsl, "bguser");
    // 픽스처는 트랜잭션 안에서 만든다(그래야 tenant_id DEFAULT 가 채워진다).
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        tenantId,
        () -> {
          datasetId = insertDataset();
          documentFileId = insertDocumentFile(datasetId);
        });
  }

  @AfterEach
  void cleanup() {
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        tenantId,
        () -> {
          dsl.deleteFrom(table(name("document_file"))).execute();
          dsl.deleteFrom(table(name("dataset"))).execute();
        });
    TenantRlsTestSupport.deleteUser(dsl, userId);
    TenantRlsTestSupport.deleteTenants(dsl, tenantId);
    TenantContext.clear();
  }

  @Test
  void datasetIsReadableFromBackgroundThreadShape() {
    // 잡 스레드 재현: 컨텍스트만 세우고, 앰비언트 트랜잭션 없이 리포지토리를 호출한다.
    TenantContext.set(tenantId);
    try {
      assertThat(datasetRepository.findById(datasetId))
          .as(
              "배경 잡 형태(트랜잭션 없음)에서 데이터셋이 안 보이면, 임포트 잡이 'Dataset not found'"
                  + " 로 실패한다 — 리포지토리가 자기 트랜잭션을 열어야 한다")
          .isPresent();
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void documentFileIsReadableFromBackgroundThreadShape() {
    // processIngestion 첫 문장이 이 형태다: findById(...).orElseThrow()
    TenantContext.set(tenantId);
    try {
      assertThat(documentFileRepository.findById(documentFileId))
          .as("배경 잡 형태에서 document_file 이 안 보이면 문서가 PENDING 에 영구히 고착된다")
          .isPresent();
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void contextlessBackgroundReadStillSeesNothing() {
    // fail-closed 는 유지되어야 한다 — 리포지토리가 트랜잭션을 열더라도, 테넌트가 없으면
    // GUC 가 비어 정책이 전 행을 차단해야 한다.
    TenantContext.clear();
    assertThat(datasetRepository.findById(datasetId))
        .as("테넌트 없이 보이면 fail-open 이다")
        .isEmpty();
  }

  // ── 픽스처 ────────────────────────────────────────────────────────────


  private Long insertDataset() {
    long suffix = TenantRlsTestSupport.nextTenantId();
    return dsl.insertInto(table(name("dataset")))
        .set(field(name("name"), String.class), "배경경로-" + suffix)
        .set(field(name("table_name"), String.class), "tbl_bg_" + suffix)
        .set(field(name("storage_type"), String.class), "DOCUMENT")
        .set(field(name("origin_type"), String.class), "SOURCE")
        .set(field(name("created_by"), Long.class), userId)
        .returning(field(name("id"), Long.class))
        .fetchOne()
        .get(field(name("id"), Long.class));
  }

  private Long insertDocumentFile(Long dsId) {
    long suffix = TenantRlsTestSupport.nextTenantId();
    return dsl.insertInto(table(name("document_file")))
        .set(field(name("dataset_id"), Long.class), dsId)
        .set(field(name("original_name"), String.class), "bg-" + suffix + ".pdf")
        .set(field(name("mime_type"), String.class), "application/pdf")
        .set(field(name("file_size"), Long.class), 10L)
        .set(field(name("storage_path"), String.class), "/tmp/bg-" + suffix)
        .set(field(name("status"), String.class), "PENDING")
        .set(field(name("uploaded_by"), Long.class), userId)
        .returning(field(name("id"), Long.class))
        .fetchOne()
        .get(field(name("id"), Long.class));
  }
}
