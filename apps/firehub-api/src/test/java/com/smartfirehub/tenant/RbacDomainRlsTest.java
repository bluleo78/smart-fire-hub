package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V99 의 role / role_permission / user_role 테넌트 격리를 양방향으로 검증한다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 붙이지 않는다 — 테넌트를 바꿔 가며 여러 트랜잭션을
 * 열어야 하고, 하나의 테스트 트랜잭션에 묶이면 GUC 가 처음 값으로 고정된다.
 *
 * <p>테스트 커넥션은 비특권 롤 {@code app_tenant}(NOBYPASSRLS, V83)로 접속한다 — 즉 픽스처·정리·
 * 검증 조회도 전부 정책의 대상이다. 그래서 정리까지 테넌트 컨텍스트 트랜잭션 안에서 한다.
 */
class RbacDomainRlsTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private TransactionTemplate tx;

  private long tenantA;
  private long tenantB;
  private Long userId;
  private Long permissionId;

  @BeforeEach
  void setUp() {
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "rbac-a");
    tenantB = TenantRlsTestSupport.createActiveTenant(dsl, "rbac-b");
    // "user" 와 permission 은 테넌트 경계 위의 전역 테이블(RLS 없음)이라 컨텍스트 없이 만진다.
    userId = TenantRlsTestSupport.insertUser(dsl, "rbac");
    permissionId = (Long) dsl.fetchValue("select id from permission order by id limit 1");
  }

  @AfterEach
  void tearDown() {
    // RLS 스코프 안에서 지우므로 자기 테넌트 행만 지워진다(남의 행은 애초에 보이지 않는다).
    // 자식(role_permission·user_role)부터 지워야 role FK 에 걸리지 않는다.
    deleteOwnRows(tenantA);
    deleteOwnRows(tenantB);
    TenantRlsTestSupport.deleteUser(dsl, userId);
    TenantRlsTestSupport.deleteTenants(dsl, tenantA, tenantB);
  }

  private void deleteOwnRows(long tenantId) {
    TenantRlsTestSupport.runInTenantTransaction(
        tx, tenantId, () -> TenantRlsTestSupport.deleteRbacCascade(dsl, tenantId));
  }

  /**
   * 지정 테넌트 컨텍스트에서 역할을 하나 만들고 PK 를 돌려준다.
   *
   * <p>{@code tenant_id} 는 명시하지 않는다 — V97 이 심은 컬럼 DEFAULT 가 GUC 에서 채우는 것을
   * 함께 검증하기 위함이다.
   */
  private Long insertRole(long tenantId, String namePrefix) {
    return TenantRlsTestSupport.runInTenantTransaction(
        tx, tenantId, () -> insertRoleInCurrentContext(namePrefix));
  }

  @Test
  @DisplayName("role 은 테넌트 간 양방향으로 격리된다")
  void roleIsIsolated() {
    TenantRlsTestSupport.assertTwoSidedIsolation(
        tx, dsl, tenantA, tenantB, "role", () -> insertRoleInCurrentContext("격리검증-A"));
  }

  /**
   * {@code assertTwoSidedIsolation} 은 이미 소유 테넌트 트랜잭션 안에서 이 Supplier 를 부른다 —
   * 여기서 또 트랜잭션을 열면 중첩이 되므로 bare 삽입을 쓴다.
   */
  private Long insertRoleInCurrentContext(String namePrefix) {
    long suffix = TenantRlsTestSupport.nextTenantId();
    return (Long)
        dsl.fetchValue(
            "insert into role (name, description, is_system) values (?, ?, false) returning id",
            namePrefix + "-" + suffix,
            "격리 검증용");
  }

  @Test
  @DisplayName("같은 역할 이름을 다른 테넌트가 각각 가질 수 있다")
  void sameRoleNameAllowedAcrossTenants() {
    // 유니크 인덱스는 RLS 와 무관하게 전역 적용된다 — V97 이 (tenant_id, name) 으로 접지 않았다면
    // 여기서 충돌 예외가 난다. 이름을 고정해야 의미가 있으므로 접미사를 붙이지 않는다.
    String sharedName = "동일이름역할-" + TenantRlsTestSupport.nextTenantId();

    Long a =
        TenantRlsTestSupport.runInTenantTransaction(
            tx,
            tenantA,
            () ->
                (Long)
                    dsl.fetchValue(
                        "insert into role (name, is_system) values (?, false) returning id",
                        sharedName));
    Long b =
        TenantRlsTestSupport.runInTenantTransaction(
            tx,
            tenantB,
            () ->
                (Long)
                    dsl.fetchValue(
                        "insert into role (name, is_system) values (?, false) returning id",
                        sharedName));

    assertThat(a).isNotNull();
    assertThat(b).isNotNull().isNotEqualTo(a);
  }

  @Test
  @DisplayName("user_role 은 다른 테넌트에서 보이지 않는다")
  void userRoleIsIsolated() {
    // user_role 의 PK 는 (user_id, role_id) 라 id 컬럼이 없다 — id PK 를 가정하는
    // assertTwoSidedIsolation 을 쓸 수 없어 직접 양방향을 확인한다.
    Long roleId = insertRole(tenantA, "격리검증-사용자역할");
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        tenantA,
        () -> dsl.execute("insert into user_role (user_id, role_id) values (?, ?)", userId, roleId));

    assertThat(countUserRoles(tenantA)).as("소유 테넌트에서는 보여야 한다").isEqualTo(1);
    assertThat(countUserRoles(tenantB)).as("다른 테넌트에서 보이면 격리 실패다").isZero();
  }

  private Integer countUserRoles(long tenantId) {
    return TenantRlsTestSupport.runInTenantTransaction(
        tx,
        tenantId,
        () ->
            (Integer)
                dsl.fetchValue("select count(*)::int from user_role where user_id = ?", userId));
  }

  @Test
  @DisplayName("role_permission 은 다른 테넌트에서 보이지 않는다")
  void rolePermissionIsIsolated() {
    // PK 가 (role_id, permission_id) 인 매핑 테이블이라 여기도 직접 확인한다.
    // permission 은 전역 카탈로그라 id 를 그대로 재사용한다.
    Long roleId = insertRole(tenantA, "격리검증-권한매핑");
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        tenantA,
        () ->
            dsl.execute(
                "insert into role_permission (role_id, permission_id) values (?, ?)",
                roleId,
                permissionId));

    assertThat(countRolePermissions(tenantA, roleId)).as("소유 테넌트에서는 보여야 한다").isEqualTo(1);
    assertThat(countRolePermissions(tenantB, roleId)).as("다른 테넌트에서 보이면 격리 실패다").isZero();
  }

  private Integer countRolePermissions(long tenantId, Long roleId) {
    return TenantRlsTestSupport.runInTenantTransaction(
        tx,
        tenantId,
        () ->
            (Integer)
                dsl.fetchValue(
                    "select count(*)::int from role_permission where role_id = ?", roleId));
  }

  @Test
  @DisplayName("컨텍스트가 없으면 자기 role 도 보이지 않는다 (fail-closed)")
  void failsClosedWithoutContext() {
    Long roleId = insertRole(tenantA, "격리검증-무컨텍스트");

    // 공유 테스트 DB 라 전체 카운트로는 단언할 수 없다(다른 세션이 동시에 쓴다). 방금 만든 행
    // 하나가 보이는지로 확인한다. tenantId=null 은 "컨텍스트가 빈 상태" 재현이다.
    Boolean visible =
        TenantRlsTestSupport.runInTenantTransaction(
            tx, null, () -> TenantRlsTestSupport.rowExists(dsl, "role", "id", roleId));

    assertThat(visible).as("테넌트 컨텍스트 없이 role 이 보이면 fail-open 이다").isFalse();
  }

  @Test
  @DisplayName("다른 테넌트 id 로 role 을 심으려 하면 WITH CHECK 가 거부한다")
  void insertWithMismatchedTenantIsRejected() {
    Throwable thrown =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                TenantRlsTestSupport.runInTenantTransaction(
                    tx,
                    tenantA,
                    () ->
                        dsl.execute(
                            "insert into role (name, is_system, tenant_id) values (?, false, ?)",
                            "교차삽입시도-" + TenantRlsTestSupport.nextTenantId(),
                            tenantB)));

    assertThat(thrown)
        .as("다른 테넌트 id 로 INSERT 가 통과하면 WITH CHECK 가 없는 것이다")
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }
}
