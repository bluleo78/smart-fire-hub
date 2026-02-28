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
    assertThatThrownBy(() -> userService.changePassword(testUserId, "wrongpass", "newpass"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Current password is incorrect");
  }

  @Test
  void setUserRoles_success() {
    Long adminRoleId =
        dsl.select(ROLE.ID).from(ROLE).where(ROLE.NAME.eq("ADMIN")).fetchOne(ROLE.ID);

    Long userRoleId = dsl.select(ROLE.ID).from(ROLE).where(ROLE.NAME.eq("USER")).fetchOne(ROLE.ID);

    userService.setUserRoles(testUserId, List.of(adminRoleId, userRoleId));

    UserDetailResponse detail = userService.getUserById(testUserId);
    assertThat(detail.roles()).hasSize(2);
  }

  @Test
  void setUserActive_success() {
    userService.setUserActive(testUserId, false);

    UserDetailResponse detail = userService.getUserById(testUserId);
    assertThat(detail.isActive()).isFalse();
  }
}
