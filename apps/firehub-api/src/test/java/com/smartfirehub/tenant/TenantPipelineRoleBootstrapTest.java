package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.config.TenantPipelineDataSourceRegistry;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantPipelineRole;
import com.smartfirehub.global.tenant.TenantPipelineRoleBootstrap;
import com.smartfirehub.global.tenant.TenantSchemaProvisioner;
import com.smartfirehub.pipeline.service.ColumnInfo;
import com.smartfirehub.pipeline.service.SqlColumnProbe;
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

/**
 * 기동 치유({@link TenantPipelineRoleBootstrap})가 <b>2026-09-17 장애 상태의 테넌트를 실제로
 * 되살리는지</b> 확인한다(#680).
 *
 * <p>이 테스트는 장애 당시 테넌트 2의 상태를 그대로 재현한다:
 *
 * <ul>
 *   <li>테넌트는 있고 {@code data_t{id}} 스키마도 있다(데이터셋을 이미 만들었으므로)
 *   <li>그런데 {@code pipeline_executor_t{id}} 롤이 없어, 스키마가 executor GRANT 없이 만들어졌다
 * </ul>
 *
 * <p>그 상태에서 파이프라인을 돌리면 {@code permission denied for schema data_t{id}} 가 난다.
 * 중요한 것은 <b>이 상태가 스스로 낫지 않는다</b>는 점이다 — {@link TenantSchemaProvisioner} 의
 * 자가치유는 테이블을 <b>만들 때</b>만 돌고, 파이프라인은 읽기만 하기 때문이다. 그래서 운영자가
 * 손으로 고치기 전까지 영구히 고장이었다. 이 테스트는 <b>배포만으로</b> 낫는지를 본다.
 */
class TenantPipelineRoleBootstrapTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;

  @Autowired private TenantPipelineRoleBootstrap bootstrap;

  @Autowired private TenantSchemaProvisioner schemaProvisioner;

  @Autowired private SqlColumnProbe sqlColumnProbe;

  @Autowired private TenantPipelineDataSourceRegistry tenantPipelineDataSources;

  @Autowired
  @Qualifier("schemaOwnerDataSource")
  private DataSource schemaOwnerDataSource;

  private static final long TENANT_BASE = TenantRlsTestSupport.randomSchemaProvisioningTenantIdBase();

  private DSLContext ownerDsl() {
    return DSL.using(schemaOwnerDataSource, SQLDialect.POSTGRES);
  }

  @Test
  void healTenant_repairsTenantWhoseSchemaWasCreatedWithoutExecutorRole() {
    long tenantId = TENANT_BASE + 21;
    TenantRlsTestSupport.insertActiveTenant(dsl, tenantId);
    String schema = DataSchema.forTenant(tenantId);
    String roleName = TenantPipelineRole.roleName(tenantId);
    String table = schema + ".bootstrap_guard";
    String sql = "SELECT post_id, title FROM " + table;

    try {
      // ── 장애 상태 재현 ─────────────────────────────────────────────────────────
      // 롤이 없는 채로 스키마를 만든다. 프로비저너는 롤이 없으면 executor GRANT 6문장을 통째로
      // 건너뛰므로, 이것이 테넌트 2의 data_t2 와 같은 상태다.
      TenantContext.runScoped(tenantId, schemaProvisioner::ensureCurrentTenantSchema);
      dsl.execute("CREATE TABLE " + table + " (post_id INT, title VARCHAR(200))");

      assertThat(roleExists(roleName)).as("치유 전에는 롤이 없다").isFalse();
      // 치유 전에는 파이프라인이 실패해야 한다 — 그래야 아래 초록이 의미를 갖는다. 실패 이유는
      // 롤 부재(접속 실패)이고, 롤만 만들고 스키마 GRANT 를 빠뜨리면 이번엔
      // "permission denied for schema data_t{id}" 로 바뀐다. 즉 이 테스트는 치유의 두 절반
      // (ensureRole · healSchemaGrants) 중 하나만 빠져도 빨개진다.
      assertThatThrownBy(
              () -> TenantContext.runScopedGet(tenantId, () -> sqlColumnProbe.columnsWithTypes(sql)))
          .hasMessageContaining("SQL 컬럼 타입 분석 실패");

      // ── 배포(=기동 치유) ──────────────────────────────────────────────────────
      bootstrap.healTenant(tenantId);

      // ── 같은 파이프라인이 이제 돈다 ───────────────────────────────────────────
      List<ColumnInfo> columns =
          TenantContext.runScopedGet(tenantId, () -> sqlColumnProbe.columnsWithTypes(sql));

      assertThat(columns)
          .containsExactly(new ColumnInfo("post_id", "INTEGER"), new ColumnInfo("title", "TEXT"));
    } finally {
      TenantRlsTestSupport.cleanupAll(
          () -> dsl.execute("DROP TABLE IF EXISTS " + table),
          () -> TenantRlsTestSupport.dropSchemasCreatedByThisTest(ownerDsl(), schema),
          // 실제 접속을 했으므로 이 테넌트의 Hikari 풀이 남는다 — test 프로필 상한(max-pools: 3)을
          // 먹고 다른 테스트의 풀을 축출하지 않도록 닫는다.
          tenantPipelineDataSources::closeAllPools,
          () -> TenantRlsTestSupport.dropPipelineLoginRole(ownerDsl(), roleName),
          () -> TenantRlsTestSupport.deleteTenants(dsl, tenantId));
    }
  }

  private boolean roleExists(String roleName) {
    return TenantRlsTestSupport.roleExists(ownerDsl(), roleName);
  }
}
