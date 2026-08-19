package com.smartfirehub.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.auth.repository.RefreshTokenRepository;
import com.smartfirehub.auth.service.RefreshTokenHasher;
import com.smartfirehub.support.IntegrationTestBase;
import jakarta.servlet.http.Cookie;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 운영자 로그인의 두 계약을 검증한다: (1) 플랫폼 롤이 없으면 자격증명이 맞아도 거부하고 그 응답이
 * 자격증명 오류와 <b>구분되지 않는다</b>, (2) 리프레시 쿠키가 테넌트 평면과 이름·path 모두 분리된다.
 */
@AutoConfigureMockMvc
class PlatformAuthControllerTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private RefreshTokenRepository refreshTokenRepository;

  private static final String PASSWORD = "P7aOpsPassw0rd!";

  /**
   * 운영자 로그아웃은 <b>테넌트 세션을 끊지 않는다</b>.
   *
   * <p>{@code refresh_token} 은 전역 테이블이고 평면 컬럼이 없다. 그래서 사용자 단위 폐기
   * ({@code revokeAllByUserId})를 쓰면 운영자이면서 일반 사용자인 계정이 콘솔에서 로그아웃할 때
   * firehub-web 세션까지 끊긴다 — 쿠키를 평면별로 분리해 세션이 서로를 끊지 않게 만든 설계를
   * 로그아웃이 되돌리는 셈이다. 여기서는 같은 사용자로 두 평면에 로그인한 뒤 운영자만 로그아웃하고,
   * <b>테넌트 리프레시가 여전히 동작하는지</b>로 판정한다(폐기 여부를 상태코드로 직접 본다).
   */
  @Test
  void platformLogoutDoesNotKillTenantSession() throws Exception {
    String username = createUser(true);

    String tenantRefresh = cookieValue(tenantLogin(username), "refreshToken");
    MockHttpServletResponse platformLogin = login(username, PASSWORD);
    String platformRefresh = cookieValue(platformLogin, "platformRefreshToken");
    String platformAccess = accessToken(platformLogin);

    mockMvc
        .perform(
            post("/api/platform/auth/logout")
                .header("Authorization", "Bearer " + platformAccess)
                .cookie(new Cookie("platformRefreshToken", platformRefresh)))
        .andExpect(status().isNoContent());

    // 폐기 여부를 저장소에서 직접 본다. 테넌트 갱신 엔드포인트로 판정하면 멤버십 상태 같은
    // 무관한 규칙(테넌트 미선택 등)이 섞여 무엇을 검증하는지 흐려진다.
    assertThat(refreshTokenRepository.isTokenRevoked(RefreshTokenHasher.hash(platformRefresh)))
        .as("운영자 세션은 끊겼어야 한다")
        .isTrue();
    assertThat(refreshTokenRepository.isTokenRevoked(RefreshTokenHasher.hash(tenantRefresh)))
        .as("테넌트 세션은 살아 있어야 한다 — 사용자 단위 폐기였다면 이것도 true 가 된다")
        .isFalse();
  }

  /** 테넌트 평면 로그인(발급기 직접 호출이 아니라 실제 로그인). 두 평면 동시 로그인 상태를 만든다. */
  private MockHttpServletResponse tenantLogin(String username) throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD)))
        .andReturn()
        .getResponse();
  }

  private String cookieValue(MockHttpServletResponse response, String name) {
    Cookie cookie = response.getCookie(name);
    assertThat(cookie).as("쿠키 %s 가 없다", name).isNotNull();
    return cookie.getValue();
  }

  private String accessToken(MockHttpServletResponse response) throws Exception {
    return objectMapper.readTree(response.getContentAsString()).get("accessToken").asText();
  }

  /** 플랫폼 롤 보유자는 로그인해 액세스 토큰과 보유 권한을 받는다. */
  @Test
  void login_succeedsForPlatformRoleHolder() throws Exception {
    String username = createUser(true);

    MockHttpServletResponse response = login(username, PASSWORD);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentAsString()).contains("accessToken", "platform:tenant:create");
    // 리프레시 토큰은 본문에 실리지 않는다(쿠키 전용).
    assertThat(response.getContentAsString()).contains("\"refreshToken\":null");
  }

  /**
   * 플랫폼 롤이 없는 사용자는 <b>자격증명이 맞아도</b> 거부되고, 그 응답이 비밀번호 오류와
   * 바이트 단위로 같아야 한다.
   *
   * <p>이것이 이 테스트 클래스의 핵심이다. 응답이 갈리면 "어떤 계정이 운영자인지" 를 외부에서
   * 판별할 수 있는 열거 오라클이 된다.
   */
  @Test
  void login_rejectsNonPlatformUserIndistinguishablyFromWrongPassword() throws Exception {
    String operator = createUser(true);
    String plainUser = createUser(false);

    MockHttpServletResponse wrongPassword = login(operator, "wrong-password");
    MockHttpServletResponse notAnOperator = login(plainUser, PASSWORD);

    assertThat(notAnOperator.getStatus()).isEqualTo(wrongPassword.getStatus());
    assertThat(comparableBody(notAnOperator)).isEqualTo(comparableBody(wrongPassword));
  }

  /** 존재하지 않는 계정도 같은 응답이어야 한다(계정 존재 여부 열거 방지). */
  @Test
  void login_unknownUserMatchesWrongPassword() throws Exception {
    String operator = createUser(true);

    MockHttpServletResponse wrongPassword = login(operator, "wrong-password");
    MockHttpServletResponse unknown = login("p7a-nobody-" + System.nanoTime(), PASSWORD);

    assertThat(unknown.getStatus()).isEqualTo(wrongPassword.getStatus());
    assertThat(comparableBody(unknown)).isEqualTo(comparableBody(wrongPassword));
  }

  /**
   * 열거 비교용 본문. 공통 에러 봉투의 {@code timestamp} 만 지운다.
   *
   * <p>왜 통째 비교를 하지 않는가: 봉투에는 매 응답마다 달라지는 시각이 들어 있어 그대로 비교하면
   * 항상 실패한다. 시각은 어느 계정이 운영자인지에 대해 아무것도 알려주지 않으므로 열거 정보가
   * 아니다. <b>그 외의 모든 필드</b>(status·error·message·errors·path)는 그대로 비교한다 — 거기서
   * 갈리는 순간이 곧 오라클이다.
   */
  private String comparableBody(MockHttpServletResponse response) throws Exception {
    return response.getContentAsString().replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"*\"");
  }

  /**
   * 발급된 리프레시 쿠키가 테넌트 평면과 겹치지 않는다.
   *
   * <p>이름이 같으면 같은 호스트에서 firehub-web 과 firehub-admin 을 동시에 열었을 때 나중 로그인이
   * 앞의 세션을 덮어쓴다. path 분리는 운영자 쿠키가 테넌트 API 요청에 실려 나가지 않게 한다.
   */
  @Test
  void login_setsSeparatelyScopedRefreshCookie() throws Exception {
    String username = createUser(true);

    String setCookie = login(username, PASSWORD).getHeader("Set-Cookie");

    assertThat(setCookie).isNotNull();
    assertThat(setCookie).contains("platformRefreshToken=");
    assertThat(setCookie).contains("Path=/api/platform/auth");
    assertThat(setCookie).contains("HttpOnly");
    // 테넌트 평면 쿠키 이름으로 나가면 서로의 세션을 덮어쓴다.
    assertThat(setCookie).doesNotContain("refreshToken=;");
    assertThat(setCookie).doesNotStartWith("refreshToken=");
  }

  /**
   * 테넌트 리프레시 토큰으로는 플랫폼 토큰을 받을 수 없다.
   *
   * <p>평면을 갈아타는 승격 경로를 막는다. 쿠키 이름이 분리돼 있어 브라우저가 실수로 보낼 일은
   * 없지만, 공격자는 직접 붙일 수 있다.
   */
  @Test
  void refresh_rejectsTenantRefreshToken() throws Exception {
    String tenantRefresh = cookieValue(tenantLogin(createUser(true)), "refreshToken");

    MockHttpServletResponse response =
        mockMvc
            .perform(post("/api/platform/auth/refresh").cookie(
                new Cookie("platformRefreshToken", tenantRefresh)))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(401);
  }

  /** 쿠키 없이 갱신하면 401. */
  @Test
  void refresh_withoutCookieIsUnauthorized() throws Exception {
    assertThat(mockMvc.perform(post("/api/platform/auth/refresh")).andReturn().getResponse().getStatus())
        .isEqualTo(401);
  }

  private MockHttpServletResponse login(String username, String password) throws Exception {
    return mockMvc
        .perform(
            post("/api/platform/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
        .andReturn()
        .getResponse();
  }


  /**
   * 검증용 사용자를 만든다. 공유 test DB 라 username/email 을 나노초로 유일화한다.
   *
   * @param platformRole true 면 SUPER_ADMIN 을 부여한다(test DB 의 platform_user_role 은 0행이다)
   */
  private String createUser(boolean platformRole) {
    String uniq = "p7a-auth-" + System.nanoTime();
    long userId =
        dsl.insertInto(table(name("user")))
            .set(field(name("username")), uniq)
            .set(field(name("email")), uniq + "@example.com")
            .set(field(name("password")), passwordEncoder.encode(PASSWORD))
            .set(field(name("name")), uniq)
            .returning(field(name("id"), Long.class))
            .fetchOne()
            .get(field(name("id"), Long.class));
    if (platformRole) {
      dsl.execute(
          "insert into platform_user_role (user_id, platform_role_id)"
              + " select ?, id from platform_role where name = 'SUPER_ADMIN'"
              + " on conflict do nothing",
          userId);
    }
    return uniq;
  }
}
