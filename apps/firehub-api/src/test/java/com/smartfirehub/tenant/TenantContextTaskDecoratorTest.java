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
