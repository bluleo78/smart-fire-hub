package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;

import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.MissingTenantScopeException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantPipelineRole;
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
 * {@link TenantSchemaProvisioner} 의 지연 생성·멱등성·권한 부여를 검증한다.
 *
 * <p><b>모든 테스트는 900_000_xxx 대역의 테넌트를 쓴다</b> — 이 대역이라야 {@code data_t900000xxx}
 * 가 파생되어 삭제 가드({@link TenantRlsTestSupport#dropSchemasCreatedByThisTest})를 통과한다.
 * 테넌트 1 로 프로비저너를 시험하면 {@code data} 자체를 건드리게 되므로 절대 쓰지 않는다.
 */
class TenantSchemaProvisionerTest extends IntegrationTestBase {

  @Autowired private TenantSchemaProvisioner provisioner;
  @Autowired private DSLContext dsl;

  @Autowired
  @Qualifier("schemaOwnerDataSource")
  private DataSource schemaOwnerDataSource;

  /** DROP SCHEMA 는 소유자(app)만 할 수 있다 — 런타임 롤(app_tenant)은 스키마 소유자가 아니다. */
  private DSLContext ownerDsl() {
    return DSL.using(schemaOwnerDataSource, SQLDialect.POSTGRES);
  }

  /** 스키마가 없으면 만든다 — 그리고 자기가 만든 것만 지운다. */
  @Test
  void createsSchemaWhenAbsent() {
    long tenantId = 900_000_001L;
    TenantRlsTestSupport.insertActiveTenant(dsl, tenantId);
    String schema = TenantContext.runScopedGet(tenantId, DataSchema::current); // → "data_t900000001"
    try {
      TenantContext.runScopedGet(
          tenantId,
          () -> {
            provisioner.ensureCurrentTenantSchema();
            return null;
          });
      assertThat(schemaExists(schema)).isTrue();
    } finally {
      TenantRlsTestSupport.dropSchemasCreatedByThisTest(ownerDsl(), schema); // 가드가 data 를 거부한다(R7)
      TenantRlsTestSupport.deleteTenants(dsl, tenantId); // 재실행 가능성 — 고정 id 라 지우지 않으면 다음 실행이 중복키로 깨진다
    }
  }

  /** 두 번 불러도 안전하다(멱등). */
  @Test
  void isIdempotent() {
    long tenantId = 900_000_002L;
    TenantRlsTestSupport.insertActiveTenant(dsl, tenantId);
    String schema = TenantContext.runScopedGet(tenantId, DataSchema::current);
    try {
      TenantContext.runScopedGet(
          tenantId,
          () -> {
            provisioner.ensureCurrentTenantSchema();
            provisioner.ensureCurrentTenantSchema(); // 두 번째 호출도 예외 없이 끝나야 한다
            return null;
          });
      assertThat(schemaExists(schema)).isTrue();
    } finally {
      TenantRlsTestSupport.dropSchemasCreatedByThisTest(ownerDsl(), schema);
      TenantRlsTestSupport.deleteTenants(dsl, tenantId);
    }
  }

  /**
   * 권한 세트가 실제로 걸린다 — 이 밴드에서 조용히 실패할 수 있는 유일한 지점이다.
   *
   * <p>왜 pg_default_acl 을 보는가: GRANT ... ON ALL TABLES 는 갓 만든 빈 스키마에서 no-op 이다.
   * 신규 테넌트의 파이프라인이 <b>나중에 만들어질</b> 테이블을 볼 수 있게 하는 것은 오직
   * ALTER DEFAULT PRIVILEGES 항목뿐이다. 이게 빠지면 신규 테넌트만 조용히 파이프라인이 안 돈다.
   *
   * <p>이 테스트가 도는 test DB 는 executor 롤({@code pipeline_executor_t900000003})을 미리
   * 만들어 두지 않는다(신규 테넌트 롤 생성은 운영자 절차, #383) — 그래서 GRANT/ALTER DEFAULT
   * PRIVILEGES 문장 자체는 롤이 없으면 건너뛴다. 이 테스트는 스키마·런타임 롤(app_tenant) 권한만
   * 검증하고, executor 롤 대상 권한(defaultAclExists)은 롤을 직접 만들어 검증한다.
   */
  @Test
  void grantsRequiredPrivileges() {
    long tenantId = 900_000_003L;
    TenantRlsTestSupport.insertActiveTenant(dsl, tenantId);
    String schema = TenantContext.runScopedGet(tenantId, DataSchema::current);
    String executorRole = TenantPipelineRole.roleName(tenantId);
    boolean createdRole = false;
    try {
      createdRole = ensureExecutorRoleExists(executorRole);
      TenantContext.runScopedGet(
          tenantId,
          () -> {
            provisioner.ensureCurrentTenantSchema();
            return null;
          });
      // 런타임 롤이 이 스키마 안에 데이터셋 테이블을 만드는 주체다.
      assertThat(hasSchemaPrivilege("app_tenant", schema, "CREATE")).isTrue();
      assertThat(hasSchemaPrivilege("app_tenant", schema, "USAGE")).isTrue();
      // app_tenant 가 만들 미래 테이블에 대한 기본 권한 항목이 존재하는가.
      assertThat(defaultAclExists(schema, executorRole)).isTrue();
    } finally {
      TenantRlsTestSupport.dropSchemasCreatedByThisTest(ownerDsl(), schema);
      if (createdRole) {
        ownerDsl().execute("DROP ROLE IF EXISTS " + executorRole);
      }
      TenantRlsTestSupport.deleteTenants(dsl, tenantId);
    }
  }

  /** 테넌트 컨텍스트가 없으면 조용히 기본 스키마로 떨어지지 않고 터진다. */
  @Test
  void failsWithoutTenantContext() {
    TenantContext.clear();
    assertThatThrownBy(() -> provisioner.ensureCurrentTenantSchema())
        .isInstanceOf(MissingTenantScopeException.class);
  }

  /** 삭제 헬퍼는 data 를 절대 지우지 않는다 (R7 가드의 비공허성). */
  @Test
  void dropHelperRefusesNonTenantSchemas() {
    DSLContext owner = ownerDsl();
    assertThatThrownBy(() -> TenantRlsTestSupport.dropSchemasCreatedByThisTest(owner, "data"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TenantRlsTestSupport.dropSchemasCreatedByThisTest(owner, "public"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ── 검증 전용 헬퍼 ──────────────────────────────────────────────────────

  private boolean schemaExists(String schema) {
    return dsl.fetchExists(
        dsl.selectOne().from("pg_namespace").where(field("nspname", String.class).eq(schema)));
  }

  private boolean hasSchemaPrivilege(String role, String schema, String privilege) {
    Object result = dsl.fetchValue("select has_schema_privilege(?, ?, ?)", role, schema, privilege);
    return Boolean.TRUE.equals(result);
  }

  /** {@code pg_default_acl} 을 {@code pg_namespace} 와 조인해, 해당 스키마·역할의 기본 권한 항목이 있는지 본다. */
  private boolean defaultAclExists(String schema, String role) {
    Object result =
        dsl.fetchValue(
            "select exists ("
                + "  select 1 from pg_default_acl da"
                + "  join pg_namespace n on n.oid = da.defaclnamespace"
                + "  where n.nspname = ?"
                + "    and exists ("
                + "      select 1 from unnest(da.defaclacl) as acl(item)"
                + "      where item::text like ?"
                + "    )"
                + ")",
            schema,
            role + "=%");
    return Boolean.TRUE.equals(result);
  }

  /**
   * 신규 테넌트 롤은 운영자 절차(#383)로 만들어지므로 test DB 에 미리 없을 수 있다. 이 테스트는
   * ALTER DEFAULT PRIVILEGES 가 executor 롤을 실제로 지정하는지를 봐야 하므로, 없으면 최소 권한
   * 롤을 직접 만들고 테스트가 끝나면 지운다.
   *
   * @return 이 메서드가 롤을 새로 만들었으면 true(호출자가 정리해야 함)
   */
  private boolean ensureExecutorRoleExists(String roleName) {
    boolean exists =
        dsl.fetchExists(
            dsl.selectOne().from("pg_roles").where(field("rolname", String.class).eq(roleName)));
    if (exists) {
      return false;
    }
    ownerDsl().execute("CREATE ROLE " + roleName + " NOLOGIN");
    return true;
  }
}
