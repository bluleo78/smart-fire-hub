package com.smartfirehub.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.support.IntegrationTestBase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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
   * 플랫폼 롤을 가진 검증용 사용자를 만든다.
   *
   * <p>test DB 의 {@code platform_user_role} 은 0행이므로 픽스처를 직접 만든다. 공유 DB 라
   * username/email 은 나노초로 유일화한다.
   */
  private long platformUser() {
    String uniq = "p7a-iso-" + System.nanoTime();
    long userId =
        dsl.insertInto(table(name("user")))
            .set(field(name("username")), uniq)
            .set(field(name("email")), uniq + "@example.com")
            .set(field(name("password")), "{noop}x")
            .set(field(name("name")), uniq)
            .returning(field(name("id"), Long.class))
            .fetchOne()
            .get(field(name("id"), Long.class));
    dsl.execute(
        "insert into platform_user_role (user_id, platform_role_id)"
            + " select ?, id from platform_role where name = 'SUPER_ADMIN'"
            + " on conflict do nothing",
        userId);
    return userId;
  }
}
