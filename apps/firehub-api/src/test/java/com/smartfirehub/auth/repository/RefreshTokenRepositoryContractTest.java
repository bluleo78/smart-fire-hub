package com.smartfirehub.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/**
 * RefreshTokenRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-706).
 *
 * <p>만료 비교는 JVM 시계({@code LocalDateTime.now()})라 경계는 ±1분으로 시험한다(정확히 같은 시각은 만들 수 없다). 시각은 UTC 벽시계
 * 그대로 왕복해야 한다(리뷰 포인트 5).
 */
abstract class RefreshTokenRepositoryContractTest extends AuthRepositoryContractBase {

  protected abstract RefreshTokenRepository repository();

  private String prefix;
  private long userId;
  private long otherUserId;

  @BeforeEach
  void createUsers() {
    prefix = "rt-" + UUID.randomUUID().toString().substring(0, 8) + "-";
    userId = createUser(prefix + "u1");
    otherUserId = createUser(prefix + "u2");
  }

  @AfterEach
  void cleanup() {
    fixtureUpdate("delete from refresh_token where user_id in (?, ?)", userId, otherUserId);
    deleteUser(userId);
    deleteUser(otherUserId);
    repository().deleteExpiredTokens();
  }

  private LocalDateTime future() {
    return LocalDateTime.now().plusDays(7);
  }

  @Test
  void save_thenExistsValidToken_true_andUnknownIsFalse() {
    repository().save(userId, prefix + "h1", future(), UUID.randomUUID());

    assertThat(repository().existsValidToken(prefix + "h1")).isTrue();
    assertThat(repository().existsValidToken(prefix + "unknown")).isFalse();
    assertThat(repository().isTokenRevoked(prefix + "h1")).isFalse();
    assertThat(repository().isTokenRevoked(prefix + "unknown")).isFalse();
  }

  @Test
  void expiresAt_roundTripsAsWallClock() {
    LocalDateTime at = LocalDateTime.of(2031, 3, 4, 5, 6, 7, 123_456_000);
    repository().save(userId, prefix + "rt", at, UUID.randomUUID());

    assertThat(
            fixtureLong(
                "select count(*) from refresh_token where token_hash = ? and expires_at = ?",
                prefix + "rt",
                at))
        .isEqualTo(1);
  }

  @Test
  void existsValidToken_expiredIsFalse_andJustBeforeExpiryIsTrue() {
    repository()
        .save(userId, prefix + "live", LocalDateTime.now().plusMinutes(1), UUID.randomUUID());
    repository()
        .save(userId, prefix + "gone", LocalDateTime.now().minusMinutes(1), UUID.randomUUID());

    assertThat(repository().existsValidToken(prefix + "live")).isTrue();
    assertThat(repository().existsValidToken(prefix + "gone")).isFalse();
  }

  @Test
  void findFamilyIdByTokenHash_returnsSavedFamily_orEmpty() {
    UUID family = UUID.randomUUID();
    repository().save(userId, prefix + "f1", future(), family);

    assertThat(repository().findFamilyIdByTokenHash(prefix + "f1")).contains(family);
    assertThat(repository().findFamilyIdByTokenHash(prefix + "none")).isEmpty();
  }

  @Test
  void revokeByTokenHash_firstTrue_thenFalse_unknownFalse() {
    repository().save(userId, prefix + "r1", future(), UUID.randomUUID());

    assertThat(repository().revokeByTokenHash(prefix + "r1")).isTrue();
    assertThat(repository().revokeByTokenHash(prefix + "r1")).isFalse();
    assertThat(repository().revokeByTokenHash(prefix + "none")).isFalse();

    assertThat(repository().isTokenRevoked(prefix + "r1")).isTrue();
    assertThat(repository().existsValidToken(prefix + "r1")).isFalse();
  }

  @Test
  void revokeByFamilyId_revokesWholeFamilyOnly() {
    UUID family = UUID.randomUUID();
    repository().save(userId, prefix + "a", future(), family);
    repository().save(userId, prefix + "b", future(), family);
    repository().save(userId, prefix + "c", future(), UUID.randomUUID());

    repository().revokeByFamilyId(family);

    assertThat(repository().isTokenRevoked(prefix + "a")).isTrue();
    assertThat(repository().isTokenRevoked(prefix + "b")).isTrue();
    assertThat(repository().isTokenRevoked(prefix + "c")).isFalse();
  }

