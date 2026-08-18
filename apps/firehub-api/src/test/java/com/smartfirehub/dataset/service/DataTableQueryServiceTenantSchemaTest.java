package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.dto.SqlQueryResponse;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantSchemaProvisioner;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
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
 * ({@code IntegrationTestBase} 참조). 검증 대상 호출은 {@link TenantRlsTestSupport
 * #runInTenantTransaction} 로 직접 감싼다 — {@code executeQuery} 자신은 {@code @Transactional}
 * 이 아니라("Must be called within a @Transactional context") 호출부가 트랜잭션을 열어 줘야
 * {@code SET LOCAL} 이 유효하다.
 */
class DataTableQueryServiceTenantSchemaTest extends IntegrationTestBase {

  private static final long TENANT_BASE = TenantRlsTestSupport.randomSchemaProvisioningTenantIdBase();

  @Autowired private DataTableQueryService dataTableQueryService;
  @Autowired private TenantSchemaProvisioner provisioner;
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

    try {
      // 픽스처: DatasetService 전체 흐름(카탈로그 등록 등)은 이 회귀와 무관하므로 배제하고, 스키마
      // 프로비저닝(provisioner) + 원시 테이블만 만든다. app_tenant 는 provisioner 가 준 CREATE 로
      // 이 테이블을 직접 만들 수 있다.
      TenantContext.runScopedGet(
          tenantId,
          () -> {
            provisioner.ensureCurrentTenantSchema();
            dsl.execute(
                "CREATE TABLE " + DataSchema.qualify("tenant_schema_probe") + " (name text)");
            dsl.execute(
                "INSERT INTO "
                    + DataSchema.qualify("tenant_schema_probe")
                    + " (name) VALUES ('probe')");
            return null;
          });

      // 검증 대상 호출 — 진입부 SET LOCAL(인용) 검증.
      String[] searchPathAfterRestore = new String[1];
      SqlQueryResponse response =
          TenantRlsTestSupport.runInTenantTransaction(
              tx,
              tenantId,
              () -> {
                SqlQueryResponse r =
                    dataTableQueryService.executeQuery("SELECT * FROM tenant_schema_probe", 10);
                // 복원부(무인용 TO public, <schema>) 검증 — 같은 트랜잭션 안에서 SHOW 로 직접
                // 확인한다. dataTableQueryService.executeQuery 를 한 번 더 부르지 않는 이유는,
                // 그 경로 자체가 다시 SET LOCAL 로 재설정해 복원 여부를 가리기 때문이다.
                searchPathAfterRestore[0] =
                    dsl.fetch("SHOW search_path").get(0).get(0, String.class);
                return r;
              });

      assertThat(response.error()).isNull();
      assertThat(response.rows()).hasSize(1);
      assertThat(response.rows().get(0).get("name")).isEqualTo("probe");
      // 실측 판정: 무인용 "TO public, data_t{id}" 조립도 숫자 접미사 스키마에서 그대로 해석된다
      // (Task 4 psql 프로브가 SHOW 로 확인한 것과 동일한 형태).
      assertThat(searchPathAfterRestore[0]).isEqualTo("public, " + schema);
    } finally {
      TenantRlsTestSupport.cleanupAll(
          () -> TenantRlsTestSupport.dropSchemasCreatedByThisTest(ownerDsl(), schema),
          () -> TenantRlsTestSupport.deleteTenants(dsl, tenantId));
    }
  }
}
