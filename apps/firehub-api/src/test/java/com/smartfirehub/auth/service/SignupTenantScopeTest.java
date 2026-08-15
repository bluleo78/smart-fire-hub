package com.smartfirehub.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.auth.dto.SignupRequest;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 회원가입은 permitAll 이라 JWT 가 없고 TenantContext 도 비어 있다. 그런데 내부에서
 * role 을 이름으로 조회하므로, role 에 RLS 가 걸리면 컨텍스트 없이는 0행이 되어
 * RoleNotFoundException 으로 가입 자체가 깨진다.
 *
 * <p>이 테스트는 "컨텍스트가 비어 있는 상태에서 가입이 성공하고, 기본 테넌트의 역할이
 * 부여된다"를 고정한다. TenantContext 를 세팅하지 않는 것이 이 테스트의 핵심이다.
 */
class SignupTenantScopeTest extends IntegrationTestBase {

  @Autowired private AuthService authService;
  @Autowired private DSLContext dsl;
  @Autowired private TransactionTemplate tx;

  @Test
  @DisplayName("테넌트 컨텍스트 없이도 회원가입이 성공하고 기본 테넌트 역할이 붙는다")
  void signupSucceedsWithoutTenantContext() {
    TenantContext.clear();

    String username = "p2c-" + UUID.randomUUID().toString().substring(0, 8);
    var response =
        authService.signup(new SignupRequest(username, username + "@example.com", "Passw0rd!", "P2C"));

    assertThat(response.id()).isNotNull();

    // 부여된 역할이 기본 테넌트(1)의 역할인지 확인한다. 테스트 커넥션은 비특권 롤 app_tenant 라
    // V99 정책이 그대로 적용된다 — 검증 조회도 기본 테넌트 컨텍스트 트랜잭션 안에서 해야 한다
    // (밖에서 읽으면 GUC 가 비어 0행이 되고 "실패했다"와 구분되지 않는다).
    Long roleTenant =
        TenantRlsTestSupport.runInTenantTransaction(
            tx,
            DEFAULT_TEST_TENANT_ID,
            () ->
                (Long)
                    dsl.fetchValue(
                        "select r.tenant_id from user_role ur join role r on r.id = ur.role_id"
                            + " where ur.user_id = ? limit 1",
                        response.id()));
    assertThat(roleTenant).isEqualTo(1L);

    // 정리 — audit_log·user_role 도 RLS 대상이라 컨텍스트 트랜잭션 안에서 지운다. 밖에서 지우면
    // 0행만 지워지고 뒤이은 "user" 삭제가 FK 로 터진다.
    // audit_log 가 "user" FK 를 잡고 있으므로 먼저 지운다.
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        DEFAULT_TEST_TENANT_ID,
        () -> {
          dsl.execute("delete from audit_log where user_id = ?", response.id());
          dsl.execute("delete from user_role where user_id = ?", response.id());
        });
    // 가입 감사 로그 중 테넌트 선택 전에 기록된 NULL 테넌트 행은 컨텍스트 없는 트랜잭션에서만
    // 보인다(형태 b 정책: NULL IS NOT DISTINCT FROM NULL).
    TenantRlsTestSupport.runInTenantTransaction(
        tx, null, () -> dsl.execute("delete from audit_log where user_id = ?", response.id()));
    // membership·"user" 는 테넌트 경계 위의 전역 테이블이라 RLS 가 없다.
    dsl.execute("delete from membership where user_id = ?", response.id());
    dsl.execute("delete from \"user\" where id = ?", response.id());
  }
}