  @Test
  void save_defaultPlaneIsWeb_andRevokeAllWebKeepsPlatform() {
    repository().save(userId, prefix + "web", future(), UUID.randomUUID());
    repository()
        .save(
            userId,
            prefix + "plat",
            future(),
            UUID.randomUUID(),
            RefreshTokenRepository.PLANE_PLATFORM);
    repository().save(otherUserId, prefix + "otherweb", future(), UUID.randomUUID());

    repository().revokeAllWebByUserId(userId);

    assertThat(repository().isTokenRevoked(prefix + "web")).isTrue();
    assertThat(repository().isTokenRevoked(prefix + "plat")).isFalse();
    assertThat(repository().isTokenRevoked(prefix + "otherweb")).isFalse();
  }

  @Test
  void revokeAllByUserId_revokesEveryPlane_forThatUserOnly() {
    repository().save(userId, prefix + "web", future(), UUID.randomUUID());
    repository()
        .save(
            userId,
            prefix + "plat",
            future(),
            UUID.randomUUID(),
            RefreshTokenRepository.PLANE_PLATFORM);
    repository().save(otherUserId, prefix + "other", future(), UUID.randomUUID());

    repository().revokeAllByUserId(userId);

    assertThat(repository().isTokenRevoked(prefix + "web")).isTrue();
    assertThat(repository().isTokenRevoked(prefix + "plat")).isTrue();
    assertThat(repository().isTokenRevoked(prefix + "other")).isFalse();
  }

  @Test
  void deleteExpiredTokens_removesExpired_andOldRevoked_keepsLiveAndRecentRevoked() {
    repository()
        .save(userId, prefix + "expired", LocalDateTime.now().minusMinutes(1), UUID.randomUUID());
    repository().save(userId, prefix + "oldrev", future(), UUID.randomUUID());
    repository().save(userId, prefix + "newrev", future(), UUID.randomUUID());
    repository().save(userId, prefix + "live", future(), UUID.randomUUID());
    repository().revokeByTokenHash(prefix + "oldrev");
    repository().revokeByTokenHash(prefix + "newrev");
    // 폐기 뒤 7일이 지난 행 — 7일 경계는 JVM 시계 기준 1시간 여유로 시험한다.
    fixtureUpdate(
        "update refresh_token set created_at = ? where token_hash = ?",
        LocalDateTime.now().minusDays(7).minus(1, ChronoUnit.HOURS),
        prefix + "oldrev");
    fixtureUpdate(
        "update refresh_token set created_at = ? where token_hash = ?",
        LocalDateTime.now().minusDays(7).plus(1, ChronoUnit.HOURS),
        prefix + "newrev");

    int deleted = repository().deleteExpiredTokens();

    assertThat(deleted).isGreaterThanOrEqualTo(2);
    assertThat(
            fixtureLong(
                "select count(*) from refresh_token where token_hash in (?, ?)",
                prefix + "expired",
                prefix + "oldrev"))
        .isZero();
    assertThat(repository().existsValidToken(prefix + "live")).isTrue();
    assertThat(repository().isTokenRevoked(prefix + "newrev")).isTrue();
  }

  @Test
  void save_duplicateTokenHash_throwsDuplicateKeyException() {
    repository().save(userId, prefix + "dup", future(), UUID.randomUUID());

    assertThatThrownBy(
            () -> repository().save(otherUserId, prefix + "dup", future(), UUID.randomUUID()))
        .isInstanceOf(DuplicateKeyException.class);
  }

  /** Tibero 는 빈 문자열을 NULL 로 보지만(NOT NULL 열이면 -10005) PG 와 같이 빈 해시도 저장·조회돼야 한다(리뷰 포인트 2). */
  @Test
  void emptyTokenHash_isSavedFoundAndRevoked() {
    UUID family = UUID.randomUUID();
    repository().save(userId, "", future(), family);

    assertThat(repository().existsValidToken("")).isTrue();
    assertThat(repository().findFamilyIdByTokenHash("")).contains(family);
    assertThat(repository().revokeByTokenHash("")).isTrue();
    assertThat(repository().isTokenRevoked("")).isTrue();
  }
}
