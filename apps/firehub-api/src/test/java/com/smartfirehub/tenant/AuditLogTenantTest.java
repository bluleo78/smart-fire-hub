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
 * audit_log 는 형태 (b) 정책이다 — NULL 테넌트를 허용한다.
 *
 * <p>로그인·회원가입 감사는 테넌트 선택 전에 기록되므로 tenant_id 가 NULL 이다. 표준 정책이면
 * WITH CHECK 가 NULL INSERT 를 거부해 로그인이 깨지고, 이어서 INSERT ... RETURNING 이 반환 행에
 * USING 정책까지 적용해 또 깨진다. IS NOT DISTINCT FROM 이 두 문제를 함께 해소한다.
 *
 * <p>대가는 대칭적이다 — NULL 테넌트 행은 어떤 테넌트 컨텍스트에서도 보이지 않는다. 그것이 의도된
 * 동작이므로 여기서 함께 고정한다.
 */
class AuditLogTenantTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private TransactionTemplate tx;

  private long tenantA;
  private Long userId;

  @BeforeEach
  void setUp() {
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "audit");
    userId = TenantRlsTestSupport.insertUser(dsl, "audit");
  }

  @AfterEach
  void tearDown() {
    // 이 테스트가 만드는 행은 NULL 테넌트와 tenantA 두 종류다. 정책상 한 컨텍스트에서 둘 다
    // 보이지 않으므로 두 번 나눠 지운다(테스트 커넥션도 비특권 롤이라 정책 대상이다).
    TenantRlsTestSupport.runInTenantTransaction(
        tx, null, () -> dsl.execute("delete from audit_log where user_id = ?", userId));
    TenantRlsTestSupport.runInTenantTransaction(
        tx, tenantA, () -> dsl.execute("delete from audit_log where user_id = ?", userId));
    TenantRlsTestSupport.deleteUser(dsl, userId);
    TenantRlsTestSupport.deleteTenants(dsl, tenantA);
  }

  /** audit_log 의 NOT NULL 컬럼은 username·action_type·resource 다(action_time·result 는 DEFAULT). */
  private Long insertAuditRow(Long tenantId) {
    return TenantRlsTestSupport.runInTenantTransaction(
        tx,
        tenantId,
        () ->
            (Long)
                dsl.fetchValue(
                    "insert into audit_log (user_id, username, action_type, resource)"
                        + " values (?, 'audit-tester', 'LOGIN', 'auth') returning id",
                    userId));
  }

  @Test
  @DisplayName("컨텍스트가 없으면 NULL 테넌트로 INSERT ... RETURNING 이 통과한다")
  void nullTenantInsertReturningPasses() {
    // 표준 정책이었다면 WITH CHECK 또는 RETURNING 의 USING 평가에서 터진다.
    Long id = insertAuditRow(null);

    assertThat(id).isNotNull();
  }

  @Test
  @DisplayName("테넌트 컨텍스트에서는 NULL 테넌트 행이 보이지 않는다")
  void nullTenantRowsHiddenInsideTenant() {
    Long id = insertAuditRow(null);

    Boolean visible =
        TenantRlsTestSupport.runInTenantTransaction(
            tx, tenantA, () -> TenantRlsTestSupport.rowExists(dsl, "audit_log", "id", id));

    assertThat(visible).as("NULL 테넌트 감사 행이 테넌트 컨텍스트에서 보이면 격리 실패다").isFalse();
  }

  @Test
  @DisplayName("테넌트 컨텍스트에서 쓴 감사 행은 그 테넌트에서만 보인다")
  void tenantScopedRowIsIsolated() {
    Long id = insertAuditRow(tenantA);

    Boolean visibleToOwner =
        TenantRlsTestSupport.runInTenantTransaction(
            tx, tenantA, () -> TenantRlsTestSupport.rowExists(dsl, "audit_log", "id", id));
    Boolean visibleWithoutContext =
        TenantRlsTestSupport.runInTenantTransaction(
            tx, null, () -> TenantRlsTestSupport.rowExists(dsl, "audit_log", "id", id));

    assertThat(visibleToOwner).as("소유 테넌트에서 자기 감사 행이 보여야 한다").isTrue();
    assertThat(visibleWithoutContext).as("컨텍스트 없이 테넌트 감사 행이 보이면 fail-open 이다").isFalse();
  }
}
