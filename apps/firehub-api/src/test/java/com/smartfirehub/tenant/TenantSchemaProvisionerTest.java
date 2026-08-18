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
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * {@link TenantSchemaProvisioner} 의 지연 생성·멱등성·권한 부여·자가치유를 검증한다.
 *
 * <p><b>테넌트 id 는 프로세스마다 무작위 기저(라운드 1 리뷰 R13) + 클래스 내 고정 오프셋을
 * 쓴다.</b> 고정 리터럴을 썼던 초판은 동시에 도는 다른 워크트리와 같은 스키마·롤 이름을 노려
 * 교차 실패가 재현됐다({@link TenantRlsTestSupport#randomSchemaProvisioningTenantIdBase()}
 * 참조). 이 대역이라야 {@code data_t{id}} 가 파생되어 삭제 가드
 * ({@link TenantRlsTestSupport#dropSchemasCreatedByThisTest})를 통과한다. 테넌트 1 로
 * 프로비저너를 시험하면 {@code data} 자체를 건드리게 되므로 절대 쓰지 않는다.
 */
class TenantSchemaProvisionerTest extends IntegrationTestBase {

  /** 이 클래스의 모든 테스트가 공유하는 무작위 기저. 테스트마다 +1, +2, ... 오프셋만 더한다. */
  private static final long TENANT_BASE =
      TenantRlsTestSupport.randomSchemaProvisioningTenantIdBase();

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
    long tenantId = TENANT_BASE + 1;
    TenantRlsTestSupport.insertActiveTenant(dsl, tenantId);
    String schema = TenantContext.runScopedGet(tenantId, DataSchema::current); // → "data_t{id}"
    try {
      TenantContext.runScopedGet(
          tenantId,
          () -> {
            provisioner.ensureCurrentTenantSchema();
            return null;
          });
      assertThat(schemaExists(schema)).isTrue();
    } finally {
      cleanupAll(
          () -> TenantRlsTestSupport.dropSchemasCreatedByThisTest(ownerDsl(), schema),
          () -> TenantRlsTestSupport.deleteTenants(dsl, tenantId));
    }
  }

  /** 두 번 불러도 안전하다(멱등). */
  @Test
  void isIdempotent() {
    long tenantId = TENANT_BASE + 2;
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
      cleanupAll(
          () -> TenantRlsTestSupport.dropSchemasCreatedByThisTest(ownerDsl(), schema),
          () -> TenantRlsTestSupport.deleteTenants(dsl, tenantId));
    }
  }

  /**
   * 권한 세트가 실제로 걸린다 — 이 밴드에서 조용히 실패할 수 있는 유일한 지점이다.
   *
   * <p>왜 pg_default_acl 을 보는가: GRANT ... ON ALL TABLES 는 갓 만든 빈 스키마에서 no-op 이다.
   * 신규 테넌트의 파이프라인이 <b>나중에 만들어질</b> 테이블을 볼 수 있게 하는 것은 오직
   * ALTER DEFAULT PRIVILEGES 항목뿐이다. 이게 빠지면 신규 테넌트만 조용히 파이프라인이 안 돈다.
   *
   * <p><b>왜 {@code defaultAclExists} 가 {@code defaclrole}(FOR ROLE)과 {@code defaclobjtype}
   * 을 함께 확인하는가(라운드 1 리뷰 BLOCKER 2).</b> 스키마 하나만으로 조인하고 grantee 만 보면
   * {@code FOR ROLE app_tenant} → {@code FOR ROLE app} 변이가 초록으로 통과한다(실증: 두 값
   * 모두 같은 스키마에 같은 grantee 로 항목을 남기므로 스키마·grantee 만 보는 조회는 구별하지
   * 못한다). 그 변이의 실제 결과는 "신규 테이블이 executor 롤에게 조용히 안 보임" — 이 테스트가
   * 막으려던 바로 그 실패이므로, {@code defaclrole='app_tenant'} 와
   * {@code defaclobjtype in ('r','S')} 를 각각 확인해야 그 변이가 실제로 잡힌다.
   *
   * <p>이 테스트가 도는 test DB 는 executor 롤(랜덤 오프셋 기반)을 미리 만들어 두지 않는다
   * (신규 테넌트 롤 생성은 운영자 절차, #383) — 그래서 GRANT/ALTER DEFAULT PRIVILEGES 문장
   * 자체는 롤이 없으면 건너뛴다. 이 테스트는 스키마·런타임 롤(app_tenant) 권한만 검증하고,
   * executor 롤 대상 권한(defaultAclExists)은 롤을 직접 만들어 검증한다. 롤이 <b>나중에</b>
   * 생기는 경우의 자가치유는 {@link #selfHealsWhenExecutorRoleAppearsLater()} 가 별도로 다룬다.
   */
  @Test
  void grantsRequiredPrivileges() {
    long tenantId = TENANT_BASE + 3;
    TenantRlsTestSupport.insertActiveTenant(dsl, tenantId);
    String schema = TenantContext.runScopedGet(tenantId, DataSchema::current);
    String executorRole = TenantPipelineRole.roleName(tenantId);
    AtomicBoolean createdRole = new AtomicBoolean(false);
    try {
      createdRole.set(ensureExecutorRoleExists(executorRole));
      TenantContext.runScopedGet(
          tenantId,
          () -> {
            provisioner.ensureCurrentTenantSchema();
            return null;
          });
      // 런타임 롤이 이 스키마 안에 데이터셋 테이블을 만드는 주체다.
      assertThat(hasSchemaPrivilege("app_tenant", schema, "CREATE")).isTrue();
      assertThat(hasSchemaPrivilege("app_tenant", schema, "USAGE")).isTrue();
      // app_tenant 가 만들 미래 테이블·시퀀스 각각에 대한 기본 권한 항목이 존재하는가.
      assertThat(defaultAclExists(schema, "app_tenant", executorRole, "r")).isTrue();
      assertThat(defaultAclExists(schema, "app_tenant", executorRole, "S")).isTrue();
    } finally {
      cleanupAll(
          () -> TenantRlsTestSupport.dropSchemasCreatedByThisTest(ownerDsl(), schema),
          () -> {
            if (createdRole.get()) {
              ownerDsl().execute("DROP ROLE IF EXISTS " + executorRole);
            }
          },
          () -> TenantRlsTestSupport.deleteTenants(dsl, tenantId));
    }
  }

  /**
   * 자가치유(라운드 1 리뷰 BLOCKER 1) — executor 롤이 스키마 생성 <b>이후</b>에 생겨도, 다음
   * 프로비저닝 호출이 그 스키마에 다시 들어가 권한을 마저 건다.
   *
   * <p>왜 필요한가: 신규 테넌트의 롤 생성은 운영자 절차(#383)라 첫 데이터셋 생성 시점에는 아직
   * 없는 경우가 흔하다. 단순히 "스키마가 있으면 끝"으로 단락시키면, 롤이 나중에 생겨도 이
   * 메서드가 다시 들어오지 않아 그 테넌트의 파이프라인이 에러 없이 영구히 안 돈다 — 자가치유
   * 경로가 없으면 원인 추적조차 안 되는 조용한 실패다.
   */
  @Test
  void selfHealsWhenExecutorRoleAppearsLater() {
    long tenantId = TENANT_BASE + 4;
    TenantRlsTestSupport.insertActiveTenant(dsl, tenantId);
    String schema = TenantContext.runScopedGet(tenantId, DataSchema::current);
    String executorRole = TenantPipelineRole.roleName(tenantId);
    try {
      // 1차 — 롤이 아직 없는 상태에서 프로비저닝한다. 스키마만 생기고 executor 대상 grant 는
      // 전부 건너뛴다(정상 동작 — 위 grantsRequiredPrivileges 의 전제와 같다).
      TenantContext.runScopedGet(
          tenantId,
          () -> {
            provisioner.ensureCurrentTenantSchema();
            return null;
          });
      assertThat(schemaExists(schema)).isTrue();
      assertThat(roleExistsInDb(executorRole)).as("아직 롤을 만들지 않았다").isFalse();

      // 운영자가 뒤늦게 롤을 만든다(#383 절차의 재현).
      ownerDsl().execute("CREATE ROLE " + executorRole + " NOLOGIN");

      // 2차 — 스키마는 이미 있지만, 롤이 이 스키마에 아직 USAGE 가 없으므로 단락 조건(R12)이
      // 거짓이 되어 grant 블록이 다시 실행돼야 한다.
      TenantContext.runScopedGet(
          tenantId,
          () -> {
            provisioner.ensureCurrentTenantSchema();
            return null;
          });

      assertThat(hasSchemaPrivilege(executorRole, schema, "USAGE"))
          .as("자가치유로 뒤늦게 USAGE 가 걸려야 한다")
          .isTrue();
      assertThat(defaultAclExists(schema, "app_tenant", executorRole, "r")).isTrue();
      assertThat(defaultAclExists(schema, "app_tenant", executorRole, "S")).isTrue();
    } finally {
      cleanupAll(
          () -> TenantRlsTestSupport.dropSchemasCreatedByThisTest(ownerDsl(), schema),
          () -> ownerDsl().execute("DROP ROLE IF EXISTS " + executorRole),
          () -> TenantRlsTestSupport.deleteTenants(dsl, tenantId));
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

  private boolean roleExistsInDb(String roleName) {
    return dsl.fetchExists(
        dsl.selectOne().from("pg_roles").where(field("rolname", String.class).eq(roleName)));
  }

  private boolean hasSchemaPrivilege(String role, String schema, String privilege) {
    Object result = dsl.fetchValue("select has_schema_privilege(?, ?, ?)", role, schema, privilege);
    return Boolean.TRUE.equals(result);
  }

  /**
   * {@code pg_default_acl} 을 {@code pg_namespace}·{@code pg_roles} 와 조인해, 지정한
   * {@code forRole}({@code FOR ROLE})이 만든 {@code objType}({@code 'r'}=테이블, {@code 'S'}=
   * 시퀀스) 기본 권한 중 {@code granteeRole} 에게 준 항목이 있는지 본다.
   *
   * <p>스키마만으로 조인하고 grantee 만 보는 버전은 {@code defaclrole} 을 구별하지 못해
   * {@code FOR ROLE app_tenant} → {@code FOR ROLE app} 변이를 통과시킨다(라운드 1 리뷰 실증,
   * 클래스 Javadoc 참조) — 그래서 {@code defaclrole}·{@code defaclobjtype} 을 명시 조건으로
   * 추가했다.
   */
  private boolean defaultAclExists(String schema, String forRole, String granteeRole, String objType) {
    Object result =
        dsl.fetchValue(
            "select exists ("
                + "  select 1 from pg_default_acl da"
                + "  join pg_namespace n on n.oid = da.defaclnamespace"
                + "  join pg_roles r on r.oid = da.defaclrole"
                + "  where n.nspname = ?"
                + "    and r.rolname = ?"
                + "    and da.defaclobjtype = ?"
                + "    and exists ("
                + "      select 1 from unnest(da.defaclacl) as acl(item)"
                + "      where item::text like ?"
                + "    )"
                + ")",
            schema,
            forRole,
            objType,
            granteeRole + "=%");
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
    if (roleExistsInDb(roleName)) {
      return false;
    }
    ownerDsl().execute("CREATE ROLE " + roleName + " NOLOGIN");
    return true;
  }

  /**
   * 정리 단계들을 <b>서로 독립적으로</b> 실행한다(라운드 1 리뷰 should-fix 5).
   *
   * <p>순차 {@code finally} 블록에서 한 단계가 던지면 뒤따르는 정리가 전부 스킵된다 — 예를 들어
   * 스키마 드롭이 일시적으로 실패하면 {@code deleteTenants} 가 안 불려 테넌트 행이 고정 id 로
   * 영구히 남고, 다음 실행의 {@code insertActiveTenant} 가 중복 키로 깨져 수동 DB 수술 전까지
   * 복구되지 않는다. 각 단계를 독립적으로 실행해 하나가 실패해도 나머지가 최대한 정리되게 하고,
   * 실패는 모아서 마지막에 하나로 알린다(억제된 예외로 전부 보존).
   */
  private void cleanupAll(Runnable... steps) {
    RuntimeException combined = null;
    for (Runnable step : steps) {
      try {
        step.run();
      } catch (RuntimeException e) {
        if (combined == null) {
          combined = new IllegalStateException("정리 단계 중 일부가 실패했다", e);
        } else {
          combined.addSuppressed(e);
        }
      }
    }
    if (combined != null) {
      throw combined;
    }
  }
}
