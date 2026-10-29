package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.auth.dto.LoginRequest;
import com.smartfirehub.auth.exception.AccountLockedException;
import com.smartfirehub.auth.exception.InvalidCredentialsException;
import com.smartfirehub.auth.service.AuthService;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.user.exception.UserDeactivatedException;
import com.smartfirehub.user.service.UserService;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 로그인 실패·계정 잠금·비밀번호 변경이 감사 로그에 남는지 고정한다(WD-290).
 *
 * <p>핵심은 「401/403 으로 끝난 로그인의 감사 행이 실제로 커밋되는가」다. 로그인 트랜잭션은 실패 예외로 끝나므로, 롤백되면 행이 사라진다. 그래서
 * {@link LoginAuditTenantTest} 와 같이 <b>클래스 레벨 {@code @Transactional} 없이</b> 운영처럼 호출하고, 감사 행은 호출이 끝난 뒤 별도
 * 트랜잭션에서 읽는다 — 테스트 트랜잭션이 행을 붙들어 롤백을 가리는 일이 없다.
 *
 * <p>테넌트 규칙은 로그인 성공과 같다: 활성 멤버십이 하나면 그 테넌트, 0개·2개 이상이거나 없는 아이디면 NULL 테넌트(운영자 콘솔).
 */
class AccountEventAuditTest extends IntegrationTestBase {

  private static final String PASSWORD = "acct-audit-pw1";

  @Autowired private DSLContext dsl;
  @Autowired private TransactionTemplate tx;
  @Autowired private AuthService authService;
  @Autowired private UserService userService;
  @Autowired private PasswordEncoder passwordEncoder;

  private long tenantA;
  private long tenantB;
  private Long userId;
  private String username;
  /** 없는 아이디 시험에 쓴 이름(긴 이름 시험은 이 접두어로 늘인다) — 정리할 때 접두어로 NULL 테넌트 행·카운터를 지운다. */
  private String ghostName;

