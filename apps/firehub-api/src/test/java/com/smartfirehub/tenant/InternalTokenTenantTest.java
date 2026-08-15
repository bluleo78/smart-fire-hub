package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 내부 토큰({@code Authorization: Internal ...} + {@code X-On-Behalf-Of}) 인증 경로가 대행 대상
 * 사용자의 테넌트를 세우는지 고정한다 (코드리뷰 HIGH-1).
 *
 * <p>왜 필요한가: 이 경로는 JWT 가 없어 tenant 클레임이 없다. V99 로 {@code role_permission} ·
 * {@code user_role} 에 RLS 가 걸렸으므로, 테넌트 없이 권한을 조회하면 0행이 되어 권한 집합이 비고
 * {@code PermissionInterceptor} 가 모든 {@code @RequirePermission} 엔드포인트를 403 으로 막는다 —
 * firehub-ai-agent 의 MCP 도구 호출이 전부 죽는다.
 *
 * <p><b>클래스 레벨 {@code @Transactional} 이 없는 것은 의도된 것이다.</b> 붙이면 테스트 트랜잭션이
 * GUC 를 공급해 운영에 없는 조건이 만들어지고, 검증 대상인 "필터가 테넌트를 세우는가" 자체가
 * 가려진다. 픽스처와 정리만 트랜잭션으로 감싸고 필터 호출은 트랜잭션 밖에 둔다.
 */
class InternalTokenTenantTest extends IntegrationTestBase {

  /** application-test.yml 의 {@code agent.internal-token} 값. */
  private static final String INTERNAL_TOKEN = "test-internal-token";

  @Autowired private DSLContext dsl;
  @Autowired private TransactionTemplate tx;
  @Autowired private JwtAuthenticationFilter filter;

  private long tenantA;
  private long tenantB;
  private Long userId;
  private Long roleId;
  private Long permissionId;
  private String permissionCode;

  @BeforeEach
  void setUp() {
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "internal-a");
    tenantB = TenantRlsTestSupport.createActiveTenant(dsl, "internal-b");
    // "user" 와 permission 은 테넌트 경계 위의 전역 테이블(RLS 없음)이라 컨텍스트 없이 만진다.
    userId = TenantRlsTestSupport.insertUser(dsl, "internal");
    permissionId = (Long) dsl.fetchValue("select id from permission order by id limit 1");
    permissionCode = (String) dsl.fetchValue("select code from permission where id = ?", permissionId);

    // 권한 부여는 전부 tenantA 안에서 — RLS 대상이라 컨텍스트 트랜잭션이 필요하다.
    roleId =
        TenantRlsTestSupport.runInTenantTransaction(
            tx,
            tenantA,
            () ->
                (Long)
                    dsl.fetchValue(
                        "insert into role (name, description, is_system)"
                            + " values (?, ?, false) returning id",
                        "내부토큰검증-" + TenantRlsTestSupport.nextTenantId(),
                        "내부 토큰 테넌트 검증용"));
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        tenantA,
        () -> {
          dsl.execute(
              "insert into role_permission (role_id, permission_id) values (?, ?)",
              roleId,
              permissionId);
          dsl.execute("insert into user_role (user_id, role_id) values (?, ?)", userId, roleId);
        });
  }

  @AfterEach
  void tearDown() {
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        tenantA,
        () -> {
          dsl.execute("delete from role_permission where role_id = ?", roleId);
          dsl.execute("delete from user_role where user_id = ?", userId);
          dsl.execute("delete from role where id = ?", roleId);
        });
    dsl.execute("delete from membership where user_id = ?", userId);
    TenantRlsTestSupport.deleteUser(dsl, userId);
    TenantRlsTestSupport.deleteTenants(dsl, tenantA, tenantB);
    // 권한이 형제 테스트로 새지 않게 한다.
    SecurityContextHolder.clearContext();
    TenantContext.clear();
  }

  /** membership 은 전역(RLS 미적용) 테이블이라 테넌트 컨텍스트 없이 삽입한다. */
  private void insertMembership(long tenantId) {
    dsl.execute(
        "insert into membership (user_id, tenant_id, role, status)"
            + " values (?, ?, 'MEMBER', 'ACTIVE')",
        userId,
        tenantId);
  }

  /** 운영의 ai-agent 호출을 재현한다 — 앰비언트 트랜잭션도 테넌트 컨텍스트도 없다. */
  private Authentication runFilter() throws Exception {
    SecurityContextHolder.clearContext();
    TenantContext.clear();

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Internal " + INTERNAL_TOKEN);
    request.addHeader("X-On-Behalf-Of", String.valueOf(userId));

    // 필터는 finally 에서 컨텍스트를 지우므로, 체인 안(= 실제 컨트롤러가 도는 시점)에서 인증 결과를
    // 캡처해야 한다. 필터가 반환된 뒤에는 SecurityContext 도 남아 있지만 컨텍스트는 이미 비어 있다.
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request, new MockHttpServletResponse(), chain);
    return SecurityContextHolder.getContext().getAuthentication();
  }

  @Test
  @DisplayName("멤버십이 하나면 그 테넌트로 권한이 채워진다")
  void singleMembershipResolvesAuthorities() throws Exception {
    insertMembership(tenantA);

    Authentication authentication = runFilter();

    assertThat(authentication).as("내부 토큰 인증 자체가 성립해야 한다").isNotNull();
    assertThat(authentication.getAuthorities())
        .as(
            "테넌트를 세우지 않으면 RLS 가 걸린 role_permission·user_role 조회가 0행이 되어 권한이"
                + " 비고, PermissionInterceptor 가 모든 @RequirePermission 을 403 으로 막는다")
        .extracting(GrantedAuthority::getAuthority)
        .contains(permissionCode);
  }

  @Test
  @DisplayName("멤버십이 여러 개면 테넌트를 추측하지 않고 fail-closed 한다")
  void ambiguousMembershipFailsClosed() throws Exception {
    insertMembership(tenantA);
    insertMembership(tenantB);

    Authentication authentication = runFilter();

    // 임의로 기본 테넌트를 고르면 audit_log 의 형태 (b) 정책(GUC 미설정 시 fail-open)과 맞물려
    // 교차 테넌트 유출이 된다. 권한 0개 → 403 이 의도된 결과다.
    assertThat(authentication).isNotNull();
    assertThat(authentication.getAuthorities())
        .as("테넌트가 모호할 때 권한이 채워지면 어느 테넌트를 추측했다는 뜻이다")
        .isEmpty();
  }

  @Test
  @DisplayName("멤버십이 없어도 테넌트를 추측하지 않는다")
  void noMembershipFailsClosed() throws Exception {
    Authentication authentication = runFilter();

    assertThat(authentication).isNotNull();
    assertThat(authentication.getAuthorities()).isEmpty();
  }
}
