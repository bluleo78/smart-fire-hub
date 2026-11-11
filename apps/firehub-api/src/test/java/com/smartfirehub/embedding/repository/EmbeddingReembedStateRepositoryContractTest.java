package com.smartfirehub.embedding.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.embedding.reembed.ReembedState;
import com.smartfirehub.global.tenant.MissingTenantScopeException;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.Duration;
import java.time.Instant;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * EmbeddingReembedStateRepository 계약 테스트(WD-712) — 공개 메서드 10 개 전부를 구현마다 하위 클래스로 실행한다(jOOQ·MyBatis 는
 * PG, TiberoMyBatis 는 Tibero 실서버 두 계정 RLS).
 *
 * <p>리뷰 포인트 2(임대 시각): 만료 경계(막 만료 −3초·아직 유효 +30초)를 벽시계 대기 없이 소유자 픽스처로 만들고, 동시에 두 트랜잭션이 잡으면 하나만
 * 이기며(행이 없을 때·만료된 행이 있을 때), JVM 기본 시간대가 KST 여도 시각이 9시간 어긋나지 않는다. 리뷰 포인트 4(다른 테넌트 차단): 테넌트 B 의 상태·설정
 * 행이 A 의 판정에 섞이지 않는다.
 */
abstract class EmbeddingReembedStateRepositoryContractTest extends TenantPairContractBase {

  private static final Duration LEASE = Duration.ofSeconds(600);
  private static final String CONFIG_KEY = "embedding.config";
  private static final EmbeddingSpace SPACE =
      new EmbeddingSpace(EmbeddingDimension.D1024, "bge-m3");

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract EmbeddingReembedStateRepository repository();

  private ReembedState stateA() {
    return inA(() -> repository().find()).orElseThrow();
  }

  private boolean acquireA() {
    return inA(() -> repository().tryAcquire(LEASE));
  }

  private boolean activeA() {
    return inA(() -> repository().hasActiveLease());
  }

  private boolean failedSinceSaveA() {
    return inA(() -> repository().isFailedSinceLastConfigSave());
  }

  /** 리포지토리가 읽은 시각과 DB 시계의 차이(절댓값) — 9시간 어긋남을 잡는다. */
  private Duration skewFromDbNow(ReembedState s) {
    return Duration.between(s.updatedAt().toInstant(), fx.dbNow()).abs();
  }

  @Test
  @DisplayName("한 번도 돌지 않았으면 find 는 빈 값, 활성 임대·실패 판정은 모두 거짓")
  void neverRun_emptyAndFalse() {
    assertThat(inA(() -> repository().find())).isEmpty();
    assertThat(activeA()).isFalse();
    assertThat(failedSinceSaveA()).isFalse();
  }

  @Test
  @DisplayName("첫 tryAcquire 는 RUNNING 행을 만들고 임대 만료는 DB 시계 + lease, 갱신 시각은 DB 시계다")
  void tryAcquire_createsRunningRow() {
    assertThat(acquireA()).isTrue();

    ReembedState s = stateA();
    assertThat(s.status()).isEqualTo("RUNNING");
    assertThat(s.model()).isNull();
    assertThat(s.dimension()).isNull();
    assertThat(s.lastError()).isNull();
    assertThat(skewFromDbNow(s)).isLessThan(Duration.ofSeconds(30));
    assertThat(fx.reembedLeaseRemaining(tenantA))
        .isBetween(Duration.ofSeconds(540), Duration.ofSeconds(601));
    assertThat(activeA()).isTrue();
  }

  @Test
  @DisplayName("임대는 풀기 전까지 배타적이고 release 는 상태를 그대로 둔 채 임대만 비운다")
  void lease_isExclusiveUntilReleased() {
    assertThat(acquireA()).isTrue();
    assertThat(acquireA()).isFalse();

    runA(() -> repository().release());
    assertThat(fx.reembedLeaseRemaining(tenantA)).isNull();
    assertThat(stateA().status()).isEqualTo("RUNNING");
    assertThat(activeA()).isFalse();

    assertThat(acquireA()).isTrue();
  }

  @Test
  @DisplayName("임대 경계 — DB 시계 기준 막 만료(−3초)는 다시 잡히고 아직 유효(+30초)는 안 잡히며 만료 시각도 그대로다")
  void lease_expiryBoundary() {
    assertThat(acquireA()).isTrue();

    fx.setReembedLeaseFromDbNow(tenantA, -3);
    assertThat(activeA()).isFalse();
    assertThat(acquireA()).isTrue();
    assertThat(fx.reembedLeaseRemaining(tenantA))
        .isBetween(Duration.ofSeconds(540), Duration.ofSeconds(601));

    fx.setReembedLeaseFromDbNow(tenantA, 30);
    assertThat(activeA()).isTrue();
    assertThat(acquireA()).isFalse();
    // 실패한 시도는 만료 시각을 건드리지 않는다.
    assertThat(fx.reembedLeaseRemaining(tenantA))
        .isBetween(Duration.ofSeconds(20), Duration.ofSeconds(31));
  }

