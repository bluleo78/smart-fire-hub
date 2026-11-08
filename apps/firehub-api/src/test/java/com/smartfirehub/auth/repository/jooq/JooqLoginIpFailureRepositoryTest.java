package com.smartfirehub.auth.repository.jooq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.auth.repository.LoginIpFailureRepository;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * 로그인 IP 실패 기록 저장소 통합 테스트(WD-578) — 실제 PostgreSQL(Testcontainers).
 *
 * <p>같은 계정 반복 실패는 한 행(서로 다른 계정 수로 센다), IP 키끼리 분리, 만료 행 미집계·정리, 동시 UPSERT 의 원자성을 본다. 테스트마다 무작위 IP 키를
 * 써서 다른 테스트의 잔여 행과 섞이지 않게 하고, 끝나면 그 키의 행을 지운다(이 클래스는 트랜잭션 없이 커밋한다).
 */
class JooqLoginIpFailureRepositoryTest extends IntegrationTestBase {

  @Autowired private LoginIpFailureRepository repository;
  @Autowired private DSLContext dsl;

  private String ipKey;
  private String otherKey;

  @BeforeEach
  void setUp() {
    ipKey = "test-" + UUID.randomUUID();
    otherKey = "test-" + UUID.randomUUID();
  }

  @AfterEach
  void cleanUp() {
    dsl.execute("delete from login_ip_failures where ip_key in (?, ?)", ipKey, otherKey);
  }

  @Test
  void sameAccountRepeated_countsOnce() {
    repository.recordFailure(ipKey, "alice");
    repository.recordFailure(ipKey, "alice");
    repository.recordFailure(ipKey, "alice");

    assertThat(repository.countActiveAccounts(ipKey)).isEqualTo(1);
  }

  @Test
  void distinctAccounts_areCounted_andKeysAreSeparate() {
    repository.recordFailure(ipKey, "alice");
    repository.recordFailure(ipKey, "bob");
    repository.recordFailure(otherKey, "carol");

    assertThat(repository.countActiveAccounts(ipKey)).isEqualTo(2);
    assertThat(repository.countActiveAccounts(otherKey)).isEqualTo(1);
    assertThat(repository.countActiveAccounts("test-" + UUID.randomUUID())).isZero();
  }

  @Test
  void expiredRows_areNotCounted_andDeleteExpiredRemovesOnlyThem() {
    repository.recordFailure(ipKey, "old1");
    repository.recordFailure(ipKey, "old2");
    repository.recordFailure(ipKey, "fresh");
    // DB 시계 기준으로 과거로 돌린다 — 저장소가 now() 로 비교하므로 같은 시계를 쓴다.
    dsl.execute(
        "update login_ip_failures set expires_at = now() - interval '1 minute'"
            + " where ip_key = ? and username in ('old1', 'old2')",
        ipKey);

    assertThat(repository.countActiveAccounts(ipKey)).isEqualTo(1);

    int deleted = repository.deleteExpired();
    // 다른 테스트의 만료 행도 함께 지워질 수 있다
    assertThat(deleted).isGreaterThanOrEqualTo(2);
    assertThat(dsl.fetchCount(dsl.selectOne().from("login_ip_failures").where("ip_key = ?", ipKey)))
        .isEqualTo(1);
  }

  @Test
  void recordFailure_onExpiredRow_revivesIt() {
    repository.recordFailure(ipKey, "alice");
    dsl.execute(
        "update login_ip_failures set expires_at = now() - interval '1 minute' where ip_key = ?",
        ipKey);
    assertThat(repository.countActiveAccounts(ipKey)).isZero();

    // 같은 계정이 다시 실패하면 PK 충돌 없이 만료만 미뤄진다
    repository.recordFailure(ipKey, "alice");
    assertThat(repository.countActiveAccounts(ipKey)).isEqualTo(1);
  }

  @Test
  void concurrentFailures_sameAccount_upsertWithoutConflict() throws Exception {
    CompletableFuture<?>[] futures = new CompletableFuture[8];
    for (int i = 0; i < futures.length; i++) {
      futures[i] = CompletableFuture.runAsync(() -> repository.recordFailure(ipKey, "alice"));
    }
    CompletableFuture.allOf(futures).get();

    assertThat(repository.countActiveAccounts(ipKey)).isEqualTo(1);
  }

  /** 잠금은 트랜잭션 범위다 — 트랜잭션 밖에서 부르면 문장 끝에 바로 풀려 아무것도 막지 못하므로 예외로 알린다. */
  @Test
  void lockKey_outsideTransaction_isRejected() {
    assertThatThrownBy(() -> repository.lockKey(ipKey))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  /** 트랜잭션 안에서는 잠금을 잡고 커밋 때 푼다 — 같은 키를 이어서 다시 잡을 수 있다. */
  @Test
  void lockKey_insideTransaction_isReleasedOnCommit() {
    fixtureTransactionTemplate.executeWithoutResult(status -> repository.lockKey(ipKey));
    fixtureTransactionTemplate.executeWithoutResult(status -> repository.lockKey(ipKey));
  }
}
