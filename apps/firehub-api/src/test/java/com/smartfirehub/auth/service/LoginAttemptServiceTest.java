package com.smartfirehub.auth.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoginAttemptServiceTest {

  private LoginAttemptService loginAttemptService;

  @BeforeEach
  void setUp() {
    loginAttemptService = new LoginAttemptService();
  }

  @Test
  void isBlocked_returnsFalse_whenNoAttempts() {
    assertFalse(loginAttemptService.isBlocked("user@test.com"));
  }

  @Test
  void isBlocked_returnsFalse_whenBelowMaxAttempts() {
    for (int i = 0; i < 4; i++) {
      loginAttemptService.loginFailed("user@test.com");
    }
    assertFalse(loginAttemptService.isBlocked("user@test.com"));
  }

  @Test
  void isBlocked_returnsTrue_afterMaxAttempts() {
    for (int i = 0; i < 5; i++) {
      loginAttemptService.loginFailed("user@test.com");
    }
    assertTrue(loginAttemptService.isBlocked("user@test.com"));
  }

  @Test
  void loginSucceeded_resetsAttempts() {
    for (int i = 0; i < 5; i++) {
      loginAttemptService.loginFailed("user@test.com");
    }
    assertTrue(loginAttemptService.isBlocked("user@test.com"));

    loginAttemptService.loginSucceeded("user@test.com");
    assertFalse(loginAttemptService.isBlocked("user@test.com"));
  }

  @Test
  void isBlocked_isolatesUsernames() {
    for (int i = 0; i < 5; i++) {
      loginAttemptService.loginFailed("blocked@test.com");
    }
    assertTrue(loginAttemptService.isBlocked("blocked@test.com"));
    assertFalse(loginAttemptService.isBlocked("other@test.com"));
  }
}
