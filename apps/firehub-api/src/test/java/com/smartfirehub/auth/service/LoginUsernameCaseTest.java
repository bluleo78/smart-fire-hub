package com.smartfirehub.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.auth.dto.LoginRequest;
import com.smartfirehub.auth.exception.InvalidCredentialsException;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TestUsers;
import com.smartfirehub.user.repository.UserRepository;
import java.util.Locale;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인 아이디의 대소문자·앞뒤 공백 처리(WD-269).
 *
 * <p>멤버 추가는 이메일을 소문자로 바꿔 username 으로 저장하는데, 사용자는 안내받은 대소문자 그대로 치거나 모바일이 첫 글자를 대문자로 바꾼다. 같은 계정으로
 * 로그인돼야 하고, 대소문자를 섞어 저장된 옛 계정은 그대로 로그인돼야 하며, 대소문자만 다른 계정이 둘이면 멤버 추가와 같은 계정을 골라야 한다.
 */
@Transactional
class LoginUsernameCaseTest extends IntegrationTestBase {

  private static final String PASSWORD = "Password123";

  @Autowired private AuthService authService;
  @Autowired private LoginAttemptService loginAttemptService;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private UserRepository userRepository;

  /** 관리자가 추가한(소문자로 저장된) 계정에 대문자를 섞고 앞뒤 공백을 붙여 입력해도 로그인된다. */
  @Test
  void login_mixedCaseInput_logsIntoLowercaseAccount() {
    String username = "case269-" + System.nanoTime() + "@example.com";
    createMember(username);

    var result =
        authService.login(
            new LoginRequest("  " + username.toUpperCase(Locale.ROOT) + " ", PASSWORD));

    assertThat(result.accessToken()).isNotBlank();
  }

  /** 대소문자를 섞어 저장된 옛 계정(첫 가입자 등)은 저장된 그대로도, 소문자로도 로그인된다. */
  @Test
  void login_legacyMixedCaseUsername_exactAndLowercaseBothWork() {
    String stored = "Legacy269-" + System.nanoTime() + "@Example.com";
    createMember(stored);

    assertThat(authService.login(new LoginRequest(stored, PASSWORD)).accessToken()).isNotBlank();
    assertThat(
            authService
                .login(new LoginRequest(stored.toLowerCase(Locale.ROOT), PASSWORD))
                .accessToken())
        .isNotBlank();
  }

  /**
   * 대소문자만 다른 계정이 둘이면, 정확히 같지 않은 입력은 멤버 추가와 같은 계정(id 가 작은 쪽)으로 해석한다 — 멤버십이 붙은 계정과 로그인되는 계정이 갈리지 않게.
   * 정확히 같은 입력은 늘 그 계정이다.
   */
  @Test
  void login_caseOnlyDuplicates_followMemberAddChoiceUnlessExact() {
    String suffix = System.nanoTime() + "@example.com";
    String older = "Dup269-" + suffix;
    String newer = "dup269-" + suffix;
    createMember(older);
    createMember(newer);

    String input = "DUP269-" + suffix.toUpperCase(Locale.ROOT);
    assertThat(userRepository.resolveLoginUsername(input))
        .isEqualTo(userRepository.findByUsernameIgnoreCase(input).orElseThrow().username())
        .isEqualTo(older);
    assertThat(authService.login(new LoginRequest(input, PASSWORD)).accessToken()).isNotBlank();
    assertThat(userRepository.resolveLoginUsername(newer)).isEqualTo(newer);
  }

  /** 실패 카운터는 저장된 아이디 하나로 쌓인다 — 대소문자만 바꿔 가며 잠금을 피할 수 없다. */
  @Test
  void failedAttempts_withDifferentCases_shareOneCounter() {
    String username = "lock269-" + System.nanoTime() + "@example.com";
    createMember(username);

    String[] variants = {
      username, username.toUpperCase(Locale.ROOT), " " + username, capitalize(username), username
    };
    for (String variant : variants) {
      assertThatThrownBy(() -> authService.login(new LoginRequest(variant, "wrong-password")))
          .isInstanceOf(InvalidCredentialsException.class);
    }

    assertThat(loginAttemptService.isBlocked(username)).isTrue();
  }

  private void createMember(String username) {
    TestUsers.createMember(
        dsl,
        fixtureTransactionTemplate,
        passwordEncoder,
        username,
        username,
        PASSWORD,
        "WD-269",
        DEFAULT_TEST_TENANT_ID);
  }

  private static String capitalize(String s) {
    return Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }
}
