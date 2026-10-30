package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.auth.dto.LoginRequest;
import com.smartfirehub.auth.dto.TokenResponse;
import com.smartfirehub.auth.exception.TenantAccessDeniedException;
import com.smartfirehub.auth.service.AuthService;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TestUsers;
import com.smartfirehub.tenant.dto.MembershipResponse;
import com.smartfirehub.tenant.dto.UnavailableWorkspace;
import com.smartfirehub.tenant.repository.MembershipRepository;
import com.smartfirehub.user.dto.UserResponse;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 멤버십 조회와 테넌트 선택/전환 동작을 검증한다. */
class TenantSelectionTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private AuthService authService;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private MembershipRepository membershipRepository;
  @Autowired private JwtTokenProvider jwtTokenProvider;

  /**
   * 이 테스트 전용 픽스처 사용자를 직접 삽입(TestUsers)으로 새로 만든다 — 공개 가입은 첫 사용자만 열려 있다(WD-2). 공유 테스트 DB의 기존 사용자에
   * 의존하면(예: id 최솟값 조회) 신선한 DB(사전 사용자 0명)에서 null 을 반환해 테스트가 깨지고, 공유 DB에서는 다른 테스트가 만든 사용자 상태에 우연히
   * 의존하게 된다. nanoTime 접미사로 고유 아이디를 써서 매 실행이 독립적인 사용자를 갖게 하고, 픽스처가 삽입과 동시에 기본 테넌트 1번 ACTIVE 멤버십을 만든다
   * (백필이나 signup 경로가 아니라 직접 삽입 픽스처에서).
   */
  private Long createFixtureUserId() {
    String username = "tenant-selection-" + System.nanoTime() + "@example.com";
    return TestUsers.createMember(
            dsl,
            fixtureTransactionTemplate,
            passwordEncoder,
            username,
            username,
            "Password123!",
            "테넌트선택테스트",
            DEFAULT_TEST_TENANT_ID)
        .id();
  }

  @Test
  @DisplayName("직접 삽입 픽스처로 만든 사용자는 기본 테넌트 멤버십을 조회할 수 있다")
  void backfilledUserHasDefaultMembership() {
    List<MembershipResponse> memberships =
        membershipRepository.findActiveByUser(createFixtureUserId());

    assertThat(memberships).extracting(MembershipResponse::tenantId).contains(1L);
    assertThat(memberships).extracting(MembershipResponse::tenantSlug).contains("default");
  }

  @Test
  @DisplayName("소속 테넌트를 선택하면 tenant 클레임이 담긴 토큰이 발급된다")
  void selectTenantIssuesTenantScopedToken() {
    Long userId = createFixtureUserId();

    TokenResponse response = authService.selectTenant(userId, 1L);

    assertThat(jwtTokenProvider.getTenantIdFromToken(response.accessToken())).isEqualTo(1L);
    assertThat(jwtTokenProvider.getTenantIdFromToken(response.refreshToken())).isEqualTo(1L);
    assertThat(response.activeTenantId()).isEqualTo(1L);
  }

  @Test
  @DisplayName("소속되지 않은 테넌트를 선택하면 거부된다")
  void selectingForeignTenantIsDenied() {
    Long userId = createFixtureUserId();

    assertThatThrownBy(() -> authService.selectTenant(userId, 99999L))
        .isInstanceOf(TenantAccessDeniedException.class);
  }

  @Test
  @DisplayName("소속이 하나면 로그인 시 자동 선택된다")
  void singleMembershipIsAutoSelected() {
    Long userId = createFixtureUserId();

    assertThat(membershipRepository.findActiveByUser(userId)).hasSize(1);
    assertThat(membershipRepository.hasActiveMembership(userId, 1L)).isTrue();
    assertThat(membershipRepository.hasActiveMembership(userId, 99999L)).isFalse();
  }

  @Test
  @DisplayName("갱신 시 소속이 정지된 테넌트는 강등되지만 다른 활성 멤버십 목록은 그대로 내려간다")
  void refreshWithSuspendedTenantDowngradesButKeepsMemberships() {
    String username = "tenant-refresh-" + System.nanoTime() + "@example.com";
    String password = "Password123!";
    UserResponse user =
        TestUsers.createMember(
            dsl,
            fixtureTransactionTemplate,
            passwordEncoder,
            username,
            username,
            password,
            "테넌트갱신테스트",
            DEFAULT_TEST_TENANT_ID);

    // 로그인 시점에는 소속이 tenant 1(default) 하나뿐이므로 자동 선택되어, 리프레시 토큰이
    // tenant 1 클레임을 담은 채 발급된다.
    TokenResponse loginResult = authService.login(new LoginRequest(username, password));
    assertThat(loginResult.activeTenantId()).isEqualTo(1L);

    // 이 테스트 사용자를 위한 두 번째 테넌트를 만들어 "다른 활성 멤버십이 있는" 상태를 재현하고,
    // 원래 리프레시 토큰이 가리키는 tenant 1 멤버십은 정지시킨다 — refresh() 가 강등만 하고
    // memberships 를 비우지 않는지 검증하려면 최소 하나의 활성 멤버십이 남아 있어야 한다.
    Long otherTenantId =
        dsl.insertInto(table(name("tenant")))
            .columns(field(name("slug")), field(name("name")), field(name("status")))
            .values("refresh-test-tenant-" + System.nanoTime(), "Refresh Test Tenant", "ACTIVE")
            .returning(field(name("id"), Long.class))
            .fetchOne()
            .get(0, Long.class);
    dsl.insertInto(table(name("membership")))
        .columns(
            field(name("user_id")),
            field(name("tenant_id")),
            field(name("role")),
            field(name("status")))
        .values(user.id(), otherTenantId, "MEMBER", "ACTIVE")
        .execute();
    dsl.update(table(name("membership")))
        .set(field(name("status"), String.class), "SUSPENDED")
        .where(field(name("user_id"), Long.class).eq(user.id()))
        .and(field(name("tenant_id"), Long.class).eq(1L))
        .execute();

    TokenResponse refreshed = authService.refresh(loginResult.refreshToken());

    // 정지된 tenant 1 은 더 이상 활성 멤버십이 아니므로 강등(테넌트 미선택)되지만,
    // 사용자는 여전히 다른 테넌트의 활성 멤버이므로 memberships 는 비어 있지 않아야 한다 —
    // 클라이언트가 이 목록으로 재선택 화면을 그릴 수 있어야 한다.
    assertThat(refreshed.activeTenantId()).isNull();
    assertThat(refreshed.memberships()).isNotEmpty();
    assertThat(refreshed.memberships())
        .extracting(MembershipResponse::tenantId)
        .contains(otherTenantId);
    assertThat(refreshed.memberships()).extracting(MembershipResponse::tenantId).doesNotContain(1L);
  }

  // ── WD-281: 정지된 워크스페이스를 로그인 응답에 사유와 함께 싣는다 ──────────────
  // tenant 1(default) 자체는 정지하지 않는다 — JVM 공유 테스트 DB 라 다른 테스트가 깨진다. 워크스페이스 정지는 전용 테넌트를 새로 만들어
  // 재현하고, 멤버십 정지는 이 테스트 전용 사용자의 tenant 1 멤버십만 바꾼다.

  /** 테스트 전용 테넌트를 주어진 상태로 만들고 id 를 돌려준다. */
  private Long insertTenant(String namePrefix, String status) {
    return dsl.insertInto(table(name("tenant")))
        .columns(field(name("slug")), field(name("name")), field(name("status")))
        .values("wd281-" + System.nanoTime(), namePrefix, status)
        .returning(field(name("id"), Long.class))
        .fetchOne()
        .get(0, Long.class);
  }

  /** 사용자를 테넌트에 주어진 상태의 MEMBER 로 넣는다. */
  private void insertMembership(Long userId, Long tenantId, String status) {
    dsl.insertInto(table(name("membership")))
        .columns(
            field(name("user_id")),
            field(name("tenant_id")),
            field(name("role")),
            field(name("status")))
        .values(userId, tenantId, "MEMBER", status)
        .execute();
  }

  /** 이 사용자의 tenant 1 멤버십 상태를 바꾼다. */
  private void setDefaultMembershipStatus(Long userId, String status) {
    dsl.update(table(name("membership")))
        .set(field(name("status"), String.class), status)
        .where(field(name("user_id"), Long.class).eq(userId))
        .and(field(name("tenant_id"), Long.class).eq(1L))
        .execute();
  }

  /** 로그인 가능한 전용 사용자(tenant 1 ACTIVE 멤버)를 만든다. */
  private UserResponse createLoginUser(String username, String password) {
    return TestUsers.createMember(
        dsl,
        fixtureTransactionTemplate,
        passwordEncoder,
        username,
        username,
        password,
        "정지안내테스트",
        DEFAULT_TEST_TENANT_ID);
  }

  @Test
  @DisplayName("WD-281: 유일한 멤버십이 정지되면 로그인 응답의 선택 목록은 비고 MEMBERSHIP_SUSPENDED 로 알린다")
  void loginWithSuspendedMembershipListsMembershipSuspended() {
    String username = "wd281-member-" + System.nanoTime() + "@example.com";
    String password = "Password123!";
    UserResponse user = createLoginUser(username, password);
    setDefaultMembershipStatus(user.id(), "SUSPENDED");

    TokenResponse result = authService.login(new LoginRequest(username, password));

    assertThat(result.activeTenantId()).isNull();
    assertThat(result.memberships()).isEmpty();
    assertThat(result.unavailableWorkspaces())
        .containsExactly(
            new UnavailableWorkspace(
                dsl.select(field(name("name"), String.class))
                    .from(table(name("tenant")))
                    .where(field(name("id"), Long.class).eq(1L))
                    .fetchOne(0, String.class),
                UnavailableWorkspace.Reason.MEMBERSHIP_SUSPENDED));
  }

  @Test
  @DisplayName("WD-281: 워크스페이스가 정지되면 멤버십 상태와 무관하게 WORKSPACE_SUSPENDED 로 알린다")
  void loginWithSuspendedTenantListsWorkspaceSuspended() {
    String username = "wd281-tenant-" + System.nanoTime() + "@example.com";
    String password = "Password123!";
    UserResponse user = createLoginUser(username, password);
    // 멤버십 ACTIVE + 테넌트 정지, 멤버십도 정지 + 테넌트 정지 — 둘 다 운영자가 풀어야 하므로 같은 사유다.
    Long suspendedTenant = insertTenant("정지된 워크스페이스A", "SUSPENDED");
    insertMembership(user.id(), suspendedTenant, "ACTIVE");
    Long bothSuspended = insertTenant("정지된 워크스페이스B", "SUSPENDED");
    insertMembership(user.id(), bothSuspended, "SUSPENDED");

    TokenResponse result = authService.login(new LoginRequest(username, password));

    // tenant 1 은 ACTIVE 그대로라 선택 목록에 남고(자동 선택), 정지된 두 곳만 사유와 함께 따로 온다.
    assertThat(result.memberships()).extracting(MembershipResponse::tenantId).containsExactly(1L);
    assertThat(result.unavailableWorkspaces())
        .containsExactly(
            new UnavailableWorkspace(
                "정지된 워크스페이스A", UnavailableWorkspace.Reason.WORKSPACE_SUSPENDED),
            new UnavailableWorkspace(
                "정지된 워크스페이스B", UnavailableWorkspace.Reason.WORKSPACE_SUSPENDED));
  }

  @Test
  @DisplayName("WD-281: ACTIVE 멤버십만 있으면 정지 목록은 비어 있다")
  void loginWithOnlyActiveMembershipHasNoUnavailable() {
    String username = "wd281-active-" + System.nanoTime() + "@example.com";
    String password = "Password123!";
    UserResponse user = createLoginUser(username, password);
    insertMembership(user.id(), insertTenant("활성 워크스페이스", "ACTIVE"), "ACTIVE");

    TokenResponse result = authService.login(new LoginRequest(username, password));

    assertThat(result.memberships()).hasSize(2);
    assertThat(result.unavailableWorkspaces()).isEmpty();
    assertThat(membershipRepository.findUnavailableByUser(user.id())).isEmpty();
  }

  @Test
  @DisplayName("WD-281: 세션 중 멤버십이 정지돼 refresh 가 강등할 때도 정지 사유를 함께 싣는다")
  void refreshAfterSuspensionListsUnavailable() {
    String username = "wd281-refresh-" + System.nanoTime() + "@example.com";
    String password = "Password123!";
    UserResponse user = createLoginUser(username, password);
    TokenResponse loginResult = authService.login(new LoginRequest(username, password));
    assertThat(loginResult.unavailableWorkspaces()).isEmpty();

    setDefaultMembershipStatus(user.id(), "SUSPENDED");
    TokenResponse refreshed = authService.refresh(loginResult.refreshToken());

    assertThat(refreshed.activeTenantId()).isNull();
    assertThat(refreshed.memberships()).isEmpty();
    assertThat(refreshed.unavailableWorkspaces())
        .extracting(UnavailableWorkspace::reason)
        .containsExactly(UnavailableWorkspace.Reason.MEMBERSHIP_SUSPENDED);
  }

  @Test
  @DisplayName("WD-281: 워크스페이스에서 제거(행 삭제)된 멤버십은 정지 목록에도 나오지 않는다")
  void removedMembershipIsNotListed() {
    Long userId = createFixtureUserId();
    Long tenantId = insertTenant("제거된 워크스페이스", "ACTIVE");
    insertMembership(userId, tenantId, "SUSPENDED");
    membershipRepository.delete(userId, tenantId);

    assertThat(membershipRepository.findUnavailableByUser(userId)).isEmpty();
  }
}
