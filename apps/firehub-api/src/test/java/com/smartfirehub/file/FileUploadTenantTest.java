package com.smartfirehub.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.file.dto.FileUploadResponse;
import com.smartfirehub.file.service.FileUploadService;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@code uploaded_files} 요청 경로의 테넌트 배선 검증 (P2-b Task 7).
 *
 * <p><b>이 테스트가 지키는 것은 심층 방어가 아니라 실제 결함 수정이다.</b> {@code FileUploadService}
 * 는 리포지토리를 거치지 않고 {@code DSLContext} 로 {@code uploaded_files} 를 직접 읽고 쓴다 —
 * 즉 Task 1 이 리포지토리에 붙인 클래스 레벨 {@code @Transactional} 이 이 경로를 커버하지 못한다.
 * V93 이 {@code tenant_id} 를 NOT NULL + GUC 기반 DEFAULT 로 추가했으므로, 트랜잭션이 열리지 않으면
 * {@code current_setting('app.tenant_id', true)} 가 NULL 을 주고 <b>업로드가 NOT NULL 위반으로
 * 500 이 된다</b>. Task 7 의 {@code TransactionTemplate} 래핑과 {@code @Transactional(readOnly)} 를
 * 되돌리면 이 테스트가 실제로 깨진다 — 형제 {@code AsyncJobTenantTest} 와 달리 여기서는
 * 애노테이션/래핑이 유일한 방어선이다.
 *
 * <p><b>클래스 레벨 {@code @Transactional} 이 없다 — 의도된 것이다.</b> 붙이면 테스트 트랜잭션이
 * GUC 를 공급해 프로덕션의 배선 누락을 구조적으로 가린다. 검증 대상 호출은 트랜잭션 밖에 남긴다
 * (선례: {@code apiconnection/ApiConnectionTenantTest}, {@code job/AsyncJobTenantTest}).
 *
 * <p>Task 9(V96)에서 {@code uploaded_files} 에 정책이 붙었으므로 양방향 격리 단언
 * ({@code assertTwoSidedIsolation})을 함께 둔다. 픽스처·검증 조회는 트랜잭션으로 감싸지만
 * <b>검증 대상 호출은 여전히 트랜잭션 밖</b>이라는 점이 이 테스트의 판별력이다.
 */
class FileUploadTenantTest extends IntegrationTestBase {

  @Autowired private FileUploadService fileUploadService;
  @Autowired private DSLContext dsl;

  /** RLS 가 걸린 테이블을 픽스처·검증이 직접 만질 때 쓰는 트랜잭션 경계. */
  @Autowired private TransactionTemplate tx;

  private Long tenantA;
  private Long tenantB;
  private Long userA;

  @BeforeEach
  void seedTenant() {
    // tenant·user 는 테넌트 경계 위의 전역 테이블이라 컨텍스트 없이 만들 수 있다.
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "upload-a");
    tenantB = TenantRlsTestSupport.createActiveTenant(dsl, "upload-b");
    userA = TenantRlsTestSupport.insertUser(dsl, "upload_a_");
  }

  @AfterEach
  void cleanUp() {
    // V96 이후 uploaded_files 는 RLS 대상이라 트랜잭션 밖 삭제는 0행이 되고, 뒤이은 user 삭제가
    // FK 로 터진다. 정리 삭제만 테넌트 트랜잭션으로 감싼다(검증 대상 호출은 여전히 밖에 있다).
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        tenantA,
        () ->
            dsl.deleteFrom(table(name("uploaded_files")))
                .where(field(name("uploaded_by"), Long.class).eq(userA))
                .execute());
    TenantRlsTestSupport.deleteUser(dsl, userA);
    TenantRlsTestSupport.deleteTenants(dsl, tenantA, tenantB);
    TenantContext.clear();
  }

  /**
   * 앰비언트 트랜잭션 없이 업로드해도 {@code tenant_id} 가 GUC 에서 채워지는지.
   *
   * <p>배선을 되돌리면 삽입이 NOT NULL 위반으로 터진다 — 이 테스트는 그 회귀를 잡는다.
   */
  @Test
  @DisplayName("트랜잭션 밖에서 업로드해도 tenant_id 가 채워진다")
  void uploadOutsideTransactionFillsTenantId() throws Exception {
    MultipartFile file =
        new MockMultipartFile("file", "tenant-upload.txt", "text/plain", "hello".getBytes());

    TenantContext.set(tenantA);
    List<FileUploadResponse> uploaded = fileUploadService.uploadFiles(List.of(file), userA);

    assertThat(uploaded).hasSize(1);
    // 검증 조회도 RLS 대상이다 — 트랜잭션 밖에서 읽으면 GUC 가 없어 정책이 막고 null 이 된다.
    Long storedTenantId =
        TenantRlsTestSupport.runInTenantTransaction(
            tx,
            tenantA,
            () ->
                dsl.select(field(name("tenant_id"), Long.class))
                    .from(table(name("uploaded_files")))
                    .where(field(name("id"), Long.class).eq(uploaded.get(0).id()))
                    .fetchOne(field(name("tenant_id"), Long.class)));
    assertThat(storedTenantId)
        .as("GUC 가 주입되지 않으면 DEFAULT 가 NULL 이 되어 삽입 자체가 실패한다")
        .isEqualTo(tenantA);
  }

  /** 조회 경로도 트랜잭션 밖에서 호출된다 — Task 9 이후 GUC 가 없으면 조용히 0행이 된다. */
  @Test
  @DisplayName("트랜잭션 밖에서 파일 메타데이터를 조회할 수 있다")
  void getFileInfoOutsideTransactionSucceeds() throws Exception {
    MultipartFile file =
        new MockMultipartFile("file", "tenant-info.txt", "text/plain", "hello".getBytes());

    TenantContext.set(tenantA);
    Long fileId = fileUploadService.uploadFiles(List.of(file), userA).get(0).id();

    assertThat(fileUploadService.getFileInfo(fileId, userA).id()).isEqualTo(fileId);
  }

  /**
   * V96 정책이 실제로 격리하는지 양방향으로 확인한다.
   *
   * <p>단방향("타 테넌트에서 0행")만 보면 빈 테이블에서 공허하게 통과한다 — 소유 테넌트에서
   * 보이는 것을 함께 확인해야 단언이 의미를 갖는다. 정책을 지우면 "남의 행이 보이면 격리 실패"
   * 쪽이 깨진다.
   */
  @Test
  @DisplayName("uploaded_files 는 테넌트 간 양방향으로 격리된다")
  void uploadedFilesIsolatedBothWays() {
    TenantRlsTestSupport.assertTwoSidedIsolation(
        tx,
        dsl,
        tenantA,
        tenantB,
        "uploaded_files",
        () ->
            dsl.insertInto(table(name("uploaded_files")))
                .set(field(name("original_name"), String.class), "iso.txt")
                .set(field(name("stored_name"), String.class), "iso-stored.txt")
                .set(field(name("mime_type"), String.class), "text/plain")
                .set(field(name("file_size"), Long.class), 5L)
                .set(field(name("file_category"), String.class), "DOCUMENT")
                .set(field(name("storage_path"), String.class), "/tmp/iso-stored.txt")
                .set(field(name("uploaded_by"), Long.class), userA)
                .set(
                    field(name("expires_at"), java.time.OffsetDateTime.class),
                    java.time.OffsetDateTime.now().plusDays(1))
                .returning(field(name("id"), Long.class))
                .fetchOne(field(name("id"), Long.class)));
  }
}