  @Test
  @DisplayName("다시 잡으면 상태를 RUNNING 으로 되돌리고 이전 실패 사유를 지운다 — 실패 기록은 임대를 풀지 않는다")
  void tryAcquire_resetsStatusAndClearsError() {
    assertThat(acquireA()).isTrue();
    runA(() -> repository().markFailed("boom"));
    // markFailed 는 임대를 그대로 둔다 — 아직 유효하므로 못 잡는다.
    assertThat(acquireA()).isFalse();
    assertThat(stateA().lastError()).isEqualTo("boom");

    runA(() -> repository().release());
    assertThat(acquireA()).isTrue();

    ReembedState s = stateA();
    assertThat(s.status()).isEqualTo("RUNNING");
    assertThat(s.lastError()).isNull();
  }

  @Test
  @DisplayName("renewLease 는 만료 여부와 무관하게 DB 시계 + lease 로 늘리고, 행이 없으면 아무것도 안 한다")
  void renewLease_extendsFromDbNow() {
    runA(() -> repository().renewLease(LEASE));
    assertThat(inA(() -> repository().find())).isEmpty();

    assertThat(inA(() -> repository().tryAcquire(Duration.ofSeconds(60)))).isTrue();
    assertThat(fx.reembedLeaseRemaining(tenantA)).isLessThanOrEqualTo(Duration.ofSeconds(61));

    runA(() -> repository().renewLease(LEASE));
    assertThat(fx.reembedLeaseRemaining(tenantA))
        .isBetween(Duration.ofSeconds(540), Duration.ofSeconds(601));

    fx.setReembedLeaseFromDbNow(tenantA, -3);
    runA(() -> repository().renewLease(LEASE));
    assertThat(fx.reembedLeaseRemaining(tenantA))
        .isBetween(Duration.ofSeconds(540), Duration.ofSeconds(601));
  }

  @Test
  @DisplayName("markRunning 은 공간(모델·차원)을 기록하고 실패 사유를 지운다")
  void markRunning_recordsSpaceAndClearsError() {
    assertThat(acquireA()).isTrue();
    runA(() -> repository().markFailed("old"));

    runA(() -> repository().markRunning(SPACE));

    ReembedState s = stateA();
    assertThat(s.status()).isEqualTo("RUNNING");
    assertThat(s.model()).isEqualTo("bge-m3");
    assertThat(s.dimension()).isEqualTo(1024);
    assertThat(s.lastError()).isNull();
  }

  @Test
  @DisplayName("markDone·markSuperseded·markFailed 는 상태·사유만 바꾸고 공간·임대는 그대로 둔다")
  void statusTransitions_keepSpaceAndLease() {
    assertThat(acquireA()).isTrue();
    runA(() -> repository().markRunning(SPACE));

    runA(() -> repository().markFailed("boom"));
    assertThat(stateA().status()).isEqualTo("FAILED");
    assertThat(stateA().lastError()).isEqualTo("boom");

    runA(() -> repository().markSuperseded());
    ReembedState superseded = stateA();
    assertThat(superseded.status()).isEqualTo("SUPERSEDED");
    assertThat(superseded.lastError()).isNull();

    runA(() -> repository().markDone());
    ReembedState done = stateA();
    assertThat(done.status()).isEqualTo("DONE");
    assertThat(done.lastError()).isNull();
    assertThat(done.model()).isEqualTo("bge-m3");
    assertThat(done.dimension()).isEqualTo(1024);
    assertThat(fx.reembedLeaseRemaining(tenantA))
        .isBetween(Duration.ofSeconds(540), Duration.ofSeconds(601));
    assertThat(skewFromDbNow(done)).isLessThan(Duration.ofSeconds(30));
  }

  @Test
  @DisplayName("hasActiveLease 는 RUNNING 일 때만 참이다 — 임대가 유효해도 DONE·FAILED 면 거짓")
  void hasActiveLease_requiresRunning() {
    assertThat(acquireA()).isTrue();
    assertThat(activeA()).isTrue();

    runA(() -> repository().markDone());
    assertThat(activeA()).isFalse();

    runA(() -> repository().markRunning(SPACE));
    assertThat(activeA()).isTrue();

    runA(() -> repository().markFailed("x"));
    assertThat(activeA()).isFalse();
  }

