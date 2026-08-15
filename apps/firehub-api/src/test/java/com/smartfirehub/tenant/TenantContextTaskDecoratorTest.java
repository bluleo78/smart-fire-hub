package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantContextTaskDecorator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * TaskDecorator 가 제출 스레드의 TenantContext 를 워커 스레드로 승계하는지 검증한다.
 *
 * <p>승계가 없으면 @Async 경로에서 GUC 가 비어 RLS 가 전부 차단하고, 예외 없이 0행이 된다.
 */
class TenantContextTaskDecoratorTest {

  private final TenantContextTaskDecorator decorator = new TenantContextTaskDecorator();

  @AfterEach
  void clearContext() {
    TenantContext.clear();
  }

  /**
   * 같은 스레드에서 인라인 실행될 때 호출자의 테넌트가 살아남는지.
   *
   * <p>기본 taskExecutor 는 거부 정책이 {@code CallerRunsPolicy} 라, 큐가 포화되면 작업이 워커가
   * 아니라 <b>제출 스레드</b>에서 그대로 돈다. 정리를 무조건 clear 로 하면 그 순간 제출자의 테넌트가
   * 지워지고, 뒤이어 제출되는 작업이 null 을 캡처해 fail-closed 로 조용히 무동작이 된다 —
   * 파이프라인 완료 이벤트의 리스너 두 개가 정확히 이 순서로 제출된다. 그래서 정리는 clear 가 아니라
   * 진입 전 값 복원이어야 한다. 이 테스트가 그 회귀를 막는다(무조건 clear 로 되돌리면 실패한다).
   */
  @Test
  void inlineExecutionOnCallerThreadPreservesCallerTenant() {
    TenantContext.set(7777L);

    Runnable decorated = decorator.decorate(() -> {});
    decorated.run(); // CallerRunsPolicy 가 하는 것과 동일 — 제출 스레드에서 그대로 실행

    assertThat(TenantContext.get())
        .as("인라인 실행이 호출자의 테넌트를 지우면 이후 제출이 fail-closed 로 무동작이 된다")
        .isEqualTo(7777L);
  }

  @Test
  void decoratedTaskSeesSubmitterTenant() throws Exception {
    TenantContext.set(4242L);
    AtomicReference<Long> seen = new AtomicReference<>();
    Runnable decorated = decorator.decorate(() -> seen.set(TenantContext.get()));

    Thread worker = new Thread(decorated);
    worker.start();
    worker.join();

    assertThat(seen.get()).isEqualTo(4242L);
  }

  @Test
  void workerThreadIsCleanedUpAfterTask() throws Exception {
    // 풀 스레드는 재사용된다. 첫 작업의 테넌트가 남으면 다음 작업이 남의 테넌트로 실행된다.
    ThreadPoolExecutor pool =
        new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(4));
    try {
      TenantContext.set(111L);
      pool.submit(decorator.decorate(() -> {})).get();

      TenantContext.clear(); // 두 번째 제출은 테넌트 없이
      AtomicReference<Long> leaked = new AtomicReference<>(-1L);
      pool.submit(decorator.decorate(() -> leaked.set(TenantContext.get()))).get();

      assertThat(leaked.get()).isNull();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void submitterWithoutTenantLeavesWorkerWithoutTenant() throws Exception {
    TenantContext.clear();
    AtomicReference<Long> seen = new AtomicReference<>(-1L);
    Thread worker = new Thread(decorator.decorate(() -> seen.set(TenantContext.get())));
    worker.start();
    worker.join();

    // fail-closed: 없던 테넌트가 생겨나서는 안 된다
    assertThat(seen.get()).isNull();
  }
}
