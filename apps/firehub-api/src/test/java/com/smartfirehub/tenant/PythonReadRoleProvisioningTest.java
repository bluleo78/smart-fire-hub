package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantPipelineRole;
import com.smartfirehub.global.tenant.TenantPipelineRoleProvisioner;
import com.smartfirehub.global.tenant.TenantSchemaProvisioner;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.PostgresTestContainer;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;

/**
 * PYTHON 읽기 슬롯 롤 프로비저닝(스펙 §4.4, WD-29)을 실측한다 — "메서드가 불렸다"가 아니라 운영자가 눈으로 확인할 상태(실제 로그인, DB 한정
 * search_path, 스키마 USAGE, 다른 테넌트 스키마 USAGE 없음)를 본다.
 *
 * <p>테이블 SELECT 는 이 태스크 범위가 아니다(PythonReadGrantSync, Task 3). 그래서 여기서는 "SELECT 없음"을 단언하지 않는다 — 테넌트
 * 1 쪽에 그렇게 단언하면 Task 3 의 기동 동기화가 들어오는 순간 깨지고, 새 스키마에는 테이블이 없어 단언 자체가 공허하다.
 */
class PythonReadRoleProvisioningTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private TenantPipelineRoleProvisioner provisioner;
  @Autowired private TenantSchemaProvisioner schemaProvisioner;

  @Autowired
  @Qualifier("schemaOwnerDataSource")
  private DataSource schemaOwnerDataSource;

  @Value("${app.pipeline.role-password-secret}")
  private String secret;

  private static final long TENANT_BASE =
      TenantRlsTestSupport.randomSchemaProvisioningTenantIdBase();

  private DSLContext ownerDsl() {
    return DSL.using(schemaOwnerDataSource, SQLDialect.POSTGRES);
  }

  /** 프로덕션 executor 와 같은 방식(롤 이름 + 파생 비밀번호)으로 직접 접속해 current_user 를 되읽는다. */
  private String loginAs(String role, String password) throws SQLException {
    try (Connection c =
            DriverManager.getConnection(
                PostgresTestContainer.INSTANCE.getJdbcUrl(), role, password);
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("select current_user")) {
      rs.next();
      return rs.getString(1);
    }
  }

  private boolean schemaUsage(String role, String schema) {
    return ownerDsl()
        .fetchOne("select has_schema_privilege(?, ?, 'USAGE')", role, schema)
        .get(0, Boolean.class);
  }

  /** V137 + AFTER_MIGRATE 콜백 — 테넌트 1 의 슬롯 롤 10개가 파생 비밀번호로 실제 로그인된다(배포 직후 상태). */
  @Test
  void v137_tenant1SlotRolesLoginWithDerivedPassword() throws SQLException {
    for (int k = 1; k <= TenantPipelineRole.PYTHON_READ_SLOTS; k++) {
      String role = TenantPipelineRole.pythonReadRoleName(1, k);
      assertThat(loginAs(role, TenantPipelineRole.pythonReadPassword(1, k, secret)))
          .isEqualTo(role);
    }
    var r =
        ownerDsl()
            .fetchOne(
                "select rolsuper, rolinherit, rolcreaterole, rolbypassrls, rolcreatedb"
                    + " from pg_roles where rolname = ?",
                "pipeline_py_t1_s1");
    assertThat(r.get(0, Boolean.class)).as("NOSUPERUSER").isFalse();
    assertThat(r.get(1, Boolean.class)).as("NOINHERIT").isFalse();
    assertThat(r.get(2, Boolean.class)).as("NOCREATEROLE").isFalse();
    assertThat(r.get(3, Boolean.class)).as("NOBYPASSRLS").isFalse();
    assertThat(r.get(4, Boolean.class)).as("NOCREATEDB").isFalse();
    // search_path 는 DB 한정(setdatabase != 0) — 런북 §1-5 함정
    Long setdb =
        ownerDsl()
            .fetchOne(
                "select s.setdatabase::bigint from pg_db_role_setting s"
                    + " join pg_roles r on r.oid = s.setrole where r.rolname = ?",
                "pipeline_py_t1_s1")
            .get(0, Long.class);
    assertThat(setdb).isNotZero();
    assertThat(schemaUsage("pipeline_py_t1_s1", "data")).isTrue();
  }

  /**
   * 신규 테넌트: 롤 먼저 → 스키마 나중(데이터셋 첫 생성) 순서에서도 USAGE 가 걸린다. 반대 순서(스키마 먼저)는 ensurePythonReadRoles 가
   * 건다({@link #scratchTenant_schemaThenRoles_grantsUsageFromRoleProvisioner}). 기동 치유 경로는
   * TenantPipelineRoleBootstrapTest 가 본다.
   */
  @Test
  void scratchTenant_rolesThenSchema_grantsUsageOnOwnSchemaOnly() throws SQLException {
    long tenantId = TENANT_BASE + 21;
    String schema = DataSchema.forTenant(tenantId);
    TenantRlsTestSupport.insertActiveTenant(dsl, tenantId);
    try {
      assertThat(provisioner.pythonReadRolesExist(tenantId)).as("프로비저닝 전").isFalse();
      provisioner.ensureRole(tenantId);
      provisioner.ensurePythonReadRoles(tenantId);
      assertThat(provisioner.pythonReadRolesExist(tenantId)).isTrue();
      String role = TenantPipelineRole.pythonReadRoleName(tenantId, 3);
      assertThat(loginAs(role, TenantPipelineRole.pythonReadPassword(tenantId, 3, secret)))
          .isEqualTo(role);

      TenantContext.runScoped(tenantId, schemaProvisioner::ensureCurrentTenantSchema);
      for (int k = 1; k <= TenantPipelineRole.PYTHON_READ_SLOTS; k++) {
        assertThat(schemaUsage(TenantPipelineRole.pythonReadRoleName(tenantId, k), schema))
            .as("슬롯 %d 의 자기 테넌트 스키마 USAGE", k)
            .isTrue();
      }
      // 다른 테넌트(1)의 스키마는 쓰지 못한다
      assertThat(schemaUsage(role, "data")).isFalse();
      // 멱등 — 스키마가 생긴 뒤 다시 불러도 실패하지 않고 같은 비밀번호로 접속된다
      provisioner.ensurePythonReadRoles(tenantId);
      assertThat(loginAs(role, TenantPipelineRole.pythonReadPassword(tenantId, 3, secret)))
          .isEqualTo(role);
    } finally {
      // 스키마 DROP(이름 가드 헬퍼) → 슬롯 롤 → 실행 롤 → 테넌트 순서. 롤이 스키마 권한을 가진 채면 DROP ROLE 이 거부되지만
      // dropPipelineLoginRole 의 DROP OWNED 가 그 권한도 걷는다.
      TenantRlsTestSupport.cleanupAll(
          () -> TenantRlsTestSupport.dropSchemasCreatedByThisTest(ownerDsl(), schema),
          () -> TenantRlsTestSupport.dropPythonReadRoles(ownerDsl(), tenantId),
          () ->
              TenantRlsTestSupport.dropPipelineLoginRole(
                  ownerDsl(), TenantPipelineRole.roleName(tenantId)),
          () -> TenantRlsTestSupport.deleteTenants(dsl, tenantId));
    }
  }

  /**
   * 반대 순서: 실행 롤·스키마가 이미 완비된 테넌트(기본 권한까지 걸려 스키마 프로비저너가 단락되는 상태 — 운영의 기존 테넌트)에 슬롯 롤이 나중에 생긴다. 이때
   * USAGE 는 스키마 프로비저너가 다시 들어오지 않으므로 ensurePythonReadRoles 의 schemaExists 분기만 건다.
   */
  @Test
  void scratchTenant_schemaThenRoles_grantsUsageFromRoleProvisioner() {
    long tenantId = TENANT_BASE + 22;
    String schema = DataSchema.forTenant(tenantId);
    TenantRlsTestSupport.insertActiveTenant(dsl, tenantId);
    try {
      provisioner.ensureRole(tenantId);
      TenantContext.runScoped(tenantId, schemaProvisioner::ensureCurrentTenantSchema);
      assertThat(provisioner.pythonReadRolesExist(tenantId)).isFalse();

      provisioner.ensurePythonReadRoles(tenantId);

      for (int k = 1; k <= TenantPipelineRole.PYTHON_READ_SLOTS; k++) {
        assertThat(schemaUsage(TenantPipelineRole.pythonReadRoleName(tenantId, k), schema))
            .as("슬롯 %d 의 스키마 USAGE(롤 프로비저너가 건 것)", k)
            .isTrue();
      }
    } finally {
      TenantRlsTestSupport.cleanupAll(
          () -> TenantRlsTestSupport.dropSchemasCreatedByThisTest(ownerDsl(), schema),
          () -> TenantRlsTestSupport.dropPythonReadRoles(ownerDsl(), tenantId),
          () ->
              TenantRlsTestSupport.dropPipelineLoginRole(
                  ownerDsl(), TenantPipelineRole.roleName(tenantId)),
          () -> TenantRlsTestSupport.deleteTenants(dsl, tenantId));
    }
  }
}
