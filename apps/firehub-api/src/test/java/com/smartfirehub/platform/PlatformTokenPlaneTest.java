package com.smartfirehub.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.platform.repository.PlatformRoleRepository;
import com.smartfirehub.support.IntegrationTestBase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** 플랫폼 토큰이 테넌트 클레임을 갖지 않고, 플랫폼 권한이 별도 경로로 로딩되는지 검증한다. */
@AutoConfigureMockMvc
class PlatformTokenPlaneTest extends IntegrationTestBase {

  @Autowired private JwtTokenProvider jwtTokenProvider;
  @Autowired private PlatformRoleRepository platformRoleRepository;
  @Autowired private DSLContext dsl;
  @Autowired private MockMvc mockMvc;

  /**
   * 플랫폼 토큰에는 tenant 클레임이 없어야 한다.
   *
   * <p>왜 중요한가: tenant 클레임이 실리면 JwtAuthenticationFilter 가 TenantContext 를 세우고, 운영자
   * 요청이 특정 테넌트의 RLS 안에서 실행된다. 운영자 평면은 전역 테이블만 만지므로 컨텍스트가 비어
   * 있어야 하고, 비어 있음이 곧 fail-closed 다.
   */
  @Test
  void platformTokenCarriesPlatformFlagAndNoTenant() {
    String token = jwtTokenProvider.generatePlatformAccessToken(7L, "ops");

    var principal = jwtTokenProvider.parseAccessToken(token).orElseThrow();
    assertThat(principal.userId()).isEqualTo(7L);
    assertThat(principal.tenantId()).isNull();
    assertThat(principal.platform()).isTrue();
  }

  /** 테넌트 토큰은 platform=false 로 파싱돼야 한다(기본값이 true 로 새면 평면 가드가 무력화된다). */
  @Test
  void tenantTokenIsNotPlatform() {
    String token = jwtTokenProvider.generateAccessToken(7L, "user", 1L);

    var principal = jwtTokenProvider.parseAccessToken(token).orElseThrow();
    assertThat(principal.tenantId()).isEqualTo(1L);
    assertThat(principal.platform()).isFalse();
  }

  /** 리프레시 토큰의 평면도 판별 가능해야 한다 — 평면이 다른 리프레시로 갈아타는 것을 막는 데 쓴다. */
  @Test
  void refreshTokenPlaneIsDistinguishable() {
    assertThat(jwtTokenProvider.isPlatformToken(jwtTokenProvider.generatePlatformRefreshToken(7L)))
        .isTrue();
    assertThat(jwtTokenProvider.isPlatformToken(jwtTokenProvider.generateRefreshToken(7L, 1L)))
        .isFalse();
    // 서명이 깨진 토큰은 예외가 아니라 false — 호출처가 평면 불일치로 처리한다.
    assertThat(jwtTokenProvider.isPlatformToken("not-a-token")).isFalse();
  }

  /**
   * 플랫폼 권한은 platform_user_role 경로로만 조회된다.
   *
   * <p>test DB 의 platform_user_role 은 0행이므로 이 테스트가 자기 픽스처를 만든다.
   */
  @Test
  void platformPermissionsLoadFromPlatformPlane() {
    long userId = createUserWithSuperAdmin();

    assertThat(platformRoleRepository.hasAnyPlatformRole(userId)).isTrue();
    assertThat(platformRoleRepository.findPlatformPermissionCodes(userId))
        .contains("platform:tenant:create", "platform:settings:write");
  }

  /** 플랫폼 롤이 없는 사용자는 빈 집합을 받는다(예외가 아니라 빈 집합 — 호출처가 403 으로 처리한다). */
  @Test
  void nonPlatformUserGetsEmptySet() {
    long userId = createPlainUser();

    assertThat(platformRoleRepository.hasAnyPlatformRole(userId)).isFalse();
    assertThat(platformRoleRepository.findPlatformPermissionCodes(userId)).isEmpty();
  }

  /**
   * 필터의 평면 분기가 실제로 인증을 세우는지 요청 경로로 검증한다.
   *
   * <p>왜 단위 검증만으로 부족한가: 위 테스트들은 토큰 발급기와 리포지토리만 만진다. 필터가 platform
   * 클레임을 보고 {@code setPlatformSecurityContext} 로 갈라지지 않으면(그리고 그 경로가 테넌트 권한
   * 조회를 타면) 아무 단위 테스트도 깨지지 않는다. 여기서 401 이 아니게 되는 것이 곧 "tenant 클레임
   * 없이도 인증이 섰다"는 증거다. 경로가 아직 없으므로 404 가 정상 — Task 4/5 가 컨트롤러를 붙인다.
   */
  @Test
  void platformTokenAuthenticatesWithoutTenantClaim() throws Exception {
    long userId = createUserWithSuperAdmin();
    String token = jwtTokenProvider.generatePlatformAccessToken(userId, "ops");

    mockMvc
        .perform(get("/api/platform/does-not-exist").header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());
  }

  /** 플랫폼 SUPER_ADMIN 을 가진 사용자를 만든다. test DB 는 platform_user_role 이 0행이다. */
  private long createUserWithSuperAdmin() {
    long userId = createPlainUser();
    dsl.execute(
        "insert into platform_user_role (user_id, platform_role_id)"
            + " select ?, id from platform_role where name = 'SUPER_ADMIN'"
            + " on conflict do nothing",
        userId);
    return userId;
  }

  /** 전역 "user" 테이블에 검증 전용 사용자를 만든다. username/email 은 나노초로 유일화한다. */
  private long createPlainUser() {
    String uniq = "p7a-" + System.nanoTime();
    return dsl.insertInto(table(name("user")))
        .set(field(name("username")), uniq)
        .set(field(name("email")), uniq + "@example.com")
        .set(field(name("password")), "{noop}x")
        .set(field(name("name")), uniq)
        .returning(field(name("id"), Long.class))
        .fetchOne()
        .get(field(name("id"), Long.class));
  }
}
