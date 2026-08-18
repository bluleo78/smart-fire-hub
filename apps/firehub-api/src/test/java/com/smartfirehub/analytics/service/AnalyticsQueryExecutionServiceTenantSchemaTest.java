package com.smartfirehub.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.analytics.dto.AnalyticsQueryResponse;
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

/**
 * P3-b2 T4 — {@link AnalyticsQueryExecutionService} 의 {@code SET LOCAL search_path = '<schema>',
 * 'public'}(작은따옴표 인용 + 콤마로 이어붙인 두 스키마 목록) 조립이 <b>숫자 접미사가 붙은</b>
 * 테넌트 스키마({@code data_t{id}})에서도 실제 테이블 참조를 올바르게 해석하는지 실측한다.
 *
 * <p>기존 {@code AnalyticsQueryExecutionServiceTest} 는 클래스 레벨 {@code @Transactional} 로
 * 테넌트 1(물리 스키마 {@code data}, 접미사 없음)만 돈다 — 그 상태로는 이 조립이 우연히 맞아도
 * 숫자 접미사에서 깨지는 형태를 가릴 수 있다. 이 클래스는 클래스 레벨 {@code @Transactional} 을
 * 쓰지 않는다 — 테넌트 N 컨텍스트로 <b>새 물리 트랜잭션</b>을 열어야 그 시점 GUC 가 주입되기
 * 때문이다({@code IntegrationTestBase} 참조). {@link AnalyticsQueryExecutionService#execute} 는
 * 그 자체로 {@code @Transactional} 이므로(자가 트랜잭션 관리) {@link TenantContext#runScopedGet}
 * 만으로 충분하다 — {@code DataTableQueryService} 와 달리 별도 {@code TransactionTemplate} 이
 * 필요 없다.
 */
class AnalyticsQueryExecutionServiceTenantSchemaTest extends IntegrationTestBase {

  private static final long TENANT_BASE = TenantRlsTestSupport.randomSchemaProvisioningTenantIdBase();

  @Autowired private AnalyticsQueryExecutionService executionService;
  @Autowired private TenantSchemaProvisioner provisioner;
  @Autowired private DSLContext dsl;

  @Autowired
  @Qualifier("schemaOwnerDataSource")
  private DataSource schemaOwnerDataSource;

  private DSLContext ownerDsl() {
    return DSL.using(schemaOwnerDataSource, SQLDialect.POSTGRES);
  }

  @Test
  void execute_selectQuery_resolvesQuotedSchemaWithCommaPublic_forSuffixedTenant() {
    long tenantId = TENANT_BASE + 1;
    TenantRlsTestSupport.insertActiveTenant(dsl, tenantId);
    String schema = TenantContext.runScopedGet(tenantId, DataSchema::current); // "data_t{id}"

    try {
      // 픽스처: DatasetService 전체 흐름(카탈로그 등록 등)은 이 회귀와 무관하므로 배제하고, 스키마
      // 프로비저닝(provisioner) + 원시 테이블만 만든다.
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

      // 검증 대상 호출 — execute() 는 자가 @Transactional 이라 이 시점에 새 물리 트랜잭션이
      // 열리고, 그 시점의 TenantContext(=tenantId)로 GUC 가 주입된다.
      AnalyticsQueryResponse response =
          TenantContext.runScopedGet(
              tenantId,
              () -> executionService.execute("SELECT * FROM tenant_schema_probe", 10, true));

      // 실측 판정: 인용 + 콤마 목록 조립("data_t{id}", 'public')이 숫자 접미사 스키마에서도
      // 정확히 그 스키마의 테이블을 해석한다(Task 4 psql 프로브가 SHOW 로 확인한 것을 여기서는
      // 실제 프로덕션 경로의 SELECT 결과로 확인한다).
      assertThat(response.error()).isNull();
      assertThat(response.rows()).hasSize(1);
      assertThat(response.rows().get(0).get("name")).isEqualTo("probe");
    } finally {
      TenantRlsTestSupport.cleanupAll(
          () -> TenantRlsTestSupport.dropSchemasCreatedByThisTest(ownerDsl(), schema),
          () -> TenantRlsTestSupport.deleteTenants(dsl, tenantId));
    }
  }
}
