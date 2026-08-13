package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.auth.dto.LoginRequest;
import com.smartfirehub.auth.dto.SignupRequest;
import com.smartfirehub.auth.dto.TokenResponse;
import com.smartfirehub.auth.exception.TenantAccessDeniedException;
import com.smartfirehub.auth.service.AuthService;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.tenant.dto.MembershipResponse;
import com.smartfirehub.tenant.repository.MembershipRepository;
import com.smartfirehub.user.dto.UserResponse;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 멤버십 조회와 테넌트 선택/전환 동작을 검증한다. */
class TenantSelectionTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private AuthService authService;
  @Autowired private MembershipRepository membershipRepository;
  @Autowired private JwtTokenProvider jwtTokenProvider;

  /**
   * 이 테스트 전용 픽스처 사용자를 회원가입으로 새로 만든다. 공유 테스트 DB의 기존 사용자에
   * 의존하면(예: id 최솟값 조회) 신선한 DB(사전 사용자 0명)에서 null 을 반환해 테스트가
   * 깨지고, 공유 DB에서는 다른 테스트가 만든 사용자 상태에 우연히 의존하게 된다.
   * signupGrantsDefaultTenantMembership 과 동일한 패턴 — nanoTime 접미사로 고유 아이디를 써서
   * 매 실행이 독립적인 사용자를 갖게 하고, 가입 시점에 기본 테넌트 1번 ACTIVE 멤버십이
   * 보장된다(백필이 아니라 signup 경로에서).
   */
  private Long createFixtureUserId() {
    String username = "tenant-selection-" + System.nanoTime() + "@example.com";
    SignupRequest request = new SignupRequest(username, username, "Password123!", "테넌트선택테스트");
    return authService.signup(request).id();
  }

  @Test
  @DisplayName("가입한 사용자는 기본 테넌트 멤버십을 조회할 수 있다")
  void backfilledUserHasDefaultMembership() {
    List<MembershipResponse> memberships = membershipRepository.findActiveByUser(createFixtureUserId());

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
  @DisplayName("회원가입한 사용자는 기본 테넌트에 ACTIVE 멤버십을 자동으로 갖는다")
  void signupGrantsDefaultTenantMembership() {
    // 공유 테스트 DB에서 반복 실행 시 충돌하지 않도록 고유한 아이디를 사용한다.
    String username = "tenant-signup-" + System.nanoTime() + "@example.com";
    SignupRequest request = new SignupRequest(username, username, "Password123!", "테넌트가입테스트");

    UserResponse user = authService.signup(request);

    List<MembershipResponse> memberships = membershipRepository.findActiveByUser(user.id());
    assertThat(memberships).hasSize(1);
    assertThat(memberships.get(0).tenantId()).isEqualTo(1L);
    assertThat(memberships.get(0).tenantSlug()).isEqualTo("default");
    assertThat(membershipRepository.hasActiveMembership(user.id(), 1L)).isTrue();
  }

  @Test
  @DisplayName("갱신 시 소속이 정지된 테넌트는 강등되지만 다른 활성 멤버십 목록은 그대로 내려간다")
  void refreshWithSuspendedTenantDowngradesButKeepsMemberships() {
    String username = "tenant-refresh-" + System.nanoTime() + "@example.com";
    String password = "Password123!";
    UserResponse user = authService.signup(new SignupRequest(username, username, password, "테넌트갱신테스트"));

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
            field(name("user_id")), field(name("tenant_id")), field(name("role")), field(name("status")))
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
    assertThat(refreshed.memberships()).extracting(MembershipResponse::tenantId).contains(otherTenantId);
    assertThat(refreshed.memberships()).extracting(MembershipResponse::tenantId).doesNotContain(1L);
  }
}
