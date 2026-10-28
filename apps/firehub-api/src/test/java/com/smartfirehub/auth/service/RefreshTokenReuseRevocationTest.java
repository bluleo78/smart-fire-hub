package com.smartfirehub.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.auth.dto.LoginRequest;
import com.smartfirehub.auth.exception.InvalidTokenException;
import com.smartfirehub.auth.repository.RefreshTokenRepository;
import com.smartfirehub.platform.service.PlatformAuthService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import java.util.function.UnaryOperator;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 리프레시 토큰 재사용 탐지의 패밀리 폐기가 <b>실제로 커밋되는지</b> 두 평면에서 검증한다(WD-268).
 *
 * <p>클래스에 {@code @Transactional} 을 붙이지 않는 것이 이 테스트의 핵심이다. 테스트 전체를 한 트랜잭션으로 감싸면 갱신 호출이 그 트랜잭션에 참여해,
 * 재사용 예외로 서비스 트랜잭션이 롤백되는 운영 상황이 재현되지 않는다 — 예전 {@code AuthServiceTest} 의 같은 테스트가 버그를 가리고 초록이었던 이유다.
 * 여기서는 로그인·갱신이 각자 커밋되므로 운영과 같은 경계에서 판정한다.
 *
 * <p>공격 시나리오: 정상 사용자가 A 로 갱신해 B 를 받은 뒤, 탈취한 A 가 다시 쓰이면 패밀리 전체가 폐기돼 B 로도 갱신할 수 없어야 한다.
 */
class RefreshTokenReuseRevocationTest extends IntegrationTestBase {

  private static final String PASSWORD = "Wd268Passw0rd!";
  private static final String REUSE_MESSAGE = "이미 사용된 토큰입니다. 다시 로그인해 주세요.";

  @Autowired private AuthService authService;
  @Autowired private PlatformAuthService platformAuthService;
  @Autowired private RefreshTokenRepository refreshTokenRepository;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder passwordEncoder;

  private Long tenantUserId;
  private Long platformUserId;

  @AfterEach
  void cleanup() {
    if (tenantUserId != null) {
      TestUsers.cleanup(dsl, fixtureTransactionTemplate, tenantUserId, DEFAULT_TEST_TENANT_ID);
    }
    if (platformUserId != null) {
      TestUsers.cleanup(dsl, fixtureTransactionTemplate, platformUserId);
    }
  }

  /** 테넌트 평면({@code /api/v1/auth}): 재사용 뒤 최신 토큰도 폐기된 채 남는다. */
  @Test
  void tenantPlane_reuseRevokesLatestTokenOfFamily() {
    String username = createTenantUser();
    String first = authService.login(new LoginRequest(username, PASSWORD)).refreshToken();

    assertReuseRevokesFamily(first, token -> authService.refresh(token).refreshToken());
  }

  /** 운영자 평면({@code /api/platform/auth}): 같은 회전 기계를 쓰므로 같은 보장이 있어야 한다. */
  @Test
  void platformPlane_reuseRevokesLatestTokenOfFamily() {
    String username = "wd268-platform-" + System.nanoTime();
    platformUserId =
        TenantRlsTestSupport.insertUserWithPassword(
            dsl, username, passwordEncoder.encode(PASSWORD));
    TenantRlsTestSupport.grantPlatformSuperAdmin(dsl, platformUserId);
    String first = platformAuthService.login(new LoginRequest(username, PASSWORD)).refreshToken();

    assertReuseRevokesFamily(first, token -> platformAuthService.refresh(token).refreshToken());
  }

  /**
   * 회전 폐기는 한 번만 성공한다. 같은 토큰으로 동시에 들어온 두 갱신이 모두 검사를 통과해도, 늦은 쪽의 폐기 UPDATE 는 0행이어서 회전이 거부된다 — 한 토큰에서
   * 살아 있는 가지가 둘 생기지 않게 하는 근거다.
   */
  @Test
  void revokeByTokenHash_succeedsOnlyOnce() {
    String username = createTenantUser();
    String hash =
        RefreshTokenHasher.hash(
            authService.login(new LoginRequest(username, PASSWORD)).refreshToken());

    assertThat(refreshTokenRepository.revokeByTokenHash(hash)).isTrue();
    assertThat(refreshTokenRepository.revokeByTokenHash(hash)).isFalse();
  }

  /**
   * A → B 로 정상 회전한 뒤 A 를 재사용하면 401(예외)이고, 그 뒤 B 도 저장소에서 폐기돼 있으며 갱신이 거부되는지 본다.
   *
   * <p>B 의 폐기 여부를 저장소에서 먼저 확인하는 이유: B 로 갱신을 시도하는 단언만 두면, 폐기가 롤백됐더라도 B 갱신이 <b>성공</b>해 버려 실패 원인이 "패밀리
   * 폐기 롤백"이라는 것이 메시지로 드러나지 않는다.
   */
  private void assertReuseRevokesFamily(String first, UnaryOperator<String> refresh) {
    String second = refresh.apply(first);

    assertThatThrownBy(() -> refresh.apply(first))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessage(REUSE_MESSAGE);

    assertThat(refreshTokenRepository.isTokenRevoked(RefreshTokenHasher.hash(second)))
        .as("재사용 탐지 뒤 최신 토큰도 폐기돼 있어야 한다 — false 면 패밀리 폐기가 예외와 함께 롤백된 것이다")
        .isTrue();
    assertThatThrownBy(() -> refresh.apply(second))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessage(REUSE_MESSAGE);
  }

  /** 기본 테넌트의 ACTIVE 멤버를 만들고(정리용 id 기록) username 을 돌려준다. 공유 test DB 라 나노초로 유일화한다. */
  private String createTenantUser() {
    String username = "wd268-tenant-" + System.nanoTime();
    tenantUserId =
        TestUsers.createMember(
                dsl,
                fixtureTransactionTemplate,
                passwordEncoder,
                username,
                username + "@example.com",
                PASSWORD,
                username,
                DEFAULT_TEST_TENANT_ID)
            .id();
    return username;
  }
}
