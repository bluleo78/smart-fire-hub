package com.smartfirehub.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.repository.OAuthStateRepository.ConsumedState;
import com.smartfirehub.user.repository.AccessFixtures.OAuthStateStored;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OAuthStateRepository 계약 테스트 — 공개 메서드 3 개 전부(WD-709). jOOQ·MyBatis 는 PG, TiberoMyBatis 는 Tibero
 * 실서버(두 계정 RLS)다.
 *
 * <p>oauth_state 는 RLS 정책이 없다(V106 [R7]) — 발급(create)은 테넌트 범위에서, 소비(consume)는 <b>테넌트 컨텍스트 없이</b>
 * 부른다(permitAll 콜백 재현). tenant_id 가 NULL 인 행은 열이 NOT NULL 이라 DB 로는 만들 수 없어 단위
 * 테스트(MyBatisNotificationBundle4UnitTest)가 맡는다.
 */
abstract class OAuthStateRepositoryContractTest extends TenantPairContractBase {

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract OAuthStateRepository repository();

  private long user;
  private String state;

  @BeforeEach
  void setUpUser() {
    user = fx.createUser(prefix + "-u", null);
    state = prefix + UUID.randomUUID().toString().replace("-", "");
  }

  private void issueInA(String s, ChannelType type, Instant expiresAt) {
    runA(() -> repository().create(s, user, type, expiresAt));
  }

  private Optional<ConsumedState> consumeNoTenant(String s) {
    return fx.noTenant(() -> repository().consume(s));
  }

  @Test
  @DisplayName("consume 은 테넌트 컨텍스트 없이도 발급 시점의 사용자·채널·테넌트를 돌려주고 소비 표시를 한다")
  void consume_returnsCarriedTenantWithoutContext() {
    issueInA(state, ChannelType.SLACK, Instant.now().plus(Duration.ofMinutes(10)));

    ConsumedState consumed = consumeNoTenant(state).orElseThrow();

    assertThat(consumed.userId()).isEqualTo(user);
    assertThat(consumed.channelType()).isEqualTo(ChannelType.SLACK);
    assertThat(consumed.tenantId()).isEqualTo(tenantA);
    OAuthStateStored stored = fx.oauthStateStored(state);
    assertThat(stored.consumed()).isTrue();
    assertThat(stored.tenantId()).isEqualTo(tenantA);
  }

  @Test
  @DisplayName("다른 테넌트에서 발급한 state 는 그 테넌트를 실어 나른다")
  void consume_carriesIssuingTenant() {
    issueInA(state + "A", ChannelType.KAKAO, Instant.now().plus(Duration.ofMinutes(10)));
    runInB(() -> repository().create(state + "B", user, ChannelType.KAKAO, farFuture()));

    assertThat(consumeNoTenant(state + "B").orElseThrow().tenantId()).isEqualTo(tenantB);
    assertThat(consumeNoTenant(state + "A").orElseThrow().tenantId()).isEqualTo(tenantA);
  }

  @Test
  @DisplayName("이미 소비된 state 는 두 번째에 empty 이다")
  void consume_isSingleUse() {
    issueInA(state, ChannelType.SLACK, farFuture());

    assertThat(consumeNoTenant(state)).isPresent();
    assertThat(consumeNoTenant(state)).isEmpty();
  }

  @Test
  @DisplayName("만료된 state 는 empty 이고 소비 표시가 남지 않는다")
  void consume_expiredIsEmpty() {
    issueInA(state, ChannelType.SLACK, Instant.now().minus(Duration.ofMinutes(1)));

    assertThat(consumeNoTenant(state)).isEmpty();
    assertThat(fx.oauthStateStored(state).consumed()).isFalse();
  }

  @Test
  @DisplayName("없는 state 는 empty 이다")
  void consume_unknownIsEmpty() {
    assertThat(consumeNoTenant(state + "-none")).isEmpty();
  }

  @Test
  @DisplayName("deleteExpired 는 만료 행만 지우고 지운 행 수를 돌려준다")
  void deleteExpired_removesOnlyExpired() {
    issueInA(state + "e1", ChannelType.SLACK, Instant.now().minus(Duration.ofHours(1)));
    issueInA(state + "e2", ChannelType.KAKAO, Instant.now().minus(Duration.ofMinutes(1)));
    issueInA(state + "ok", ChannelType.SLACK, farFuture());

    // 공유 DB 라 다른 시험의 만료 행도 함께 지워질 수 있다 — 우리 두 행 이상이면 된다.
    int deleted = fx.noTenant(() -> repository().deleteExpired());

    assertThat(deleted).isGreaterThanOrEqualTo(2);
    assertThat(fx.oauthStateStored(state + "e1")).isNull();
    assertThat(fx.oauthStateStored(state + "e2")).isNull();
    assertThat(fx.oauthStateStored(state + "ok")).isNotNull();
  }

  /**
   * 같은 state 를 두 트랜잭션이 동시에 소비하면 승자는 하나다(Review Focus 2). 첫 트랜잭션이 커밋 전에 멈춘 사이 두 번째는 행 잠금에서 기다렸다가, 첫
   * 쪽이 커밋하면 empty 를 받는다.
   */
  @Test
  @DisplayName("같은 state 를 동시에 소비하는 두 트랜잭션 중 승자는 하나다")
  void concurrentConsume_hasExactlyOneWinner() throws Exception {
    issueInA(state, ChannelType.SLACK, farFuture());
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch firstDone = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try {
      Future<Optional<ConsumedState>> first =
          pool.submit(
              () ->
                  fx.inTenant(
                      null,
                      () -> {
                        Optional<ConsumedState> r = repository().consume(state);
                        firstDone.countDown();
                        awaitQuietly(release);
                        return r;
                      }));
      assertThat(firstDone.await(30, TimeUnit.SECONDS)).isTrue();

      Future<Optional<ConsumedState>> second =
          pool.submit(() -> fx.inTenant(null, () -> repository().consume(state)));
      assertThatThrownBy(() -> second.get(1500, TimeUnit.MILLISECONDS))
          .as("두 번째 소비는 첫 트랜잭션이 끝날 때까지 기다린다")
          .isInstanceOf(TimeoutException.class);

      release.countDown();
      Optional<ConsumedState> r1 = first.get(30, TimeUnit.SECONDS);
      Optional<ConsumedState> r2 = second.get(30, TimeUnit.SECONDS);

      assertThat(r1).isPresent();
      assertThat(r1.get().tenantId()).isEqualTo(tenantA);
      assertThat(r2).as("뒤따른 트랜잭션은 empty").isEmpty();
    } finally {
      release.countDown();
      pool.shutdown();
      pool.awaitTermination(30, TimeUnit.SECONDS);
    }
  }

  private void runInB(Runnable call) {
    inB(
        () -> {
          call.run();
          return null;
        });
  }

  private static Instant farFuture() {
    return Instant.now().plus(Duration.ofMinutes(30));
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
