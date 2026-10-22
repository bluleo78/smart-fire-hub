package com.smartfirehub.proactive.service.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.user.dto.UserResponse;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * WD-172: 리포트 수신자 해석은 실행 워크스페이스의 ACTIVE 멤버이면서 계정이 활성인 사용자만 남긴다 — 정지(SUSPENDED)·제거(멤버십 없음)·계정 비활성은
 * 빠지고, 지정 순서는 지킨다.
 */
class ActiveRecipientResolverTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private ActiveRecipientResolver resolver;

  private SecurityFixture fx;
  private final List<Long> users = new ArrayList<>();

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    users.forEach(fx::deleteUser);
  }

  private long member(String prefix) {
    long id = fx.createUser(prefix);
    users.add(id);
    return id;
  }

  @Test
  void keepsOnlyActiveMembersWithActiveAccounts_inGivenOrder() {
    long active2 = member("rr_a2");
    long suspended = member("rr_s");
    long removed = member("rr_r");
    long inactiveAccount = member("rr_i");
    long active1 = member("rr_a1");
    dsl.execute(
        "update membership set status = 'SUSPENDED' where user_id = ? and tenant_id = ?",
        suspended,
        DEFAULT_TEST_TENANT_ID);
    TenantRlsTestSupport.deleteMembership(dsl, removed);
    dsl.execute("update \"user\" set is_active = false where id = ?", inactiveAccount);

    List<Long> got =
        TenantRlsTestSupport.runInTenantTransaction(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () ->
                resolver
                    .activeUsers(
                        List.of(active2, suspended, removed, inactiveAccount, active1, active2))
                    .stream()
                    .map(UserResponse::id)
                    .toList());

    assertThat(got).containsExactly(active2, active1);
  }

  @Test
  void emptyInput_returnsEmpty() {
    assertThat(resolver.activeUsers(List.of())).isEmpty();
  }
}
