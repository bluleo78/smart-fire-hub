package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.auth.dto.LoginRequest;
import com.smartfirehub.auth.service.AuthService;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 로그인 성공 감사 행이 <b>활성 테넌트 스코프로</b> 기록되는지 고정한다 (코드리뷰 MEDIUM-3).
 *
 * <p>왜 필요한가: {@code /auth/login} 은 permitAll 이라 JWT 도 {@link TenantContext} 도 없다. 그대로
 * 두면 {@code audit_log.tenant_id} 가 NULL 이 되고, V99 의 형태 (b) USING 정책이 그 행을 모든 테넌트
 * 스코프 조회에서 감춘다 — 감사 화면과 대시보드에 LOGOUT(JWT 아래에서 기록되어 테넌트가 붙는다)만
 * 보이고 짝이 되는 LOGIN 이 사라진다.
 *
 * <p>{@code AuditLogTenantTest} 는 "테넌트가 진짜 없는 행은 안 보인다" 는 정책 자체를 고정한다.
 * 그 동작은 그대로 두고, 여기서는 "로그인은 테넌트가 있는 행을 남겨야 한다" 를 고정한다.
 *
 * <p><b>클래스 레벨 {@code @Transactional} 이 없는 것은 의도된 것이다.</b> 붙이면 테스트 트랜잭션이
 * GUC 를 공급해 운영에 없는 조건이 만들어지고, 검증 대상인 결함이 통째로 가려진다. 같은 이유로
 * {@code login} 호출 직전에 {@link TenantContext#clear()} 를 부른다 —
 * {@code IntegrationTestBase} 가 세워 두는 기본 테넌트 1 이 남아 있으면 로그인 트랜잭션이 그 GUC 로
 * 열려 결함이 드러나지 않는다.
 */
class LoginAuditTenantTest extends IntegrationTestBase {

  private static final String PASSWORD = "login-audit-pw";

  @Autowired private DSLContext dsl;
  @Autowired private TransactionTemplate tx;
  @Autowired private AuthService authService;
  @Autowired private PasswordEncoder passwordEncoder;

  private long tenantA;
  private Long userId;
  private String username;

  @BeforeEach
  void setUp() {
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "login-audit");
    userId = TenantRlsTestSupport.insertUser(dsl, "loginaudit");
    username = (String) dsl.fetchValue("select username from \"user\" where id = ?", userId);
    // insertUser 는 평문 "pw" 를 넣는다 — 로그인하려면 인코딩된 비밀번호로 바꿔야 한다.
    dsl.execute(
        "update \"user\" set password = ? where id = ?", passwordEncoder.encode(PASSWORD), userId);
    // membership 은 전역(RLS 미적용) 테이블. 정확히 하나여야 로그인이 테넌트를 자동 선택한다.
    dsl.execute(
        "insert into membership (user_id, tenant_id, role, status)"
            + " values (?, ?, 'MEMBER', 'ACTIVE')",
        userId,
        tenantA);
  }

  @AfterEach
  void tearDown() {
    // 이 테스트가 남길 수 있는 감사 행은 NULL 테넌트와 tenantA 두 종류다. 정책상 한 컨텍스트에서
    // 둘 다 보이지 않으므로 두 번 나눠 지운다(테스트 커넥션도 비특권 롤이라 정책 대상이다).
    TenantRlsTestSupport.runInTenantTransaction(
        tx, null, () -> dsl.execute("delete from audit_log where user_id = ?", userId));
    TenantRlsTestSupport.runInTenantTransaction(
        tx, tenantA, () -> dsl.execute("delete from audit_log where user_id = ?", userId));
    dsl.execute("delete from refresh_token where user_id = ?", userId);
    dsl.execute("delete from membership where user_id = ?", userId);
    dsl.execute("delete from login_attempts where username = ?", username);
    TenantRlsTestSupport.deleteUser(dsl, userId);
    TenantRlsTestSupport.deleteTenants(dsl, tenantA);
    TenantContext.clear();
  }

  @Test
  @DisplayName("로그인 성공 감사 행은 선택된 테넌트 스코프에서 보인다")
  void loginAuditRowIsVisibleInSelectedTenant() {
    // 운영의 permitAll 로그인 재현: 컨텍스트도 앰비언트 트랜잭션도 없다.
    TenantContext.clear();

    authService.login(new LoginRequest(username, PASSWORD));

    Integer visibleInTenant =
        TenantRlsTestSupport.runInTenantTransaction(
            tx,
            tenantA,
            () ->
                (Integer)
                    dsl.fetchValue(
                        "select count(*)::int from audit_log"
                            + " where user_id = ? and action_type = 'LOGIN'",
                        userId));

    assertThat(visibleInTenant)
        .as(
            "LOGIN 감사 행이 테넌트 스코프에서 안 보이면, 감사 화면·대시보드에 LOGOUT 만 남고"
                + " 짝이 되는 LOGIN 이 사라진다")
        .isEqualTo(1);
  }

  @Test
  @DisplayName("로그인 성공 감사 행은 NULL 테넌트로 남지 않는다")
  void loginAuditRowIsNotNullTenant() {
    TenantContext.clear();

    authService.login(new LoginRequest(username, PASSWORD));

    // tenantId=null 은 "컨텍스트가 빈 상태" 재현이다. 형태 (b) 정책이라 이 조회에는 NULL 테넌트
    // 행만 매칭된다 — 즉 여기서 1이 나오면 감사 행이 여전히 NULL 로 기록됐다는 뜻이다.
    Integer visibleWithoutContext =
        TenantRlsTestSupport.runInTenantTransaction(
            tx,
            null,
            () ->
                (Integer)
                    dsl.fetchValue(
                        "select count(*)::int from audit_log"
                            + " where user_id = ? and action_type = 'LOGIN'",
                        userId));

    assertThat(visibleWithoutContext).as("NULL 테넌트로 기록되면 어떤 테넌트에서도 보이지 않는다").isZero();
  }
}
