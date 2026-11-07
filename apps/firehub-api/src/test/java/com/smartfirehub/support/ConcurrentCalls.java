package com.smartfirehub.support;

import com.smartfirehub.global.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 같은 작업을 여러 스레드에서 동시에 출발시키는 테스트 도구(WD-584 — 같은 id 동시 삭제).
 *
 * <p>각 스레드는 운영의 요청 스레드처럼 자기 {@link TenantContext} 를 세우고, {@link CyclicBarrier} 로 한꺼번에 출발해 서비스 호출을
 * 감싸지 않은 채(프로덕션 호출이 스스로 트랜잭션을 연다) 실행한다. 결과는 호출 순서대로 성공이면 {@code null}, 실패면 던진 예외다.
 *
 * <p>테스트 커넥션 풀이 2개라 실제로 DB 에서 겹치는 요청은 둘 정도다 — 그래서 경합을 결정적으로 보려는 테스트는 {@link PausedTransactionRace}
 * 를 함께 쓴다. 이 도구는 「여러 건이 동시에 와도 성공은 1건」이라는 바깥 계약을 본다.
 */
public final class ConcurrentCalls {

  private ConcurrentCalls() {}

  /**
   * {@code call} 을 {@code n} 개 스레드에서 동시에 실행한다.
   *
   * @param n 동시 호출 수
   * @param tenantId 각 스레드에 세울 테넌트
   * @param call 검증 대상 프로덕션 호출(감싸지 않는다)
   * @return 호출별 결과 — 성공 {@code null}, 실패 예외
   */
  public static List<Throwable> run(int n, long tenantId, Runnable call) throws Exception {
    CyclicBarrier barrier = new CyclicBarrier(n);
    ExecutorService pool = Executors.newFixedThreadPool(n);
    List<Future<Throwable>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < n; i++) {
        futures.add(
            pool.submit(
                () -> {
                  TenantContext.set(tenantId);
                  try {
                    barrier.await(30, TimeUnit.SECONDS);
                    call.run();
                    return null;
                  } catch (Throwable e) {
                    return e;
                  } finally {
                    TenantContext.clear();
                  }
                }));
      }
      List<Throwable> results = new ArrayList<>();
      for (Future<Throwable> f : futures) {
        results.add(f.get(120, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }
}