  @Test
  @DisplayName("실패 뒤 설정 저장 판정 — 설정 없음·실패 전 저장·다른 키는 참, 실패 뒤 저장·FAILED 아님은 거짓")
  void isFailedSinceLastConfigSave_matrix() {
    assertThat(acquireA()).isTrue();
    runA(() -> repository().markFailed("bad key"));
    // 설정 행이 없으면 참(미설정은 스윕이 먼저 걸러 낸다).
    assertThat(failedSinceSaveA()).isTrue();

    // 실패 기록(DB 시계 지금)보다 60초 전에 저장된 설정 → 여전히 참.
    fx.putTenantSettingFromDbNow(tenantA, CONFIG_KEY, "{}", -60);
    assertThat(failedSinceSaveA()).isTrue();

    // 다른 키가 나중에 저장돼도 참.
    fx.putTenantSettingFromDbNow(tenantA, "embedding.other", "{}", 60);
    assertThat(failedSinceSaveA()).isTrue();

    // 실패 기록을 2분 전으로 옮기고 설정을 1분 전에 저장 → 실패 뒤 저장이라 거짓.
    fx.setReembedUpdatedAtFromDbNow(tenantA, -120);
    fx.putTenantSettingFromDbNow(tenantA, CONFIG_KEY, "{}", -60);
    assertThat(failedSinceSaveA()).isFalse();

    // FAILED 가 아니면 거짓.
    fx.putTenantSettingFromDbNow(tenantA, CONFIG_KEY, "{}", -600);
    assertThat(failedSinceSaveA()).isTrue();
    runA(() -> repository().markDone());
    assertThat(failedSinceSaveA()).isFalse();
  }

  @Test
  @DisplayName("다른 테넌트 차단 — B 의 상태·임대·설정은 A 의 조회·판정·임대에 섞이지 않는다")
  void otherTenantIsIsolated() {
    assertThat(acquireA()).isTrue();
    runA(() -> repository().markFailed("a failed"));

    // B 는 자기 행을 따로 잡는다(A 의 유효 임대와 무관).
    assertThat(inB(() -> repository().find())).isEmpty();
    assertThat(inB(() -> repository().tryAcquire(LEASE))).isTrue();
    assertThat(inB(() -> repository().hasActiveLease())).isTrue();

    // B 가 설정을 새로 저장해도 A 의 판정은 그대로 참.
    fx.putTenantSettingFromDbNow(tenantB, CONFIG_KEY, "{}", 60);
    assertThat(failedSinceSaveA()).isTrue();
    assertThat(inB(() -> repository().isFailedSinceLastConfigSave())).isFalse();

    // B 의 갱신·해제는 A 행을 건드리지 않는다.
    inB(
        () -> {
          repository().markRunning(SPACE);
          repository().markDone();
          repository().renewLease(Duration.ofSeconds(60));
          repository().release();
          return null;
        });
    ReembedState a = stateA();
    assertThat(a.status()).isEqualTo("FAILED");
    assertThat(a.lastError()).isEqualTo("a failed");
    assertThat(a.model()).isNull();
    assertThat(fx.reembedLeaseRemaining(tenantA))
        .isBetween(Duration.ofSeconds(540), Duration.ofSeconds(601));
    assertThat(inB(() -> repository().find()).orElseThrow().status()).isEqualTo("DONE");
  }

  @Test
  @DisplayName("테넌트 컨텍스트 없이 부르면 MissingTenantScopeException")
  void noTenant_isRejected() {
    assertThatThrownBy(() -> fx.inTenant(null, () -> repository().tryAcquire(LEASE)))
        .isInstanceOf(MissingTenantScopeException.class);
    assertThatThrownBy(() -> fx.inTenant(null, () -> repository().find()))
        .isInstanceOf(MissingTenantScopeException.class);
  }

  @Test
  @DisplayName("실패 사유 — 빈 문자열은 PG 는 \"\", Tibero 는 null, null 은 null, 긴 한글은 원문의 접두어로 저장된다")
  void markFailed_emptyNullAndLongMessages() {
    assertThat(acquireA()).isTrue();

    runA(() -> repository().markFailed(""));
    if (emptyStringIsNull()) {
      assertThat(stateA().lastError()).isNull();
    } else {
      assertThat(stateA().lastError()).isEmpty();
    }

    runA(() -> repository().markFailed(null));
    assertThat(stateA().lastError()).isNull();

    // 30000 글자 한글 = UTF-8 90000 바이트 — Tibero VARCHAR2(65532) 바이트 한도를 넘는다.
    String huge = "임베딩실패".repeat(6000);
    runA(() -> repository().markFailed(huge));
    String stored = stateA().lastError();
    assertThat(stored).isNotEmpty();
    assertThat(huge).startsWith(stored);
    assertThat(stateA().status()).isEqualTo("FAILED");
  }

