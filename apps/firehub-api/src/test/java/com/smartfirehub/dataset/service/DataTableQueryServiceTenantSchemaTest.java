package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.dto.SqlQueryRequest;
import com.smartfirehub.dataset.dto.SqlQueryResponse;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.List;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * P3-b2 T4 — {@link DataTableQueryService#executeQuery} 안의 두 {@code search_path} 조립 지점을
 * <b>숫자 접미사가 붙은 테넌트 스키마</b>({@code data_t{id}})로 실측한다.
 *
 * <ul>
 *   <li>진입부: {@code SET LOCAL search_path = '<schema>'}(작은따옴표 인용, 단일 스키마)
 *   <li>{@code finally} 복원부: {@code SET LOCAL search_path TO public, <schema>}(<b>인용 없는</b>
 *       무인용 식별자 목록) — 네 지점 중 유일하게 인용이 없는 형태라 가장 덜 보호받는다.
 * </ul>
 *
 * <p>기존 {@code DataTableQueryServiceTest} 는 클래스 레벨 {@code @Transactional} 로 테넌트
 * 1(물리 스키마 {@code data}, 접미사 없음)만 도는데, 그 상태로는 두 문장 모두 우연히 맞아도
 * 숫자 접미사에서 깨지는 형태를 가릴 수 있다. 이 클래스는 클래스 레벨 {@code @Transactional} 을
 * 쓰지 않는다 — 테넌트 N 컨텍스트로 새 물리 트랜잭션을 열어야 그 시점 GUC 가 주입되기 때문이다
 * ({@code IntegrationTestBase} 참조).
 *
 * <p><b>라운드 2 리뷰 NIT-1 반영 — 검증 대상 호출을 {@code DatasetDataService.executeQuery}
 * (자가 {@code @Transactional}, 프로덕션의 유일한 실제 호출부)로 바꿨다.</b> 이전 버전은
 * {@code DataTableQueryService.executeQuery} 를 {@link TenantRlsTestSupport
 * #runInTenantTransaction} 로 직접 감싸 불렀는데, 이건 {@code IntegrationTestBase
 * .inTenantFixture} Javadoc 이 가장 강한 어조로 금지하는 패턴과 효과가 같다 — "테스트가 열어 준
 * 트랜잭션이 GUC 를 공급해, 프로덕션 경로가 스스로 트랜잭션·테넌트 컨텍스트를 세우지 못한다는
 * 배선 결함을 영구히 가린다." {@code DatasetDataService.executeQuery} 를 통해 부르면 그 메서드
 * 자신의 {@code @Transactional} 이 트랜잭션을 열므로 배선까지 함께 증명된다(Analytics 쪽
 * 테스트가 이미 이 형태다).
 *
 * <p>이 전환으로 <b>같은 트랜잭션 안에서 SHOW search_path 를 직접 조회</b>하던 이전 방식은 더 이상
 * 쓸 수 없다(호출부가 자기 트랜잭션을 열고 닫으므로 외부에서 그 안을 들여다볼 수 없다). 대신
 * {@code DatasetDataService.executeQuery} 가 {@code dataTableQueryService.executeQuery} 호출
 * (복원부 포함) <b>직후, 같은 트랜잭션 안에서</b> {@code query_history} 에 결과를 저장한다는
 * 사실을 이용한다 — {@code query_history} 는 {@code public} 스키마 테이블이므로, 복원부
 * ({@code SET LOCAL search_path TO public, <schema>})가 실패했다면 이 INSERT 자체가 스키마를
 * 찾지 못해 전체 {@code @Transactional} 메서드가 예외로 끝났을 것이다. 저장된 {@code
 * query_history} 행을 사후 조회해 확인하는 것이 곧 복원부가 실행됐다는 증거다.
 */
class DataTableQueryServiceTenantSchemaTest extends IntegrationTestBase {

  private static final long TENANT_BASE = TenantRlsTestSupport.randomSchemaProvisioningTenantIdBase();

  private static final List<DatasetColumnRequest> COLUMNS =
      List.of(new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null));

  @Autowired private DatasetService datasetService;
  @Autowired private DatasetDataService datasetDataService;
  @Autowired private DSLContext dsl;
  @Autowired private TransactionTemplate tx;

  @Autowired
  @Qualifier("schemaOwnerDataSource")
  private DataSource schemaOwnerDataSource;

  private DSLContext ownerDsl() {
    return DSL.using(schemaOwnerDataSource, SQLDialect.POSTGRES);
  }

  @Test
  void executeQuery_setsAndRestoresSearchPath_forSuffixedTenant() {
    long tenantId = TENANT_BASE + 1;
    TenantRlsTestSupport.insertActiveTenant(dsl, tenantId);
    String schema = TenantContext.runScopedGet(tenantId, DataSchema::current); // "data_t{id}"
    Long userId = TenantRlsTestSupport.insertUser(dsl, "t4-dtq-suffixed");

    try {
      // 픽스처: 실제 프로덕션 경로(DatasetService.createDataset)로 카탈로그 행 + 물리 테이블을
      // 함께 만든다 — DatasetDataService.executeQuery 가 datasetId 로 dataset 을 조회하므로
      // 카탈로그 행이 반드시 있어야 한다.
      Long datasetId =
          TenantContext.runScopedGet(
              tenantId,
              () -> {
                DatasetDetailResponse created =
                    datasetService.createDataset(
                        new CreateDatasetRequest(
                            "T4 접미사 검증",
                            "tenant_schema_probe",
                            null,
                            null,
                            "TABLE",
                            "SOURCE",
                            COLUMNS,
                            null),
                        userId);
                dsl.execute(
                    "INSERT INTO "
                        + DataSchema.qualify("tenant_schema_probe")
                        + " (name) VALUES ('probe')");
                return created.id();
              });

      // 검증 대상 호출 — 실제 프로덕션 진입점 하나로 진입부 SET LOCAL(인용)과 복원부(무인용
      // TO public, <schema>) 를 모두 실제 배선 그대로 실행한다.
      SqlQueryResponse response =
          TenantContext.runScopedGet(
              tenantId,
              () ->
                  datasetDataService.executeQuery(
                      datasetId, new SqlQueryRequest("SELECT * FROM tenant_schema_probe", 10), userId));

      assertThat(response.error()).isNull();
      assertThat(response.rows()).hasSize(1);
      assertThat(response.rows().get(0).get("name")).isEqualTo("probe");

      // 실측 판정: query_history(public 스키마) 저장이 성공했다는 것 자체가 복원부(무인용
      // "TO public, data_t{id}")가 숫자 접미사 스키마에서도 정확히 실행됐다는 증거다 — 복원이
      // 실패했다면 이 INSERT 가 스키마를 못 찾아 executeQuery 전체가 예외로 끝났을 것이다.
      Integer historyCount =
          TenantRlsTestSupport.runInTenantTransaction(
              tx,
              tenantId,
              () ->
                  dsl.fetchCount(
                      DSL.selectOne()
                          .from(DSL.table(DSL.name("query_history")))
                          .where(DSL.field(DSL.name("dataset_id"), Long.class).eq(datasetId))
                          .and(
                              DSL.field(DSL.name("success"), Boolean.class).eq(true))));
      assertThat(historyCount).as("복원 성공을 증명하는 query_history 행이 저장돼 있어야 한다").isEqualTo(1);
    } finally {
      TenantRlsTestSupport.cleanupAll(
          // dataset 삭제가 query_history 를 CASCADE 로 함께 지운다(V24).
          () -> TenantRlsTestSupport.deleteOwnDatasetRows(dsl, tx, tenantId),
          () -> TenantRlsTestSupport.deleteOwnAuditLogRows(dsl, tx, tenantId),
          () -> TenantRlsTestSupport.dropSchemasCreatedByThisTest(ownerDsl(), schema),
          () -> TenantRlsTestSupport.deleteTenants(dsl, tenantId),
          () -> TenantRlsTestSupport.deleteUser(dsl, userId));
    }
  }


}
