package com.smartfirehub.user.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.auth.exception.EmailAlreadyExistsException;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import com.smartfirehub.tenant.repository.MembershipRepository;
import com.smartfirehub.user.dto.UserDetailResponse;
import com.smartfirehub.user.dto.UserListResponse;
import com.smartfirehub.user.exception.UserNotFoundException;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class UserServiceTest extends IntegrationTestBase {

  @Autowired private UserService userService;

  @Autowired private PasswordEncoder passwordEncoder;

  @Autowired private DSLContext dsl;

  @Autowired private MembershipRepository membershipRepository;

  private Long testUserId;

  /** 감사 로그 행위자 — audit_log.user_id 는 "user"(id) FK 라 실제 사용자여야 한다(WD-2). */
  private Long callerId;

  @BeforeEach
  void setUp() {
    testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "testuser@example.com")
            .set(USER.PASSWORD, passwordEncoder.encode("Password123"))
            .set(USER.NAME, "Test User")
            .set(USER.EMAIL, "test@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
    joinDefaultTenant(testUserId);
    callerId =
        TestUsers.createMember(
                dsl,
                fixtureTransactionTemplate,
                passwordEncoder,
                "caller@example.com",
                "caller@example.com",
                "Password123",
                "Caller",
                DEFAULT_TEST_TENANT_ID)
            .id();
  }

  @AfterEach
  void tearDownCaller() {
    // 클래스가 @Transactional 이라 testUser 는 롤백되지만 callerId 는 별도 커밋 트랜잭션으로 만들었다.
    TestUsers.cleanup(dsl, fixtureTransactionTemplate, callerId, DEFAULT_TEST_TENANT_ID);
  }

  /**
   * 픽스처 사용자를 기본 테넌트의 ACTIVE 멤버로 만든다.
   *
   * <p>사용자 관리 경로(목록·상세·역할부여·활성화)는 {@code "user"} 가 전역 테이블이라 RLS 로 덮을 수 없어 {@code membership} 조인으로
   * 테넌트를 좁힌다. 멤버십이 없으면 이 테스트의 픽스처는 관리 경로에서 "없는 사용자"(404)로 보인다 — 운영에서도 회원가입이 곧바로 기본 테넌트에 가입시키므로
   * (SignupTransaction) 멤버십이 있는 상태가 정상이다.
   */
  private void joinDefaultTenant(Long userId) {
    TenantRlsTestSupport.insertActiveMembership(dsl, userId, DEFAULT_TEST_TENANT_ID);
  }

  @Test
  void getUsers_returnsPaginatedResults() {
    PageResponse<UserListResponse> result = userService.getUsers("testuser@example.com", 0, 20);

    assertThat(result.content()).hasSizeGreaterThanOrEqualTo(1);
    assertThat(result.content().stream().anyMatch(u -> u.username().equals("testuser@example.com")))
        .isTrue();
    assertThat(result.page()).isEqualTo(0);
    assertThat(result.size()).isEqualTo(20);
  }

  /**
   * 목록 조회도 상세 조회처럼 역할을 함께 반환해야 한다(#586) — admin-manager subagent가 list_users() 한 번의 호출만으로 "역할" 컬럼을
   * 채울 수 있어야 하므로, 목록 응답에 역할이 실려 있는지 회귀 테스트로 고정한다.
   */
  @Test
  void getUsers_includesRolesPerUser() {
    Long adminRoleId =
        dsl.select(ROLE.ID).from(ROLE).where(ROLE.NAME.eq("ADMIN")).fetchOne(ROLE.ID);
    dsl.insertInto(USER_ROLE)
        .set(USER_ROLE.USER_ID, testUserId)
        .set(USER_ROLE.ROLE_ID, adminRoleId)
        .execute();

    PageResponse<UserListResponse> result = userService.getUsers("testuser@example.com", 0, 20);

    UserListResponse target =
        result.content().stream()
            .filter(u -> u.username().equals("testuser@example.com"))
            .findFirst()
            .orElseThrow();
    assertThat(target.roles()).extracting("name").containsExactly("ADMIN");
  }

  @Test
  void getUserById_returnsUserWithRoles() {
    Long adminRoleId =
        dsl.select(ROLE.ID).from(ROLE).where(ROLE.NAME.eq("ADMIN")).fetchOne(ROLE.ID);

    dsl.insertInto(USER_ROLE)
        .set(USER_ROLE.USER_ID, testUserId)
        .set(USER_ROLE.ROLE_ID, adminRoleId)
        .execute();

    UserDetailResponse result = userService.getUserById(testUserId);

    assertThat(result.id()).isEqualTo(testUserId);
    assertThat(result.username()).isEqualTo("testuser@example.com");
    assertThat(result.roles()).hasSize(1);
    assertThat(result.roles().get(0).name()).isEqualTo("ADMIN");
  }

  @Test
  void getUserById_notFound_throwsException() {
    assertThatThrownBy(() -> userService.getUserById(999L))
        .isInstanceOf(UserNotFoundException.class);
  }

  @Test
  void updateProfile_success() {
    userService.updateProfile(testUserId, "New Name", "new@example.com");

    UserDetailResponse updated = userService.getUserById(testUserId);
    assertThat(updated.name()).isEqualTo("New Name");
    assertThat(updated.email()).isEqualTo("new@example.com");
  }

  @Test
  void updateProfile_emailConflict_throwsException() {
    dsl.insertInto(USER)
        .set(USER.USERNAME, "otheruser@example.com")
        .set(USER.PASSWORD, "password")
        .set(USER.NAME, "Other User")
        .set(USER.EMAIL, "taken@example.com")
        .execute();

    assertThatThrownBy(() -> userService.updateProfile(testUserId, "New Name", "taken@example.com"))
        .isInstanceOf(EmailAlreadyExistsException.class);
  }

  @Test
  void changePassword_success() {
    userService.changePassword(testUserId, "Password123", "newpassword");

    String storedPassword =
        dsl.select(USER.PASSWORD).from(USER).where(USER.ID.eq(testUserId)).fetchOne(USER.PASSWORD);
    assertThat(passwordEncoder.matches("newpassword", storedPassword)).isTrue();
  }

  @Test
  void changePassword_wrongCurrentPassword_throwsException() {
    // 현재 비밀번호 불일치 → 400 Bad Request를 위한 IllegalArgumentException (#27)
    assertThatThrownBy(() -> userService.changePassword(testUserId, "wrongpass", "newpass"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("현재 비밀번호가 올바르지 않습니다");
  }

  @Test
  void setUserRoles_success() {
    Long adminRoleId =
        dsl.select(ROLE.ID).from(ROLE).where(ROLE.NAME.eq("ADMIN")).fetchOne(ROLE.ID);

    Long userRoleId = dsl.select(ROLE.ID).from(ROLE).where(ROLE.NAME.eq("USER")).fetchOne(ROLE.ID);

    // callerId를 testUserId와 다르게 설정하여 자기 자신 역할 제거 보호 로직 비활성화
    userService.setUserRoles(testUserId, List.of(adminRoleId, userRoleId), callerId);

    UserDetailResponse detail = userService.getUserById(testUserId);
    assertThat(detail.roles()).hasSize(2);
  }

  @Test
  void setUserActive_success() {
    // 일반 유저(ADMIN 아님) 비활성화는 정상 동작 — callerId를 대상과 다르게 설정
    userService.setUserActive(testUserId, false, callerId);

    // 멤버십만 정지되고 전역 계정 활성은 그대로다(WD-2).
    assertThat(
            membershipRepository
                .findInTenant(testUserId, DEFAULT_TEST_TENANT_ID)
                .orElseThrow()
                .status())
        .isEqualTo("SUSPENDED");
    assertThat(
            dsl.select(USER.IS_ACTIVE)
                .from(USER)
                .where(USER.ID.eq(testUserId))
                .fetchOne(USER.IS_ACTIVE))
        .isTrue();
  }

  @Test
  void setUserActive_self_throwsException() {
    // 자기 자신 비활성화 차단 — 사용자가 1인칭으로 자기 계정을 지칭해도 AI 에이전트가
    // 이를 실행해버린 사고(#585)에 대한 서버측 방어선. callerId == 대상 userId 면 거부.
    assertThatThrownBy(() -> userService.setUserActive(testUserId, false, testUserId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("자기 자신은 정지하거나 제거할 수 없습니다");

    UserDetailResponse detail = userService.getUserById(testUserId);
    assertThat(detail.isActive()).isTrue();
  }

  @Test
  void setUserActive_selfActivation_allowed() {
    // 자기 자신을 "활성화"하는 것은 위험하지 않으므로 차단 대상이 아니다 (active=true 는 통과).
    userService.setUserActive(testUserId, true, testUserId);

    UserDetailResponse detail = userService.getUserById(testUserId);
    assertThat(detail.isActive()).isTrue();
  }

  // setUserActive_lastAdmin_throwsException 삭제: 공유 기본 테넌트에는 다른 테스트의 ADMIN 이 섞여 판정이
  // 오염된다.
  // MembershipLifecycleServiceTest.lastActiveAdmin_rejected_whenOtherAdminSuspendedInThisTenant
  // (전용 테넌트)로 대체했다.

  @Test
  void setUserActive_lastAdmin_allowActivation() {
    // 비활성 ADMIN을 다시 활성화하는 것은 항상 허용
    Long adminRoleId =
        dsl.select(ROLE.ID).from(ROLE).where(ROLE.NAME.eq("ADMIN")).fetchOne(ROLE.ID);

    dsl.insertInto(USER_ROLE)
        .set(USER_ROLE.USER_ID, testUserId)
        .set(USER_ROLE.ROLE_ID, adminRoleId)
        .execute();

    // 먼저 다른 ADMIN을 하나 더 만들어 비활성화 가능 상태로
    Long adminUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "admin2@example.com")
            .set(USER.PASSWORD, passwordEncoder.encode("Password123"))
            .set(USER.NAME, "Admin 2")
            .set(USER.EMAIL, "admin2@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
    joinDefaultTenant(adminUserId);
    membershipRepository.updateStatus(adminUserId, DEFAULT_TEST_TENANT_ID, "SUSPENDED");

    dsl.insertInto(USER_ROLE)
        .set(USER_ROLE.USER_ID, adminUserId)
        .set(USER_ROLE.ROLE_ID, adminRoleId)
        .execute();

    // 비활성 ADMIN 계정 활성화 — 예외 없이 성공해야 함
    userService.setUserActive(adminUserId, true, callerId);

    assertThat(
            membershipRepository
                .findInTenant(adminUserId, DEFAULT_TEST_TENANT_ID)
                .orElseThrow()
                .isActive())
        .isTrue();
  }

  @Test
  void setUserActive_multipleAdmins_allowDeactivation() {
    // 활성 ADMIN이 2명 이상이면 한 명 비활성화 허용 (#146)
    Long adminRoleId =
        dsl.select(ROLE.ID).from(ROLE).where(ROLE.NAME.eq("ADMIN")).fetchOne(ROLE.ID);

    dsl.insertInto(USER_ROLE)
        .set(USER_ROLE.USER_ID, testUserId)
        .set(USER_ROLE.ROLE_ID, adminRoleId)
        .execute();

    Long secondAdminId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "admin3@example.com")
            .set(USER.PASSWORD, passwordEncoder.encode("Password123"))
            .set(USER.NAME, "Admin 3")
            .set(USER.EMAIL, "admin3@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
    joinDefaultTenant(secondAdminId);

    dsl.insertInto(USER_ROLE)
        .set(USER_ROLE.USER_ID, secondAdminId)
        .set(USER_ROLE.ROLE_ID, adminRoleId)
        .execute();

    // 2명의 활성 ADMIN 중 한 명 비활성화 — 예외 없이 성공해야 함
    userService.setUserActive(testUserId, false, callerId);

    assertThat(
            membershipRepository
                .findInTenant(testUserId, DEFAULT_TEST_TENANT_ID)
                .orElseThrow()
                .status())
        .isEqualTo("SUSPENDED");
  }
}
