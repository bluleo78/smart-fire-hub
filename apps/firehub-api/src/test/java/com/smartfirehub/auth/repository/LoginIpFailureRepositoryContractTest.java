package com.smartfirehub.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * LoginIpFailureRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-706).
 *
 * <p>만료 판정은 모두 DB 시계다(WD-235 원칙). DB 시계와 정확히 같은 시각을 만들 수는 없어 경계는 ±1분으로 시험한다. 동시 UPSERT 원자성은 기존
 * {@code JooqLoginIpFailureRepositoryTest} 가 맡는다(PG 전용).
 */
abstract class LoginIpFailureRepositoryContractTest extends AuthRepositoryContractBase {

  protected abstract LoginIpFailureRepository repository();

  private String ipKey;
  private String otherKey;

  @BeforeEach
  void newKeys() {
    ipKey = "ct-" + UUID.randomUUID();
    otherKey = "ct-" + UUID.randomUUID();
  }

  @AfterEach
  void cleanup() {
    fixtureUpdate("delete from login_ip_failures where ip_key in (?, ?)", ipKey, otherKey);
    repository().deleteExpired();
  }

  private void expireAt(String key, String username, String sqlOffset) {
    fixtureUpdate(
        "update login_ip_failures set expires_at = "
            + dbNow()
            + " "
            + sqlOffset
            + " where ip_key = ? and username = ?",
        key,
        username);
  }

  @Test
  void sameAccountRepeated_countsOnce() {
    repository().recordFailure(ipKey, "alice");
    repository().recordFailure(ipKey, "alice");
    repository().recordFailure(ipKey, "alice");

    assertThat(repository().countActiveAccounts(ipKey)).isEqualTo(1);
  }

  @Test
  void distinctAccounts_areCounted_andKeysAreSeparate() {
    repository().recordFailure(ipKey, "alice");
    repository().recordFailure(ipKey, "bob");
    repository().recordFailure(otherKey, "carol");

    assertThat(repository().countActiveAccounts(ipKey)).isEqualTo(2);
    assertThat(repository().countActiveAccounts(otherKey)).isEqualTo(1);
    assertThat(repository().countActiveAccounts("ct-" + UUID.randomUUID())).isZero();
  }

  @Test
  void recordFailure_pushesExpiryToFifteenMinutes_withoutAddingRows() {
    repository().recordFailure(ipKey, "alice");
    expireAt(ipKey, "alice", "+ INTERVAL '1' MINUTE");

    repository().recordFailure(ipKey, "alice");

    assertThat(
            fixtureLong(
                "select count(*) from login_ip_failures where ip_key = ? and expires_at > "
                    + dbNow()
                    + " + INTERVAL '10' MINUTE and expires_at < "
                    + dbNow()
                    + " + INTERVAL '20' MINUTE",
                ipKey))
        .isEqualTo(1);
    assertThat(fixtureLong("select count(*) from login_ip_failures where ip_key = ?", ipKey))
        .isEqualTo(1);
  }

  @Test
  void expiredRows_areNotCounted_andJustBeforeExpiryStillCounts() {
    repository().recordFailure(ipKey, "old");
    repository().recordFailure(ipKey, "live");
    expireAt(ipKey, "old", "- INTERVAL '1' MINUTE");
    expireAt(ipKey, "live", "+ INTERVAL '1' MINUTE");

    assertThat(repository().countActiveAccounts(ipKey)).isEqualTo(1);
  }

  @Test
  void recordFailure_onExpiredRow_revivesIt() {
    repository().recordFailure(ipKey, "alice");
    expireAt(ipKey, "alice", "- INTERVAL '1' MINUTE");
    assertThat(repository().countActiveAccounts(ipKey)).isZero();

    repository().recordFailure(ipKey, "alice");

    assertThat(repository().countActiveAccounts(ipKey)).isEqualTo(1);
  }

  @Test
  void deleteExpired_removesOnlyExpiredRows() {
    repository().recordFailure(ipKey, "old1");
    repository().recordFailure(ipKey, "old2");
    repository().recordFailure(ipKey, "fresh");
    expireAt(ipKey, "old1", "- INTERVAL '1' MINUTE");
    expireAt(ipKey, "old2", "- INTERVAL '1' MINUTE");

    int deleted = repository().deleteExpired();

    // 공유 DB 의 다른 만료 행도 함께 지워질 수 있다.
    assertThat(deleted).isGreaterThanOrEqualTo(2);
    assertThat(fixtureLong("select count(*) from login_ip_failures where ip_key = ?", ipKey))
        .isEqualTo(1);
  }

  /** Tibero 는 빈 문자열을 NULL 로 보지만(NOT NULL 열이면 -10005) PG 와 같이 빈 키·빈 계정도 기록·집계돼야 한다(리뷰 포인트 2). */
  @Test
  void emptyIpKeyAndUsername_areRecordedAndCounted() {
    repository().recordFailure("", "alice");
    repository().recordFailure("", "alice");
    repository().recordFailure("", "");
    repository().recordFailure(ipKey, "");

    assertThat(repository().countActiveAccounts("")).isEqualTo(2);
    assertThat(repository().countActiveAccounts(ipKey)).isEqualTo(1);

    // 빈 키 행은 cleanup 의 ip_key 조건에 안 걸리므로 만료시켜 deleteExpired 로 치운다.
    // 다른 시험의 키(ct-·test- 접두)는 건드리지 않는다.
    fixtureUpdate(
        "update login_ip_failures set expires_at = "
            + dbNow()
            + " - INTERVAL '1' MINUTE where ip_key not like 'ct-%' and ip_key not like 'test-%'");
    repository().deleteExpired();
    assertThat(repository().countActiveAccounts("")).isZero();
  }

  /** 잠금은 트랜잭션 범위다 — 트랜잭션 밖에서 부르면 문장 끝에 바로 풀려 아무것도 막지 못하므로 예외로 알린다. */
  @Test
  void lockKey_outsideTransaction_isRejected() {
    assertThatThrownBy(() -> repository().lockKey(ipKey))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  /** 트랜잭션 안에서는 잠금을 잡고 커밋 때 푼다 — 같은 키를 이어서 다시 잡을 수 있다. */
  @Test
  void lockKey_insideTransaction_isReleasedOnCommit() {
    inTx(() -> repository().lockKey(ipKey));
    inTx(() -> repository().lockKey(ipKey));
  }
}
