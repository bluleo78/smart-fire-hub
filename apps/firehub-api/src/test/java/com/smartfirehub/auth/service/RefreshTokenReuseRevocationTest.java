package com.smartfirehub.auth.service;

import static com.smartfirehub.jooq.Tables.REFRESH_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.auth.dto.LoginRequest;
import com.smartfirehub.auth.exception.InvalidTokenException;
import com.smartfirehub.auth.repository.RefreshTokenRepository;
import com.smartfirehub.platform.service.PlatformAuthService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.TimeZone;
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
  /** 유예 창 안에 재제시됐을 때 받는 응답 — 재사용(탈취) 메시지와 다르게 해 유예/탐취를 시험에서 가른다(WD-1141). */
  private static final String INVALID_MESSAGE = "만료되었거나 폐기된 토큰입니다. 다시 로그인해 주세요.";

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
   * 회전을 커밋한 뒤 도착한 진 탭의 재제시(WD-1141)는 유예 창 안이라 패밀리를 폐기하지 않는다 — 이긴 탭의 세션은 살아 있고, 진 탭은 401 을 받아
   * 클라이언트 재확인으로 이긴 탭의 새 쿠키를 다시 보낸다. 유예 중에도 새 토큰은 주지 않는다.
   */
  @Test
  void tenantPlane_reuseWithinGrace_keepsFamilyAlive() {
    String username = createTenantUser();
    UnaryOperator<String> refresh = token -> authService.refresh(token).refreshToken();
    String first = authService.login(new LoginRequest(username, PASSWORD)).refreshToken();
    String second = refresh.apply(first);
    backdateRevokedAt(first, 4);

    assertThatThrownBy(() -> refresh.apply(first))
        .isInstanceOf(InvalidTokenException.class)
        .as("유예 경로는 재사용(탈취)이 아니라 일반 401 로 끝나야 한다")
        .hasMessage(INVALID_MESSAGE);

    assertThat(refreshTokenRepository.isTokenRevoked(RefreshTokenHasher.hash(second)))
        .as("유예 경로는 패밀리를 폐기하면 안 된다 — 이긴 탭까지 로그아웃되기 때문")
        .isFalse();
    assertThat(refresh.apply(second)).as("이긴 탭은 그대로 갱신한다").isNotBlank();
  }

  /** 유예 창을 넘긴 재제시는 오늘처럼 탈취로 보아 패밀리 전체를 폐기한다 — 유예가 탈취 탐지를 무력화하지 않게. */
  @Test
  void tenantPlane_reuseAfterGrace_revokesFamily() {
    String username = createTenantUser();
    UnaryOperator<String> refresh = token -> authService.refresh(token).refreshToken();
    String first = authService.login(new LoginRequest(username, PASSWORD)).refreshToken();
    String second = refresh.apply(first);
    backdateRevokedAt(first, 6);

    assertThatThrownBy(() -> refresh.apply(first))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessage(REUSE_MESSAGE);
    assertThat(refreshTokenRepository.isTokenRevoked(RefreshTokenHasher.hash(second)))
        .as("유예 밖 재제시는 패밀리 전체를 폐기한다")
        .isTrue();
    // 패밀리 폐기는 방금 일어나 유예에 걸리므로, 유예 밖 재제시로 최신 토큰도 못 쓴다는 것을 확인한다.
    backdateRevokedAt(second, 6);
    assertThatThrownBy(() -> refresh.apply(second))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessage(REUSE_MESSAGE);
  }

  /**
   * 폐기 시각이 미래로 찍혀 있으면 유예로 세지 않는다 — 유예의 <b>위쪽 한도</b> (WD-1141 반려 3차 5).
   *
   * <p>유예는 "방금 전에 폐기됐는지"만 묻는 창이라 미래 값은 애초에 관용 대상이 아니다. 아래 한도가 없으면 시계가 어긋난 노드나 잘못 적인 값 하나로
   * 유예 밖 재사용(탈취)까지 무한히 관용해 패밀리 폐기가 영원히 미뤄진다.
   */
  @Test
  void tenantPlane_reuseWithFutureRevokedAt_revokesFamily() {
    String username = createTenantUser();
    UnaryOperator<String> refresh = token -> authService.refresh(token).refreshToken();
    String first = authService.login(new LoginRequest(username, PASSWORD)).refreshToken();
    String second = refresh.apply(first);
    setRevokedAtOffsetSeconds(first, 60);

    assertThatThrownBy(() -> refresh.apply(first))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessage(REUSE_MESSAGE);
    assertThat(refreshTokenRepository.isTokenRevoked(RefreshTokenHasher.hash(second)))
        .as("미래로 찍힌 폐기 시각은 유예에 걸리지 않는다")
        .isTrue();
  }

  /**
   * 마이그레이션 이전에 폐기된 행은 {@code revoked_at} 이 비어 있다 — 유예로 오인해 오래된 재제시를 놓치면 안 되므로 패밀리 폐기를 그대로 한다.
   */
  @Test
  void tenantPlane_reuseWithNullRevokedAt_revokesFamily() {
    String username = createTenantUser();
    UnaryOperator<String> refresh = token -> authService.refresh(token).refreshToken();
    String first = authService.login(new LoginRequest(username, PASSWORD)).refreshToken();
    String second = refresh.apply(first);
    clearRevokedAt(first);

    assertThatThrownBy(() -> refresh.apply(first))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessage(REUSE_MESSAGE);
    assertThat(refreshTokenRepository.isTokenRevoked(RefreshTokenHasher.hash(second)))
        .as("폐기 시각이 없는 행은 유예 없음으로 본다")
        .isTrue();
  }

  /** 운영자 평면은 같은 회전 기계({@link RefreshTokenRotation})를 쓰므로 같은 유예를 받아야 한다. */
  @Test
  void platformPlane_reuseWithinGrace_keepsFamilyAlive() {
    String username = "wd268-platform-" + System.nanoTime();
    platformUserId =
        TenantRlsTestSupport.insertUserWithPassword(
            dsl, username, passwordEncoder.encode(PASSWORD));
    TenantRlsTestSupport.grantPlatformSuperAdmin(dsl, platformUserId);
    UnaryOperator<String> refresh = token -> platformAuthService.refresh(token).refreshToken();
    String first = platformAuthService.login(new LoginRequest(username, PASSWORD)).refreshToken();
    String second = refresh.apply(first);
    backdateRevokedAt(first, 4);

    assertThatThrownBy(() -> refresh.apply(first))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessage(INVALID_MESSAGE);
    assertThat(refreshTokenRepository.isTokenRevoked(RefreshTokenHasher.hash(second)))
        .as("운영자 평면도 유예 경로는 패밀리를 폐기하지 않는다")
        .isFalse();
    assertThat(refresh.apply(second)).isNotBlank();
  }

  /** 회전이 폐기한 토큰에는 유예 판정이 쓸 폐기 시각이 함께 찍혀야 한다 — 안 찍히면 유예는 영원히 동작하지 않는다. */
  @Test
  void revokeByTokenHash_setsRevokedAt() {
    String username = createTenantUser();
    String hash =
        RefreshTokenHasher.hash(
            authService.login(new LoginRequest(username, PASSWORD)).refreshToken());

    assertThat(refreshTokenRepository.findRevokedAt(hash)).as("폐기 전에는 비어 있다").isEmpty();

    assertThat(refreshTokenRepository.revokeByTokenHash(hash)).isTrue();

    assertThat(refreshTokenRepository.findRevokedAt(hash))
        .as("폐기하면 시각이 함께 찍혀야 유예 판정이 된다")
        .isPresent();
  }

  /**
   * 유예 판정 시계({@link RefreshTokenRepository#revokedAtNow()})가 JVM 기본 시간대와 무관하게 UTC 벽시계로 읽히는지 고정한다(WD-1141 반려 3차 5).
   *
   * <p>기본 시간대를 UTC+9 로 바꿔 놓고도 UTC 와 5초 안에 맞아야 한다 — {@code LocalDateTime.now()} 로 돌아가면 9시간이 어긋나 유예 창 전체가 그만큼
   * 벌어진다. 시험 환경의 기본 시간대가 UTC 라면 원래 구현도 같은 값을 내어 이 시험이 회귀를 잡지 못하므로, 여기서 일부러 어긋나게 만든다.
   */
  @Test
  void revokedAtNow_ignoresJvmDefaultTimeZone() {
    TimeZone original = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
    try {
      assertThat(
              Duration.between(
                      RefreshTokenRepository.revokedAtNow(), LocalDateTime.now(ZoneOffset.UTC))
                  .abs())
          .as("판정 시계는 JVM 기본 시간대가 아니라 UTC 벽시계여야 한다")
          .isLessThan(Duration.ofSeconds(5));
    } finally {
      TimeZone.setDefault(original);
    }
  }

  /** 이미 폐기된 토큰의 {@code revoked_at} 을 과거 시각으로 되돌려 유예 안/밖을 재현한다(WD-1141). */
  private void backdateRevokedAt(String refreshToken, int secondsAgo) {
    setRevokedAtOffsetSeconds(refreshToken, -secondsAgo);
  }

  /**
   * 폐기 시각을 유예 판정 시계({@link RefreshTokenRepository#revokedAtNow()})에서 {@code offsetSeconds} 만큼 밀어 놓는다(WD-1141).
   *
   * <p>음수는 유예 안/밖, 양수는 유예의 위쪽 한도를 재현한다. 기준을 JVM 기본 시간대({@code LocalDateTime.now()})로 두면 판정 시계와 어긋나
   * 유예 안/반이 뒤집힌다(WD-1141 반려 3차 5).
   */
  private void setRevokedAtOffsetSeconds(String refreshToken, long offsetSeconds) {
    dsl.update(REFRESH_TOKEN)
        .set(
            REFRESH_TOKEN.REVOKED_AT, RefreshTokenRepository.revokedAtNow().plusSeconds(offsetSeconds))
        .where(REFRESH_TOKEN.TOKEN_HASH.eq(RefreshTokenHasher.hash(refreshToken)))
        .execute();
  }

  /** 마이그레이션 이전 행처럼 폐기 시각을 비운다 — 유예 없음으로 봐야 하는 경우. */
  private void clearRevokedAt(String refreshToken) {
    dsl.update(REFRESH_TOKEN)
        .set(REFRESH_TOKEN.REVOKED_AT, (LocalDateTime) null)
        .where(REFRESH_TOKEN.TOKEN_HASH.eq(RefreshTokenHasher.hash(refreshToken)))
        .execute();
  }

  /**
   * A → B 로 정상 회전한 뒤 A 를 재사용하면 401(예외)이고, 그 뒤 B 도 저장소에서 폐기돼 있으며 갱신이 거부되는지 본다.
   *
   * <p>B 의 폐기 여부를 저장소에서 먼저 확인하는 이유: B 로 갱신을 시도하는 단언만 두면, 폐기가 롤백됐더라도 B 갱신이 <b>성공</b>해 버려 실패 원인이 "패밀리
   * 폐기 롤백"이라는 것이 메시지로 드러나지 않는다.
   */
  private void assertReuseRevokesFamily(String first, UnaryOperator<String> refresh) {
    String second = refresh.apply(first);

    // 회전 직후 5초는 회전 유예(WD-1141)라 탈취로 잡지 않는다 — WD-268 의 탈취 탐지는 유예 밖에서 검증한다.
    backdateRevokedAt(first, 6);
    assertThatThrownBy(() -> refresh.apply(first))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessage(REUSE_MESSAGE);

    assertThat(refreshTokenRepository.isTokenRevoked(RefreshTokenHasher.hash(second)))
        .as("재사용 탐지 뒤 최신 토큰도 폐기돼 있어야 한다 — false 면 패밀리 폐기가 예외와 함께 롤백된 것이다")
        .isTrue();
    // 패밀리는 방금 폐기돼 유예에 걸리므로, 유예 밖 재제시로 판정을 다시 확인한다.
    backdateRevokedAt(second, 6);
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
