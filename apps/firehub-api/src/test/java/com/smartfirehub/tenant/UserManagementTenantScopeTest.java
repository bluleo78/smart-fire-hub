package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.proactive.dto.RecipientResponse;
import com.smartfirehub.proactive.service.ProactiveJobService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.exception.UserNotFoundException;
import com.smartfirehub.user.service.UserService;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 사용자 관리 경로의 테넌트 격리 회귀 테스트.
 *
 * <p><b>이 결함이 RLS 로 못 막히는 이유:</b> {@code "user"} 는 설계상 전역 테이블이다(tenant_id
 * 컬럼도, RLS 정책도 없다 — 한 사람이 여러 테넌트에 속하고, 로그인은 테넌트가 정해지기 전에
 * 사용자를 찾아야 한다). 그래서 사용자 관리 API 는 다른 도메인처럼 GUC 기반 RLS 에 격리를 맡길 수
 * 없고, {@code membership} 조인으로 애플리케이션이 직접 경계를 세워야 한다. 테넌트 평면 ADMIN 롤은
 * 실제로 {@code user:read}/{@code user:write}/{@code role:assign} 을 갖기 때문에, 그 술어가 빠지면
 * 한 테넌트의 ADMIN 이 남의 테넌트 사용자를 열람·비활성화·롤부여 할 수 있다.
 *
 * <p><b>클래스 레벨 {@code @Transactional} 을 붙이지 않는다.</b> 붙이면 테스트가 열어 준 트랜잭션이
 * GUC 를 공급해, 프로덕션 경로가 스스로 테넌트 컨텍스트를 세우지 못하는 배선 결함을 영구히 가린다.
 * 대신 검증 대상 호출은 {@link TenantContext#runScopedGet} 으로 컨텍스트만 세운 채 부른다 — 운영에서
 * {@code JwtAuthenticationFilter} 가 하는 일과 같고, 트랜잭션은 서비스 자신의
 * {@code @Transactional} 이 연다.
 *
 * <p><b>검색어로 좁혀 단언하는 이유:</b> 공유 테스트 DB 에는 다른 테스트가 남긴 사용자가 많아,
 * 페이지 0 에 B 의 사용자가 없다는 사실만으로는 격리가 아니라 페이지네이션 때문일 수 있다. 실행마다
 * 고유한 접두사를 검색어로 넘겨 후보를 이 테스트가 만든 두 명으로 한정하고, 총건수를 <b>정확히 1</b>
 * 로 단언한다. 아무에게도 0 건을 주는 버그가 통과하지 않도록 긍정 단언(자기 테넌트 사용자는 보인다)
 * 도 함께 둔다.
 */
class UserManagementTenantScopeTest extends IntegrationTestBase {

  @Autowired private UserService userService;
  @Autowired private DSLContext dsl;
  @Autowired private ProactiveJobService proactiveJobService;

  private long tenantA;
  private long tenantB;
  private Long userA;
  private Long userB;
  private String prefix;

  @BeforeEach
  void setUp() {
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "userscope-a");
    tenantB = TenantRlsTestSupport.createActiveTenant(dsl, "userscope-b");

    // tenant/user/membership 은 모두 테넌트 경계 위의 전역 테이블이라 컨텍스트·트랜잭션 없이 쓴다.
    prefix = "p6userscope" + TenantRlsTestSupport.nextTenantId() + "-";
    userA = TenantRlsTestSupport.insertUser(dsl, prefix + "a");
    userB = TenantRlsTestSupport.insertUser(dsl, prefix + "b");
    TenantRlsTestSupport.insertActiveMembership(dsl, userA, tenantA);
    TenantRlsTestSupport.insertActiveMembership(dsl, userB, tenantB);
  }

  @AfterEach
  void tearDown() {
    // FK 순서: membership → user → tenant.
    TenantRlsTestSupport.cleanupAll(
        () -> TenantRlsTestSupport.deleteMembership(dsl, userA),
        () -> TenantRlsTestSupport.deleteMembership(dsl, userB),
        () -> TenantRlsTestSupport.deleteUser(dsl, userA),
        () -> TenantRlsTestSupport.deleteUser(dsl, userB),
        () -> TenantRlsTestSupport.deleteTenants(dsl, tenantA, tenantB));
  }

  @Test
  @DisplayName("목록: 자기 테넌트 사용자만 보이고 남의 테넌트 사용자는 목록·총건수에서 빠진다")
  void listAndCountAreScopedToCurrentTenant() {
    PageResponse<UserResponse> inA =
        TenantContext.runScopedGet(tenantA, () -> userService.getUsers(prefix, 0, 20));
    PageResponse<UserResponse> inB =
        TenantContext.runScopedGet(tenantB, () -> userService.getUsers(prefix, 0, 20));

    assertThat(idsOf(inA))
        .as("자기 테넌트 사용자는 반드시 보여야 한다 — 이 단언이 없으면 '모두 0건' 버그가 통과한다")
        .containsExactly(userA);
    assertThat(inA.totalElements())
        .as("총건수가 남의 테넌트 사용자를 세면 페이지네이션이 없는 행을 가리킨다")
        .isEqualTo(1);

    assertThat(idsOf(inB)).containsExactly(userB);
    assertThat(inB.totalElements()).isEqualTo(1);
  }

  @Test
  @DisplayName("상세: 남의 테넌트 사용자 id 로 조회하면 404 — 존재를 알려 주지 않는다")
  void getUserByIdHidesOtherTenantUser() {
    // 긍정: 자기 테넌트 사용자는 정상 조회된다.
    var own = TenantContext.runScopedGet(tenantA, () -> userService.getUserById(userA));
    assertThat(own.id()).isEqualTo(userA);

    // 부정: 남의 테넌트 사용자는 "없는 사용자" 다. 403 이면 id 를 훑어 계정을 열거할 수 있다.
    assertThatThrownBy(
            () -> TenantContext.runScoped(tenantA, () -> userService.getUserById(userB)))
        .isInstanceOf(UserNotFoundException.class);
  }

  @Test
  @DisplayName("역할 부여: 남의 테넌트 사용자에게는 404 로 막힌다")
  void setUserRolesRejectsOtherTenantUser() {
    assertThatThrownBy(
            () ->
                TenantContext.runScoped(
                    tenantA, () -> userService.setUserRoles(userB, List.of(), userA)))
        .isInstanceOf(UserNotFoundException.class);
  }

  @Test
  @DisplayName("비활성화: 남의 테넌트 사용자에게는 404 로 막힌다")
  void setUserActiveRejectsOtherTenantUser() {
    assertThatThrownBy(
            () -> TenantContext.runScoped(tenantA, () -> userService.setUserActive(userB, false)))
        .isInstanceOf(UserNotFoundException.class);

    // 남의 테넌트 사용자가 실제로 비활성화되지 않았음을 확인한다 — 예외만 보고 만족하면, 예외를
    // 던지기 전에 이미 UPDATE 를 실행하는 순서 결함을 놓친다.
    assertThat(isActive(userB)).isTrue();
  }

  @Test
  @DisplayName("알림 수신자 검색: 남의 테넌트 사용자의 이름·이메일이 후보에 섞이지 않는다")
  void recipientSearchIsScopedToCurrentTenant() {
    // 이 피커는 사용자 관리 화면이 아니라 프로액티브 작업 편집 화면에 있어 `user:read` 권한과도
    // 무관하다 — 같은 미스코프 조회를 재사용하다 노출됐던 경로다. 호출부가 하나 더 늘 때
    // 테넌트 인자를 빠뜨리면 여기서 잡힌다.
    var inA =
        TenantContext.runScopedGet(tenantA, () -> proactiveJobService.searchRecipients(prefix));
    var inB =
        TenantContext.runScopedGet(tenantB, () -> proactiveJobService.searchRecipients(prefix));

    assertThat(inA.stream().map(RecipientResponse::userId))
        .as("자기 테넌트 후보는 보여야 한다 — 없으면 '모두 0건' 버그가 통과한다")
        .containsExactly(userA);
    assertThat(inB.stream().map(RecipientResponse::userId)).containsExactly(userB);
  }

  private List<Long> idsOf(PageResponse<UserResponse> page) {
    return page.content().stream().map(UserResponse::id).toList();
  }

  private boolean isActive(Long userId) {
    return dsl.select(field(name("is_active"), Boolean.class))
        .from(table(name("user")))
        .where(field(name("id"), Long.class).eq(userId))
        .fetchOne(0, Boolean.class);
  }
}
