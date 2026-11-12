package com.smartfirehub.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.user.repository.TenantPairContractBase;
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
 * UserChannelPreferenceRepository 계약 테스트 — 공개 메서드 2 개 전부(WD-709). jOOQ·MyBatis 는 PG, TiberoMyBatis
 * 는 Tibero 실서버(두 계정 RLS)다. 기존 UserChannelPreferenceUpsertTest·ConstraintTest(PG 제약 이름 시험)는 그대로 둔다.
 *
 * <p>사용자는 전역이라 한 사용자를 두 테넌트에서 쓴다 — 선호는 테넌트별로 갈려야 한다. enabled 저장값(PG boolean / Tibero NUMBER(1))은
 * 소유자 시점 픽스처로 읽는다.
 */
abstract class UserChannelPreferenceRepositoryContractTest extends TenantPairContractBase {

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract UserChannelPreferenceRepository repository();

  private long user;

  @BeforeEach
  void setUpUser() {
    user = fx.createUser(prefix + "-u", null);
  }

  private boolean enabledInA(ChannelType t) {
    return inA(() -> repository().isEnabled(user, t));
  }

  @Test
  @DisplayName("행이 없으면 기본값 true 이다")
  void isEnabled_defaultsToTrue() {
    assertThat(enabledInA(ChannelType.EMAIL)).isTrue();
    assertThat(enabledInA(ChannelType.SLACK)).isTrue();
    assertThat(fx.userChannelPreferenceEnabled(tenantA, user, "EMAIL")).isNull();
  }

  @Test
  @DisplayName("CHAT 은 조회 없이 항상 true 이다")
  void isEnabled_chatAlwaysTrue() {
    assertThat(enabledInA(ChannelType.CHAT)).isTrue();
  }

  @Test
  @DisplayName("끄면 false, 다시 켜면 true — 같은 행을 갱신한다")
  void setEnabled_togglesSameRow() {
    runA(() -> repository().setEnabled(user, ChannelType.EMAIL, false));
    assertThat(enabledInA(ChannelType.EMAIL)).isFalse();
    assertThat(fx.userChannelPreferenceEnabled(tenantA, user, "EMAIL")).isFalse();

    runA(() -> repository().setEnabled(user, ChannelType.EMAIL, false));
    runA(() -> repository().setEnabled(user, ChannelType.EMAIL, true));
    assertThat(enabledInA(ChannelType.EMAIL)).isTrue();
    assertThat(fx.userChannelPreferenceEnabled(tenantA, user, "EMAIL")).isTrue();
  }

  @Test
  @DisplayName("채널별로 독립이다")
  void setEnabled_isPerChannel() {
    runA(() -> repository().setEnabled(user, ChannelType.KAKAO, false));

    assertThat(enabledInA(ChannelType.KAKAO)).isFalse();
    assertThat(enabledInA(ChannelType.SLACK)).isTrue();
    assertThat(fx.userChannelPreferenceEnabled(tenantA, user, "SLACK")).isNull();
  }

  @Test
  @DisplayName("같은 사용자의 선호는 테넌트별로 갈린다")
  void preference_isTenantScoped() {
    runA(() -> repository().setEnabled(user, ChannelType.EMAIL, false));

    assertThat(inB(() -> repository().isEnabled(user, ChannelType.EMAIL))).isTrue();
    assertThat(fx.userChannelPreferenceEnabled(tenantB, user, "EMAIL")).isNull();

    inB(
        () -> {
          repository().setEnabled(user, ChannelType.EMAIL, true);
          return null;
        });
    assertThat(enabledInA(ChannelType.EMAIL)).isFalse();
    assertThat(fx.userChannelPreferenceEnabled(tenantB, user, "EMAIL")).isTrue();
  }

  @Test
  @DisplayName("CHAT 을 끄려 하면 DB 제약이 거절한다")
  void setEnabled_chatCannotBeDisabled() {
    assertThatThrownBy(() -> runA(() -> repository().setEnabled(user, ChannelType.CHAT, false)))
        .isInstanceOf(RuntimeException.class);
    assertThat(fx.userChannelPreferenceEnabled(tenantA, user, "CHAT")).isNull();
  }

  /** 같은 키를 두 트랜잭션이 동시에 처음 저장하면 차례로 처리된다 — 오류 없이 나중에 끝난 쪽 값이 남는다(Tibero 는 중복 키를 한 번 다시 시도). */
  @Test
  @DisplayName("동시에 같은 선호를 처음 저장하는 두 트랜잭션은 차례로 처리되어 한 행만 남는다")
  void concurrentFirstUpserts_areSerialized() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch firstDone = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try {
      Future<?> first =
          pool.submit(
              () ->
                  inA(
                      () -> {
                        repository().setEnabled(user, ChannelType.EMAIL, false);
                        firstDone.countDown();
                        awaitQuietly(release);
                        return null;
                      }));
      assertThat(firstDone.await(30, TimeUnit.SECONDS)).isTrue();

      Future<?> second =
          pool.submit(
              () ->
                  inA(
                      () -> {
                        repository().setEnabled(user, ChannelType.EMAIL, true);
                        return null;
                      }));
      assertThatThrownBy(() -> second.get(1500, TimeUnit.MILLISECONDS))
          .as("두 번째 저장은 첫 저장이 끝날 때까지 기다린다")
          .isInstanceOf(TimeoutException.class);

      release.countDown();
      first.get(30, TimeUnit.SECONDS);
      second.get(30, TimeUnit.SECONDS);

      assertThat(fx.userChannelPreferenceEnabled(tenantA, user, "EMAIL")).isTrue();
    } finally {
      release.countDown();
      pool.shutdown();
      pool.awaitTermination(30, TimeUnit.SECONDS);
    }
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
