package com.smartfirehub.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * LoginAttemptRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-706).
 *
 * <p>카운터 증가·만료 창·정리·빈 문자열 키·테넌트 없는 호출을 고정한다. 만료 판정은 증가가 DB 시계({@code now()}), 조회·정리가 JVM 시계다(jOOQ
 * 구현과 같다). 두 시계가 한 값에서 정확히 만날 수는 없어, 경계는 "조금 미래·조금 과거"(±1분)로 시험한다.
 */
abstract class LoginAttemptRepositoryContractTest extends AuthRepositoryContractBase {

  protected abstract LoginAttemptRepository repository();

  private String prefix;

  @BeforeEach
  void newPrefix() {
    prefix = "la-" + UUID.randomUUID().toString().substring(0, 8) + "-";
  }

  @AfterEach
  void cleanup() {
    fixtureUpdate("delete from login_attempts where username like ?", prefix + "%");
    // 빈 문자열 키 시험이 남긴 행(구현이 어떤 열 값으로 저장하든 리포지토리를 통해 지운다).
    repository().clear("");
  }

  private void expireAt(String username, String sqlOffset) {
    fixtureUpdate(
        "update login_attempts set expires_at = "
            + dbNow()
            + " "
            + sqlOffset
            + " where username = ?",
        username);
  }

  @Test
  void incrementAttempts_countsUpFromOne_andGetAttemptsSeesIt() {
    String u = prefix + "a";

    assertThat(repository().incrementAttempts(u)).isEqualTo(1);
    assertThat(repository().incrementAttempts(u)).isEqualTo(2);
    assertThat(repository().incrementAttempts(u)).isEqualTo(3);

    assertThat(repository().getAttempts(u)).isEqualTo(3);
  }

  @Test
  void getAttempts_unknownUsername_isZero() {
    assertThat(repository().getAttempts(prefix + "none")).isZero();
  }

  @Test
  void counters_areIndependentPerUsername() {
    repository().incrementAttempts(prefix + "a");
    repository().incrementAttempts(prefix + "a");
    repository().incrementAttempts(prefix + "b");

    assertThat(repository().getAttempts(prefix + "a")).isEqualTo(2);
    assertThat(repository().getAttempts(prefix + "b")).isEqualTo(1);
  }

  @Test
  void incrementAttempts_refreshesExpiryToRollingFifteenMinutes() {
    String u = prefix + "roll";
    repository().incrementAttempts(u);
    expireAt(u, "+ INTERVAL '1' MINUTE");

    repository().incrementAttempts(u);

    // 15분 창으로 다시 밀렸다 — 10분 뒤보다 멀고 20분 뒤보다 가깝다.
    assertThat(
            fixtureLong(
                "select count(*) from login_attempts where username = ? and expires_at > "
                    + dbNow()
                    + " + INTERVAL '10' MINUTE and expires_at < "
                    + dbNow()
                    + " + INTERVAL '20' MINUTE",
                u))
        .isEqualTo(1);
  }

  @Test
  void getAttempts_expiredRow_isZero_andJustBeforeExpiryStillCounts() {
    String live = prefix + "live";
    String gone = prefix + "gone";
    repository().incrementAttempts(live);
    repository().incrementAttempts(gone);
    expireAt(live, "+ INTERVAL '1' MINUTE");
    expireAt(gone, "- INTERVAL '1' MINUTE");

    assertThat(repository().getAttempts(live)).isEqualTo(1);
    assertThat(repository().getAttempts(gone)).isZero();
  }

  /** 만료됐지만 아직 지워지지 않은 행이면 증가는 그 행을 이어 센다(리셋하지 않는다) — 현재 동작을 못 박는다. */
  @Test
  void incrementAttempts_onExpiredRow_continuesCountAndRevives() {
    String u = prefix + "revive";
    repository().incrementAttempts(u);
    repository().incrementAttempts(u);
    expireAt(u, "- INTERVAL '1' MINUTE");
    assertThat(repository().getAttempts(u)).isZero();

    assertThat(repository().incrementAttempts(u)).isEqualTo(3);
    assertThat(repository().getAttempts(u)).isEqualTo(3);
  }

  @Test
  void clear_removesOnlyThatUser_andUnknownIsNoop() {
    repository().incrementAttempts(prefix + "a");
    repository().incrementAttempts(prefix + "b");

    repository().clear(prefix + "a");
    repository().clear(prefix + "never");

    assertThat(repository().getAttempts(prefix + "a")).isZero();
    assertThat(repository().getAttempts(prefix + "b")).isEqualTo(1);
  }

  @Test
  void deleteExpired_removesOnlyExpiredRows_andReportsCount() {
    repository().incrementAttempts(prefix + "old1");
    repository().incrementAttempts(prefix + "old2");
    repository().incrementAttempts(prefix + "fresh");
    expireAt(prefix + "old1", "- INTERVAL '1' MINUTE");
    expireAt(prefix + "old2", "- INTERVAL '1' MINUTE");

    int deleted = repository().deleteExpired();

    // 공유 DB 의 다른 만료 행도 함께 지워질 수 있다.
    assertThat(deleted).isGreaterThanOrEqualTo(2);
    assertThat(
            fixtureLong("select count(*) from login_attempts where username like ?", prefix + "%"))
        .isEqualTo(1);
    assertThat(repository().getAttempts(prefix + "fresh")).isEqualTo(1);
  }

  /** Tibero 는 빈 문자열을 NULL 로 보지만(NOT NULL 열이면 -10005) PG 와 같이 빈 계정 키도 세고 지워져야 한다(리뷰 포인트 2). */
  @Test
  void emptyUsername_isCountedAndCleared() {
    assertThat(repository().incrementAttempts("")).isEqualTo(1);
    assertThat(repository().incrementAttempts("")).isEqualTo(2);
    assertThat(repository().getAttempts("")).isEqualTo(2);

    repository().clear("");

    assertThat(repository().getAttempts("")).isZero();
  }
}
