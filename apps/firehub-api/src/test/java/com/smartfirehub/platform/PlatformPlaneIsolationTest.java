package com.smartfirehub.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.auth.repository.RefreshTokenRepository;
import com.smartfirehub.auth.service.RefreshTokenHasher;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.security.PlatformPlaneFilter;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 두 평면이 서로의 경로에 도달할 수 없음을 검증한다(설계서 §4).
 *
 * <p>여기서 막는 것은 권한이 아니라 <b>평면</b>이다. 권한으로 막으면 두 방향 모두 새는데, (1)
 * {@code permission} 카탈로그가 전역 공유라 테넌트 롤에 {@code platform:*} 을 부여하는 것이 가능하고,
 * (2) 플랫폼 토큰은 권한을 요구하지 않는 테넌트 엔드포인트를 그냥 통과한다.
 */
@AutoConfigureMockMvc
class PlatformPlaneIsolationTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtTokenProvider jwtTokenProvider;
  @Autowired private DSLContext dsl;
  @Autowired private RefreshTokenRepository refreshTokenRepository;

  /**
   * 테넌트 토큰으로는 운영자 평면에 도달할 수 없다.
   *
   * <p>오늘은 테넌트 롤에 {@code platform:*} 권한이 없어 권한 검사만으로도 막히지만, 누군가 그 권한을
   * 테넌트 롤에 붙이는 순간 뚫린다 — 그래서 평면으로 막고, 그것을 여기서 단언한다.
   */
  @Test
  void tenantTokenCannotReachPlatformPlane() throws Exception {
    String tenantToken = jwtTokenProvider.generateAccessToken(1L, "user", 1L);

    mockMvc
        .perform(get("/api/platform/tenants").header("Authorization", "Bearer " + tenantToken))
        .andExpect(status().isForbidden());
  }

  /**
   * 플랫폼 토큰으로는 테넌트 도메인 API 에 도달할 수 없다.
   *
   * <p>권한 집합이 비어 있어 대부분 403 이 되긴 하지만, 권한을 요구하지 않는 엔드포인트까지 막으려면
   * 평면 판정이 필요하다 — 아래 {@code /api/v1/auth/me} 케이스가 그것을 잡는다.
   */
  @Test
  void platformTokenCannotReachTenantDomainApi() throws Exception {
    String platformToken = jwtTokenProvider.generatePlatformAccessToken(platformUser(), "ops");

    mockMvc
        .perform(get("/api/v1/datasets").header("Authorization", "Bearer " + platformToken))
        .andExpect(status().isForbidden());
  }

  /**
   * 권한을 요구하지 않는 테넌트 엔드포인트도 플랫폼 토큰을 거부한다.
   *
   * <p>이것이 "권한이 아니라 평면으로 막는다"의 핵심 근거다. {@code /api/v1/auth/me} 에는
   * {@code @RequirePermission} 이 없으므로 권한 집합이 비어 있어도 통과한다 — 평면 필터가 없으면
   * 운영자 토큰으로 테넌트 사용자 정보를 읽을 수 있다.
   */
  @Test
  void platformTokenCannotReachPermissionlessTenantEndpoint() throws Exception {
    String platformToken = jwtTokenProvider.generatePlatformAccessToken(platformUser(), "ops");

    mockMvc
        .perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + platformToken))
        .andExpect(status().isForbidden());
  }

  /**
   * 로그인·갱신 경로는 평면 검사에서 제외된다.
   *
   * <p>같은 호스트에서 firehub-web 을 열어 둔 브라우저는 살아 있는 테넌트 Bearer 를 함께 보낸다.
   * 이때 평면 불일치로 403 을 주면 운영자가 로그인 자체를 못 한다 — 실제로 밟게 되는 경로다.
   * 자격증명이 없으니 4xx 이지만 <b>403(평면 불일치)이어서는 안 된다</b>.
   */
  @Test
  void platformAuthPathsAreExemptFromPlaneCheck() throws Exception {
    String tenantToken = jwtTokenProvider.generateAccessToken(1L, "user", 1L);

    int status =
        mockMvc
            .perform(
                post("/api/platform/auth/login")
                    .header("Authorization", "Bearer " + tenantToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"nobody\",\"password\":\"x\"}"))
            .andReturn()
            .getResponse()
            .getStatus();

    assertThat(status).isNotEqualTo(403);
  }

  /**
   * 평면이 일치하는 정상 요청은 필터가 통과시킨다.
   *
   * <p>가드가 모든 것을 막아 버리는 방식으로 "통과"하지 않는다는 반대 방향 증거다. 경로가 아직
   * 없으므로 404 이며, 403(평면 차단)이 아닌 것이 확인 대상이다.
   */
  @Test
  void matchingPlaneIsNotBlocked() throws Exception {
    String platformToken = jwtTokenProvider.generatePlatformAccessToken(platformUser(), "ops");

    mockMvc
        .perform(
            get("/api/platform/does-not-exist").header("Authorization", "Bearer " + platformToken))
        .andExpect(status().isNotFound());
  }

  /**
   * 퍼센트 인코딩으로 평면 판정을 속일 수 없다.
   *
   * <p>{@code getRequestURI()} 는 디코딩되지 않은 원본이고 {@code SecurityConfig}·MVC 는 디코딩된
   * 경로를 본다. 그래서 {@code /api/%70latform/tenants}({@code %70}={@code p})는 필터에게만 "플랫폼
   * 경로가 아님"으로 보여 평면 검사를 건너뛰고 운영자 컨트롤러에 도달할 수 있었다. StrictHttpFirewall
   * 은 {@code %2F}·{@code ..}·{@code ;} 는 막지만 인코딩된 <b>일반 문자</b>는 막지 않는다.
   *
   * <p>필터를 직접 호출하는 이유: MockMvc 는 URI 를 정규화할 수 있어 Tomcat 의 실제 동작(원본 URI 와
   * 디코딩된 servletPath 가 다르다)을 재현하지 못한다. 여기서는 그 둘을 명시적으로 갈라 놓고,
   * 체인이 <b>호출되지 않았음</b>을 단언한다 — 상태코드만 보면 공허해지는 자리다.
   */
  @Test
  void percentEncodedPlatformPathIsStillPlaneChecked() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/%70latform/tenants");
    // Tomcat 재현: 원본 URI 는 인코딩된 채, servletPath 는 디코딩된 채 온다.
    request.setRequestURI("/api/%70latform/tenants");
    request.setServletPath("/api/platform/tenants");
    MockHttpServletResponse response = new MockHttpServletResponse();

    // 테넌트 평면 인증을 심는다 — 이것이 운영자 경로에 닿으면 안 된다.
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(1L, null, List.of()));
    AtomicBoolean chainInvoked = new AtomicBoolean(false);
    try {
      new PlatformPlaneFilter()
          .doFilter(request, response, (req, res) -> chainInvoked.set(true));
    } finally {
      SecurityContextHolder.clearContext();
    }

    assertThat(chainInvoked).isFalse();
    assertThat(response.getStatus()).isEqualTo(403);
  }

  /**
   * <b>유효하게 저장된</b> 플랫폼 리프레시 토큰으로 테넌트 갱신 경로를 탈 수 없다.
   *
   * <p>{@code refresh_token} 은 전역 테이블이라 두 평면이 같은 회전·재사용 탐지 기계를 공유한다.
   * 그래서 저장·서명 모두 유효한 운영자 리프레시 토큰을 {@code /api/v1/auth/refresh} 에 넣으면,
   * 평면 검사가 없는 한 <b>200 과 함께 테넌트 액세스 토큰이 나온다</b> — 평면 표식이 조용히 사라진다.
   * 토큰을 실제로 저장해 두는 이유가 이것이다. 저장하지 않으면 가드를 지워도 "없는 토큰" 으로 같은
   * 401 이 나와 단언이 공허해진다.
   */
  @Test
  void platformRefreshTokenCannotRefreshOnTenantPlane() throws Exception {
    long userId = platformUser();
    String platformRefresh = jwtTokenProvider.generatePlatformRefreshToken(userId);
    refreshTokenRepository.save(
        userId,
        RefreshTokenHasher.hash(platformRefresh),
        LocalDateTime.now().plusDays(1),
        UUID.randomUUID());

    String body =
        mockMvc
            .perform(post("/api/v1/auth/refresh").cookie(new Cookie("refreshToken", platformRefresh)))
            .andExpect(status().is4xxClientError())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain("accessToken");
  }

  /**
   * 플랫폼 롤을 가진 검증용 사용자를 만든다.
   *
   * <p>test DB 의 {@code platform_user_role} 은 0행이므로 픽스처를 직접 만든다. 공유 DB 라
   * username/email 은 나노초로 유일화한다.
   */
  private long platformUser() {
    long userId =
        TenantRlsTestSupport.insertUserWithPassword(
            dsl, "p7a-iso-" + System.nanoTime(), "{noop}x");
    TenantRlsTestSupport.grantPlatformSuperAdmin(dsl, userId);
    return userId;
  }
}
