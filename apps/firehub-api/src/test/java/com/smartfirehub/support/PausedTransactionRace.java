package com.smartfirehub.support;

import com.smartfirehub.global.tenant.TenantContext;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 두 트랜잭션의 경합을 결정적으로 재현하는 테스트 도구(데이터셋 보안 등급 후속 F4·F5).
 *
 * <p>첫 작업(tx1)을 테넌트 트랜잭션 안에서 실행한 뒤 <b>커밋 전에 멈춘다</b>. 그 사이 두 번째 작업(tx2)을 시작해, 일정 시간 뒤에도 끝나지 않았는지(=
 * tx1 이 잡은 잠금·유니크 인덱스에서 대기 중인지) 관측하고 tx1 을 커밋시킨다. 테스트 커넥션 풀이 2개라(tx1·tx2 가 모두 점유) pg_stat_activity
 * 를 조회할 커넥션이 없어 시간으로 관측한다 — {@code
 * DatasetSecurityServiceTest#concurrentRemovalOfLastTwoGrants_keepsOneEntry} 와 같은 방식이다.
 *
 * <p>대기 관측이 필요한 이유: tx2 가 tx1 커밋 뒤에 시작되면 tx2 의 사전 검사가 커밋된 결과를 보고 평소 경로를 타 버려, 경합 처리 코드를 지워도 테스트가
 * 통과한다(공허한 테스트). "tx2 가 대기했다"를 단언해야 경합 구간을 실제로 지났음이 보장된다.
 */
public final class PausedTransactionRace {

  /** tx2 가 tx1 커밋 전까지 대기했는지 관측하는 시간. */
  private static final long BLOCK_OBSERVE_MS = 1_500;

  private PausedTransactionRace() {}

  /**
   * 경합 결과.
   *
   * @param secondBlocked tx2 가 관측 시간 동안 끝나지 않았는가(tx1 의 잠금·인덱스 대기)
   * @param secondResult tx2 의 반환값(예외로 끝났으면 null)
   * @param secondError tx2 가 던진 예외(정상 종료면 null)
   */
  public record Outcome<T>(boolean secondBlocked, T secondResult, Throwable secondError) {}

  /**
   * tx1 을 커밋 전에 멈춘 채 tx2 를 실행한다.
   *
   * @param transactionTemplate 픽스처 트랜잭션 템플릿(tx1 바깥 트랜잭션)
   * @param tenantId 두 작업의 테넌트 컨텍스트
   * @param first tx1 본문 — 바깥 테넌트 트랜잭션 안에서 실행되고 이 본문이 끝난 뒤 커밋 전에 멈춘다
   * @param second tx2 — 감싸지 않고 실행한다(프로덕션 호출이 스스로 트랜잭션을 세우는지까지 함께 검증)
   */
  public static <T> Outcome<T> run(
      TransactionTemplate transactionTemplate, long tenantId, Runnable first, Supplier<T> second)
      throws Exception {
    CountDownLatch firstDone = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> tx1 =
          pool.submit(
              () -> {
                TenantRlsTestSupport.runInTenantTransaction(
                    transactionTemplate,
                    tenantId,
                    () -> {
                      try {
                        first.run();
                      } finally {
                        // 실패해도 기다리는 쪽을 깨운다 — 아래에서 tx1 이 이미 끝났으면 그 예외를 드러낸다.
                        firstDone.countDown();
                      }
                      try {
                        // 커밋 전 멈춤 — tx2 가 이 사이에 시작해 대기하게 한다.
                        release.await(30, TimeUnit.SECONDS);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                    });
                return null;
              });
      if (!firstDone.await(30, TimeUnit.SECONDS)) {
        throw new IllegalStateException("tx1 이 30초 안에 본문을 끝내지 못했습니다");
      }
      // 본문이 성공했다면 tx1 은 release 를 기다리는 중이라 끝나 있지 않다 — 끝나 있으면 본문 실패이므로 그 예외를 드러낸다.
      Thread.sleep(50);
      if (tx1.isDone()) {
        tx1.get();
        throw new IllegalStateException("tx1 이 커밋 전에 멈추지 않고 끝났습니다");
      }
      Future<Outcome<T>> tx2 =
          pool.submit(
              () -> {
                TenantContext.set(tenantId);
                try {
                  return new Outcome<>(false, second.get(), null);
                } catch (Throwable e) {
                  return new Outcome<T>(false, null, e);
                } finally {
                  TenantContext.clear();
                }
              });
      Thread.sleep(BLOCK_OBSERVE_MS);
      boolean blocked = !tx2.isDone();
      release.countDown();
      tx1.get(30, TimeUnit.SECONDS);
      Outcome<T> out = tx2.get(30, TimeUnit.SECONDS);
      return new Outcome<>(blocked, out.secondResult(), out.secondError());
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }
}
