package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantPipelineRole;
import com.smartfirehub.global.tenant.TenantSchemaProvisioner;
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
import org.springframework.beans.factory.annotation.Value;

/**
 * {@link SqlColumnProbe} 가 <b>테넌트별 파이프라인 롤</b>로 probe 쿼리를 던지는지 실측한다.
 *
 * <p><b>이 테스트가 막는 회귀(실제 장애).</b> P3-b2 가 스키마를 {@code data_t{id}} 로 분리할 때 실행
 * 경로({@code SqlScriptExecutor})만 테넌트 롤로 옮기고 probe 경로는 공용 {@code pipeline_executor} 에
 * 남겨 뒀다. 그 롤은 {@code data} 에만 USAGE 가 있어서, 테넌트 2의 파이프라인이 운영에서 이렇게 터졌다:
 *
 * <pre>SQL 컬럼 타입 분석 실패: ERROR: permission denied for schema data_t2</pre>
 *
 * <p><b>왜 테넌트 1로는 이 결함을 잡을 수 없는가.</b> 테넌트 1의 스키마는 {@code data} 이고 공용 롤이
 * 거기에 USAGE 를 갖고 있어, 잘못된 배선으로도 probe 가 성공한다. 그래서 반드시 접미사 테넌트
 * ({@code data_t{id}})로 검증한다 — {@code SqlScriptExecutorSandboxTest} 의 접미사 테넌트 테스트와 같은
 * 근거다.
 */
class SqlColumnProbeSandboxTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;

  @Autowired private SqlColumnProbe sqlColumnProbe;

  @Autowired private TenantSchemaProvisioner provisioner;

  @Autowired
  @Qualifier("schemaOwnerDataSource")
  private DataSource schemaOwnerDataSource;

  /** 롤 비밀번호 파생 HMAC 키 — 하드코딩하면 레지스트리가 계산하는 값과 어긋난다. */
  @Value("${app.pipeline.role-password-secret}")
  private String rolePasswordSecret;

  private static final long TENANT_BASE = TenantRlsTestSupport.randomSchemaProvisioningTenantIdBase();

  private DSLContext ownerDsl() {
    return DSL.using(schemaOwnerDataSource, SQLDialect.POSTGRES);
  }

  @Test
  void columnsWithTypes_runsAsTenantPipelineRole_forSuffixedTenant() {
    long tenantId = TENANT_BASE + 7;
    TenantRlsTestSupport.insertActiveTenant(dsl, tenantId);
    String schema = TenantContext.runScopedGet(tenantId, DataSchema::current); // data_t{id}
    String executorRole = TenantPipelineRole.roleName(tenantId);
    String probeTable = schema + ".probe_columns_guard";

    try {
      // 롤을 먼저 만든다 — ensureCurrentTenantSchema() 는 롤이 이미 있어야 스키마 GRANT 를 함께 건다.
      ownerDsl()
          .execute(
              "CREATE ROLE "
                  + executorRole
                  + " LOGIN PASSWORD '"
                  + TenantPipelineRole.password(tenantId, rolePasswordSecret)
                  + "'");
      String db = ownerDsl().fetch("SELECT current_database()").get(0).get(0, String.class);
      ownerDsl().execute("GRANT CONNECT ON DATABASE \"" + db + "\" TO " + executorRole);

      TenantContext.runScopedGet(
          tenantId,
          () -> {
            provisioner.ensureCurrentTenantSchema();
            return null;
          });

      // 공용 pipeline_executor 에는 이 스키마 권한을 **주지 않는다** — 그것이 이 테스트의 판별력이다.
      dsl.execute("CREATE TABLE " + probeTable + " (post_id INT, title VARCHAR(500), hit BOOLEAN)");
      dsl.execute("GRANT SELECT ON " + probeTable + " TO " + executorRole);

      List<ColumnInfo> columns =
          TenantContext.runScopedGet(
              tenantId,
              () ->
                  sqlColumnProbe.columnsWithTypes(
                      "SELECT post_id, title, hit FROM " + probeTable));

      assertThat(columns)
          .as("테넌트 롤로 접속해야 접미사 스키마의 컬럼을 읽을 수 있다")
          .containsExactly(
              new ColumnInfo("post_id", "INTEGER"),
              new ColumnInfo("title", "TEXT"),
              new ColumnInfo("hit", "BOOLEAN"));
    } finally {
      TenantRlsTestSupport.cleanupAll(
          () -> dsl.execute("DROP TABLE IF EXISTS " + probeTable),
          () -> TenantRlsTestSupport.dropSchemasCreatedByThisTest(ownerDsl(), schema),
          () -> TenantRlsTestSupport.dropPipelineLoginRole(ownerDsl(), executorRole),
          () -> TenantRlsTestSupport.deleteTenants(dsl, tenantId));
    }
  }
}
