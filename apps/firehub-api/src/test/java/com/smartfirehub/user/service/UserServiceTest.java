package com.smartfirehub.user.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.auth.exception.EmailAlreadyExistsException;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.user.dto.UserDetailResponse;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.exception.UserNotFoundException;
import java.util.List;
import org.jooq.DSLContext;
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

  private Long testUserId;

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
  }

  @Test
  void getUsers_returnsPaginatedResults() {
    PageResponse<UserResponse> result = userService.getUsers("testuser@example.com", 0, 20);

    assertThat(result.content()).hasSizeGreaterThanOrEqualTo(1);
    assertThat(result.content().stream().anyMatch(u -> u.username().equals("testuser@example.com")))
        .isTrue();
    assertThat(result.page()).isEqualTo(0);
    assertThat(result.size()).isEqualTo(20);
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
    userService.setUserRoles(testUserId, List.of(adminRoleId, userRoleId), testUserId + 1000);

    UserDetailResponse detail = userService.getUserById(testUserId);
    assertThat(detail.roles()).hasSize(2);
  }

  @Test
  void setUserActive_success() {
    // 일반 유저(ADMIN 아님) 비활성화는 정상 동작
    userService.setUserActive(testUserId, false);

    UserDetailResponse detail = userService.getUserById(testUserId);
    assertThat(detail.isActive()).isFalse();
  }

  @Test
  void setUserActive_lastAdmin_throwsException() {
    // 유일한 활성 ADMIN 계정을 비활성화하면 IllegalStateException (#146)
    Long adminRoleId =
        dsl.select(ROLE.ID).from(ROLE).where(ROLE.NAME.eq("ADMIN")).fetchOne(ROLE.ID);

    // testUserId에게 ADMIN 역할 부여
    dsl.insertInto(USER_ROLE)
        .set(USER_ROLE.USER_ID, testUserId)
        .set(USER_ROLE.ROLE_ID, adminRoleId)
        .execute();

    // 시스템에 활성 ADMIN이 testUserId 하나뿐인 상태에서 비활성화 시도
    // (setUp의 testUserId 외 다른 ADMIN이 없는 경우를 가정 — IntegrationTestBase 격리 환경)
    assertThatThrownBy(() -> userService.setUserActive(testUserId, false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("마지막 활성 ADMIN");
  }

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
            .set(USER.IS_ACTIVE, false)
            .returning(USER.ID)
            .fetchOne()
            .getId();

    dsl.insertInto(USER_ROLE)
        .set(USER_ROLE.USER_ID, adminUserId)
        .set(USER_ROLE.ROLE_ID, adminRoleId)
        .execute();

    // 비활성 ADMIN 계정 활성화 — 예외 없이 성공해야 함
    userService.setUserActive(adminUserId, true);

    UserDetailResponse detail = userService.getUserById(adminUserId);
    assertThat(detail.isActive()).isTrue();
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

    dsl.insertInto(USER_ROLE)
        .set(USER_ROLE.USER_ID, secondAdminId)
        .set(USER_ROLE.ROLE_ID, adminRoleId)
        .execute();

    // 2명의 활성 ADMIN 중 한 명 비활성화 — 예외 없이 성공해야 함
    userService.setUserActive(testUserId, false);

    UserDetailResponse detail = userService.getUserById(testUserId);
    assertThat(detail.isActive()).isFalse();
  }
}
