package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.config.TenantPipelineDataSourceRegistry;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantPipelineRole;
import com.smartfirehub.global.tenant.TenantPipelineRoleProvisioner;
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
 * {@link TenantPipelineRoleProvisioner} 가 런북 §1-3 · §1-5 절차와 <b>같은 결과</b>를 내는지
 * 실측한다(#680).
 *
 * <p>2026-09-17 장애는 이 절차를 사람이 실행하지 않아서 났다. 그래서 검증도 "메서드가 불렸다"가
 * 아니라 <b>운영자가 런북 §1-8 에서 눈으로 확인하던 것</b>을 그대로 본다 — 롤이 실제로 로그인
 * 가능한가, {@code search_path} 가 DB 한정으로 들어갔는가.
 *
 * <p><b>{@code ensureRole} 을 직접 부른다</b>({@code ensureRoleIfAutoProvisionEnabled} 가 아니라).
 * test 프로필은 자동 프로비저닝이 꺼져 있고(공유 test DB 에 LOGIN 롤이 누적되는 것을 막기 위해),
 * 그 스위치가 기계장치까지 끄지 않는다는 것이 프로비저너의 설계다. 스위치 자체의 동작은
 * {@link #ensureRoleIfAutoProvisionEnabled_isNoOp_whenDisabledByProfile()} 이 본다.
 */
class TenantPipelineRoleProvisionerTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;

  @Autowired private TenantPipelineRoleProvisioner provisioner;

  @Autowired private TenantPipelineDataSourceRegistry tenantPipelineDataSources;

  @Autowired
  @Qualifier("schemaOwnerDataSource")
  private DataSource schemaOwnerDataSource;

  private static final long TENANT_BASE = TenantRlsTestSupport.randomSchemaProvisioningTenantIdBase();

  private DSLContext ownerDsl() {
    return DSL.using(schemaOwnerDataSource, SQLDialect.POSTGRES);
  }

  /**
   * 롤이 없던 테넌트가 <b>접속 가능한 상태</b>로 태어난다 — 테넌트 2에 없었던 바로 그 상태.
   *
   * <p>존재 여부({@code pg_roles})만 보지 않는다. {@code GRANT CONNECT} 가 빠지면 롤은 있는데
   * 로그인만 안 되는 조용한 중간 상태가 되고, 그건 이 클래스가 없애려는 실패 모드와 같은 종류다.
   * 그래서 프로덕션이 실제로 접속에 쓰는 경로({@code TenantPipelineDataSourceRegistry} =
   * 파생 비밀번호 + 롤 이름)로 붙어 {@code current_user} 를 되읽는다.
   */
  @Test
  void ensureRole_makesTenantRoleLoginableWithDerivedPassword() {
    long tenantId = TENANT_BASE + 11;
    TenantRlsTestSupport.insertActiveTenant(dsl, tenantId);
    String roleName = TenantPipelineRole.roleName(tenantId);

    try {
      provisioner.ensureRole(tenantId);

      String currentUser =
          tenantPipelineDataSources.withTenantDsl(
              tenantId,
              tenantDsl -> tenantDsl.fetch("select current_user").get(0).get(0, String.class));

      assertThat(currentUser)
          .as("파생 비밀번호로 테넌트 롤 접속이 실제로 성립해야 한다")
          .isEqualTo(roleName);
    } finally {
      cleanUp(tenantId, roleName);
    }
  }

  /**
   * {@code search_path} 가 <b>DB 한정</b>으로 들어갔는지 본다 — 런북 §1-5 가 가장 강하게 경고하는
   * 실수다. {@code IN DATABASE} 를 빠뜨리면 {@code pg_db_role_setting.setdatabase} 가 0 이 되어
   * 이 클러스터의 <b>모든 DB</b> 에 같은 설정이 적용된다(레거시 {@code pipeline_executor} 가 실제로
   * 그 상태다).
   *
   * <p>{@code setdatabase} 는 {@code pg_database.oid} 라 DB 마다 값이 다르다 — 0 이 아닌지만 본다
   * (런북 §1-5 의 운영자 확인 쿼리와 같은 판정).
   */
  @Test
  void ensureRole_scopesSearchPathToThisDatabaseOnly() {
    long tenantId = TENANT_BASE + 12;
    TenantRlsTestSupport.insertActiveTenant(dsl, tenantId);
    String roleName = TenantPipelineRole.roleName(tenantId);
    String schema = DataSchema.forTenant(tenantId);

    try {
      provisioner.ensureRole(tenantId);

      var setting =
          ownerDsl()
              .fetch(
                  "select drs.setdatabase, drs.setconfig from pg_db_role_setting drs"
                      + " join pg_roles r on r.oid = drs.setrole where r.rolname = ?",
                  roleName);

      assertThat(setting).as("search_path 설정 자체가 있어야 한다").hasSize(1);
      assertThat(setting.get(0).get(0, Long.class))
          .as("setdatabase=0 이면 클러스터의 모든 DB 에 적용된다 — IN DATABASE 를 빠뜨린 것이다")
          .isNotZero();
      assertThat(setting.get(0).get(1, String[].class)).containsExactly("search_path=" + schema);
    } finally {
      cleanUp(tenantId, roleName);
    }
  }

  /**
   * 두 번 불러도 안전해야 한다 — 기동 치유({@code TenantPipelineRoleBootstrap})가 매 기동마다
   * 같은 테넌트에 대해 이 메서드를 부르고, 테넌트 생성 실패 후 재시도 경로도 같은 호출을 반복한다.
   * 멱등하지 않으면 두 번째 기동부터 예외가 나고, 그 예외를 잡아 삼키는 순간 이번 장애와 같은
   * 무성 실패가 다시 생긴다.
   */
  @Test
  void ensureRole_isIdempotent() {
    long tenantId = TENANT_BASE + 13;
    TenantRlsTestSupport.insertActiveTenant(dsl, tenantId);
    String roleName = TenantPipelineRole.roleName(tenantId);

    try {
      provisioner.ensureRole(tenantId);
      provisioner.ensureRole(tenantId);

      String currentUser =
          tenantPipelineDataSources.withTenantDsl(
              tenantId,
              tenantDsl -> tenantDsl.fetch("select current_user").get(0).get(0, String.class));
      assertThat(currentUser).isEqualTo(roleName);
    } finally {
      cleanUp(tenantId, roleName);
    }
  }

  /**
   * {@code test} 프로필은 자동 프로비저닝이 꺼져 있다 — 그 상태에서 자동 호출부가 실제로 아무
   * 롤도 만들지 않는지 확인한다.
   *
   * <p>이 단언이 없으면 플래그가 무시되는 회귀를 아무도 못 잡는다. 그 회귀의 피해는 조용하다:
   * 공유 test DB 의 수백 개 테넌트에 대해 기동 치유가 LOGIN 롤을 만들어 클러스터에 영구히 남긴다
   * ({@code pg_roles} 는 DB 가 아니라 클러스터 전역이다).
   */
  @Test
  void ensureRoleIfAutoProvisionEnabled_isNoOp_whenDisabledByProfile() {
    long tenantId = TENANT_BASE + 14;
    TenantRlsTestSupport.insertActiveTenant(dsl, tenantId);
    String roleName = TenantPipelineRole.roleName(tenantId);

    try {
      provisioner.ensureRoleIfAutoProvisionEnabled(tenantId);

      assertThat(roleExists(roleName))
          .as("test 프로필에서는 자동 호출부가 롤을 만들지 않아야 한다")
          .isFalse();
    } finally {
      cleanUp(tenantId, roleName);
    }
  }

  private boolean roleExists(String roleName) {
    return TenantRlsTestSupport.roleExists(ownerDsl(), roleName);
  }

  /**
   * 무작위 테넌트 id 로 <b>이 테스트가 만든</b> 롤과 테넌트 행을 지운다.
   *
   * <p>풀도 함께 닫는다 — 위 테스트들이 {@code TenantPipelineDataSourceRegistry} 로 실제 접속을
   * 하므로 테넌트마다 Hikari 풀이 하나씩 남는다. test 프로필의 상한은 {@code max-pools: 3} 이라,
   * 닫지 않으면 이 클래스만으로 상한을 채워 {@code TenantPipelineDataSourceRegistryTest} 의 풀을
   * 중간에 축출하고, 이미 드롭된 롤을 향한 커넥션을 {@code idle-timeout} 10분 동안 붙들고 있는다.
   */
  private void cleanUp(long tenantId, String roleName) {
    TenantRlsTestSupport.cleanupAll(
        tenantPipelineDataSources::closeAllPools,
        () -> TenantRlsTestSupport.dropPipelineLoginRole(ownerDsl(), roleName),
        () -> TenantRlsTestSupport.deleteTenants(dsl, tenantId));
  }
}
