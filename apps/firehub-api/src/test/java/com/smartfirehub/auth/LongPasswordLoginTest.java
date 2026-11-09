package com.smartfirehub.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * WD-605: 72바이트 상한을 "설정" 경로에만 건 뒤에도, 예전에 72바이트보다 길게 만든 비밀번호의 기존 사용자가 그대로 로그인되는지 고정한다.
 *
 * <p>왜 필요한가: 상한 도입 전에는 100자 비밀번호도 저장됐다(BCrypt 가 앞 72바이트만 해시). 로그인 쪽에 같은 상한을 걸면 그 사람들은 원래 비밀번호를 정확히
 * 입력해도 들어올 수 없게 된다. 로그인은 길이를 막지 않고, 검증도 예전처럼 BCrypt 가 앞 72바이트로 한다 — 그래서 결과는 상한 도입 전과 같다.
 *
 * <p>저장 해시를 "앞 72바이트"의 encode 로 만드는 이유: 상한 전 서버가 100자 비밀번호로 만든 해시와 비트 단위로 같은 검증 결과를 낸다(BCrypt 가 어차피
 * 앞 72바이트만 쓴다). 100자를 그대로 encode 하면 72바이트 초과 encode 를 거부하는 Spring Security 6.5+ 로 올릴 때 이 준비 단계가 먼저
 * 깨진다 — 검증 대상(로그인)이 아닌 곳에서 실패하지 않게 했다.
 */
@AutoConfigureMockMvc
class LongPasswordLoginTest extends IntegrationTestBase {

  /** 상한 전 저장된 100자 비밀번호(대·소문자·숫자 포함). */
  private static final String LONG_PASSWORD = "Legacy1" + "p".repeat(93);

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder passwordEncoder;

  private String username;
  private Long userId;

  @BeforeEach
  void setUp() {
    username = "wd605-" + System.nanoTime();
    byte[] bytes = LONG_PASSWORD.getBytes(StandardCharsets.UTF_8);
    String first72Bytes = new String(Arrays.copyOf(bytes, 72), StandardCharsets.UTF_8);
    userId =
        TenantRlsTestSupport.insertUserWithPassword(
            dsl, username, passwordEncoder.encode(first72Bytes));
    // 로그인은 permitAll 이라 운영에선 테넌트 컨텍스트가 없다 — 기본 테스트 테넌트를 비워 같은 조건으로 돈다.
    TenantContext.clear();
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    // 소속 0 계정의 로그인 감사 행은 테넌트 NULL 로 남는다 — 그 스코프에서 지운 뒤 사용자를 지운다.
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        null,
        () -> dsl.execute("delete from audit_log where user_id = ?", userId));
    dsl.execute("delete from refresh_token where user_id = ?", userId);
    dsl.execute("delete from login_attempts where username = ?", username);
    TenantRlsTestSupport.deleteUser(dsl, userId);
  }

  private ResultActions login(String password) throws Exception {
    return mockMvc.perform(
        post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"));
  }

  /** 기존 사용자가 원래 100자 비밀번호를 그대로 입력하면 로그인된다 — 로그인 입력에는 길이 상한이 없고 500 도 나지 않는다. */
  @Test
  void existingUserWithLongPassword_logsInWithOriginalPassword() throws Exception {
    login(LONG_PASSWORD).andExpect(status().isOk());
  }

  /** 틀린 긴 비밀번호(앞 72바이트가 다름)는 평범한 401 — 길이 때문에 500 으로 새지 않는다. */
  @Test
  void wrongLongPassword_returns401NotServerError() throws Exception {
    login("Wrong1" + "q".repeat(94)).andExpect(status().isUnauthorized());
  }
}