  @BeforeEach
  void setUp() {
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "acct-audit-a");
    tenantB = TenantRlsTestSupport.createActiveTenant(dsl, "acct-audit-b");
    username = "acctaudit-" + TenantRlsTestSupport.nextTenantId();
    userId =
        TenantRlsTestSupport.insertUserWithPassword(dsl, username, passwordEncoder.encode(PASSWORD));
    TenantRlsTestSupport.insertActiveMembership(dsl, userId, tenantA);
    ghostName = "ghost-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
  }

  @AfterEach
  void tearDown() {
    // 감사 행은 NULL 테넌트·tenantA·tenantB 세 곳에 남을 수 있다 — 정책상 한 컨텍스트에서 하나만 보이므로 나눠 지운다.
    for (Long t : new Long[] {null, tenantA, tenantB}) {
      TenantRlsTestSupport.runInTenantTransaction(
          tx,
          t,
          () ->
              dsl.execute(
                  "delete from audit_log where user_id = ? or username like ? || '%'",
                  userId,
                  ghostName));
    }
    dsl.execute("delete from refresh_token where user_id = ?", userId);
    dsl.execute("delete from membership where user_id = ?", userId);
    dsl.execute(
        "delete from login_attempts where username = ? or username like ? || '%'",
        username,
        ghostName);
    TenantRlsTestSupport.deleteUser(dsl, userId);
    TenantRlsTestSupport.deleteTenants(dsl, tenantA, tenantB);
    TenantContext.clear();
  }

  @Test
  @DisplayName("틀린 비밀번호 로그인은 401 과 함께 LOGIN·FAILURE 행을 그 사용자의 테넌트에 커밋한다")
  void badPasswordIsAuditedInMembersTenant() {
    TenantContext.clear();

    assertThatThrownBy(() -> authService.login(new LoginRequest(username, "wrong-pw1")))
        .isInstanceOf(InvalidCredentialsException.class);

    List<Record> rows = rowsIn(tenantA, "LOGIN");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("result")).isEqualTo("FAILURE");
    assertThat(rows.get(0).get("description")).isEqualTo("로그인 실패 — 비밀번호 불일치");
    assertThat((String) rows.get(0).get("reason")).isEqualTo("BAD_PASSWORD");
  }

  @Test
  @DisplayName("없는 아이디 로그인은 입력한 아이디로 NULL 테넌트(운영자 콘솔)에 남는다")
  void unknownUsernameIsAuditedWithNullTenant() {
    TenantContext.clear();

    assertThatThrownBy(() -> authService.login(new LoginRequest(ghostName, "whatever1")))
        .isInstanceOf(InvalidCredentialsException.class);

    Integer rows =
        valueIn(
            null,
            "select count(*)::int from audit_log where username = ? and user_id is null"
                + " and action_type = 'LOGIN' and result = 'FAILURE'"
                + " and metadata ->> 'reason' = 'UNKNOWN_USER'",
            ghostName);
    assertThat(rows).isEqualTo(1);
  }

  @Test
  @DisplayName("50자를 넘는 없는 아이디도 401 로 끝나고, 감사 행에는 앞 50자만 남는다")
  void overlongUnknownUsernameStillReturns401() {
    TenantContext.clear();
    String longName = ghostName + "x".repeat(60);

    assertThatThrownBy(() -> authService.login(new LoginRequest(longName, "whatever1")))
        .isInstanceOf(InvalidCredentialsException.class);

    String stored =
        valueIn(null, "select username from audit_log where username = ?", longName.substring(0, 50));
    assertThat(stored).hasSize(50);
  }

  @Test
  @DisplayName("X-Forwarded-For 를 길게 보내도 401 로 끝나고, 감사 행 IP 는 45자로 잘린다")
  void overlongForwardedForStillReturns401() {
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.addHeader("X-Forwarded-For", "9".repeat(100) + ", 10.0.0.1");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    try {
      TenantContext.clear();
      assertThatThrownBy(() -> authService.login(new LoginRequest(username, "wrong-pw1")))
          .isInstanceOf(InvalidCredentialsException.class);
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }

    String ip =
        valueIn(
            tenantA,
            "select ip_address from audit_log where user_id = ? and action_type = 'LOGIN'",
            userId);
    assertThat(ip).isEqualTo("9".repeat(45));
  }

  @Test
  @DisplayName("5회째 실패에서 ACCOUNT_LOCKED 한 건, 잠긴 뒤 거부는 1분에 한 건(LOCKED)만 남는다")
  void lockAndLockedRejectionsAreAudited() {
    TenantContext.clear();

    for (int i = 0; i < 5; i++) {
      assertThatThrownBy(() -> authService.login(new LoginRequest(username, "wrong-pw1")))
          .isInstanceOf(InvalidCredentialsException.class);
    }
    for (int i = 0; i < 3; i++) {
      assertThatThrownBy(() -> authService.login(new LoginRequest(username, PASSWORD)))
          .isInstanceOf(AccountLockedException.class);
    }

    assertThat(rowsIn(tenantA, "ACCOUNT_LOCKED")).hasSize(1);
    List<Record> logins = rowsIn(tenantA, "LOGIN");
    assertThat(logins.stream().filter(r -> "BAD_PASSWORD".equals(r.get("reason")))).hasSize(5);
    assertThat(logins.stream().filter(r -> "LOCKED".equals(r.get("reason"))))
        .as("잠긴 동안의 거부는 같은 아이디당 1분에 한 건으로 합친다")
        .hasSize(1);
  }

  @Test
  @DisplayName("비활성 계정의 로그인 거부도 DEACTIVATED 사유로 남는다")
  void deactivatedLoginIsAudited() {
    dsl.execute("update \"user\" set is_active = false where id = ?", userId);
    TenantContext.clear();

    assertThatThrownBy(() -> authService.login(new LoginRequest(username, PASSWORD)))
        .isInstanceOf(UserDeactivatedException.class);

    List<Record> rows = rowsIn(tenantA, "LOGIN");
    assertThat(rows).hasSize(1);
    assertThat((String) rows.get(0).get("reason")).isEqualTo("DEACTIVATED");
  }

  @Test
  @DisplayName("멤버십이 둘이면 로그인 성공과 같이 실패 행도 NULL 테넌트로 남는다")
  void multiMembershipFailureGoesToNullTenant() {
    TenantRlsTestSupport.insertActiveMembership(dsl, userId, tenantB);
    TenantContext.clear();

    assertThatThrownBy(() -> authService.login(new LoginRequest(username, "wrong-pw1")))
        .isInstanceOf(InvalidCredentialsException.class);

    assertThat(rowsIn(null, "LOGIN")).hasSize(1);
    assertThat(rowsIn(tenantA, "LOGIN")).isEmpty();
    assertThat(rowsIn(tenantB, "LOGIN")).isEmpty();
  }

  @Test
  @DisplayName("비밀번호 변경은 PASSWORD_CHANGE 로 현재 테넌트에 남고, 임시 비밀번호 변경은 표식을 단다")
  void passwordChangeIsAudited() {
    dsl.execute("update \"user\" set must_change_password = true where id = ?", userId);

    TenantContext.runScoped(
        tenantA, () -> userService.changePassword(userId, PASSWORD, "new-pw-123A"));
    TenantContext.runScoped(
        tenantA, () -> userService.changePassword(userId, "new-pw-123A", "new-pw-456B"));

    List<Record> rows = rowsIn(tenantA, "PASSWORD_CHANGE");
    assertThat(rows).extracting(r -> r.get("description"))
        .containsExactly("임시 비밀번호 변경", "비밀번호 변경");
    assertThat(rows).extracting(r -> r.get("temporary"))
        .containsExactly("true", "false");
  }

  /** 주어진 테넌트(null = NULL 테넌트) 스코프의 별도 트랜잭션에서 값 하나를 읽는다 — 로그인 호출이 끝난 뒤 커밋된 행만 보인다. */
  @SuppressWarnings("unchecked")
  private <T> T valueIn(Long tenantId, String sql, Object... args) {
    return TenantRlsTestSupport.runInTenantTransaction(
        tx, tenantId, () -> (T) dsl.fetchValue(sql, args));
  }

  /** 주어진 테넌트(null = NULL 테넌트) 스코프에서 이 사용자의 감사 행을 시간순으로 읽는다(사유·임시 표식은 metadata 에서). */
  private List<Record> rowsIn(Long tenantId, String actionType) {
    return TenantRlsTestSupport.runInTenantTransaction(
        tx,
        tenantId,
        () ->
            dsl.fetch(
                "select result, description, metadata ->> 'reason' as reason,"
                    + " metadata ->> 'temporaryPassword' as temporary"
                    + " from audit_log where user_id = ? and action_type = ? order by id",
                userId,
                actionType));
  }
}