  @Test
  @DisplayName("동시 첫 획득(행 없음) — 두 트랜잭션 중 하나만 이긴다")
  void concurrentFirstAcquire_exactlyOneWins() throws Exception {
    boolean[] results = raceAcquire();
    assertThat(results[0]).isTrue();
    assertThat(results[1]).isFalse();
    assertThat(fx.reembedLeaseRemaining(tenantA))
        .isBetween(Duration.ofSeconds(540), Duration.ofSeconds(601));
  }

  @Test
  @DisplayName("동시 재획득(만료된 행) — 두 트랜잭션 중 하나만 이긴다")
  void concurrentAcquireOnExpiredRow_exactlyOneWins() throws Exception {
    assertThat(acquireA()).isTrue();
    fx.setReembedLeaseFromDbNow(tenantA, -3);

    boolean[] results = raceAcquire();
    assertThat(results[0]).isTrue();
    assertThat(results[1]).isFalse();
  }

  /**
   * 테넌트 A 로 두 트랜잭션이 겹쳐 tryAcquire 한다. 첫 트랜잭션은 잡은 뒤 커밋하지 않고 기다리고, 두 번째는 같은 행(또는 같은 PK)의 잠금에 막혀야 한다 —
   * 1초 안에 끝나면 겹침이 아니므로 실패. 첫 트랜잭션을 커밋시킨 뒤 둘의 결과를 [첫째, 둘째] 로 돌려준다. 공유 Tibero 에 행 잠금이 남지 않게 finally
   * 에서 항상 첫 트랜잭션을 풀어 준다.
   */
  private boolean[] raceAcquire() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch firstAcquired = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    try {
      Future<Boolean> first =
          pool.submit(
              () ->
                  inA(
                      () -> {
                        boolean got = repository().tryAcquire(LEASE);
                        firstAcquired.countDown();
                        awaitQuietly(releaseFirst);
                        return got;
                      }));
      assertThat(firstAcquired.await(30, TimeUnit.SECONDS)).isTrue();

      Future<Boolean> second = pool.submit(() -> inA(() -> repository().tryAcquire(LEASE)));
      assertThatThrownBy(() -> second.get(1, TimeUnit.SECONDS))
          .isInstanceOf(TimeoutException.class);

      releaseFirst.countDown();
      return new boolean[] {first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)};
    } finally {
      releaseFirst.countDown();
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

  @Test
  @DisplayName("JVM 기본 시간대가 Asia/Seoul 이어도 임대·갱신 시각·실패 판정이 9시간 어긋나지 않는다")
  void timesAreCorrect_whenJvmZoneIsSeoul() {
    TimeZone original = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
    try {
      assertThat(acquireA()).isTrue();
      assertThat(activeA()).isTrue();
      assertThat(fx.reembedLeaseRemaining(tenantA))
          .isBetween(Duration.ofSeconds(540), Duration.ofSeconds(601));

      // 리포지토리가 읽은 갱신 시각(DB 시계로 씀)이 DB 시계와 같은 순간이다 — 읽기 경로가 JVM 시간대로 9시간 밀리면 실패.
      runA(() -> repository().markFailed("kst"));
      ReembedState failed = stateA();
      Instant dbNow = fx.dbNow();
      assertThat(Duration.between(failed.updatedAt().toInstant(), dbNow).abs())
          .isLessThan(Duration.ofSeconds(30));

      // 경계 — 막 만료·아직 유효.
      fx.setReembedLeaseFromDbNow(tenantA, -3);
      assertThat(acquireA()).isTrue();
      fx.setReembedLeaseFromDbNow(tenantA, 30);
      assertThat(acquireA()).isFalse();

      // 실패 판정 — 실패(지금) 1분 전 저장은 참, 실패를 2분 전으로 옮기면 거짓.
      runA(() -> repository().markFailed("kst again"));
      fx.putTenantSettingFromDbNow(tenantA, CONFIG_KEY, "{}", -60);
      assertThat(failedSinceSaveA()).isTrue();
      fx.setReembedUpdatedAtFromDbNow(tenantA, -120);
      assertThat(failedSinceSaveA()).isFalse();
    } finally {
      TimeZone.setDefault(original);
    }
  }
}
