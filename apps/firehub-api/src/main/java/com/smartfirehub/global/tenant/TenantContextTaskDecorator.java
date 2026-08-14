package com.smartfirehub.global.tenant;

import org.springframework.core.task.TaskDecorator;

/**
 * 제출 스레드의 테넌트를 비동기 워커 스레드로 승계한다.
 *
 * <p>왜 필요한가: 격리 값은 트랜잭션-로컬 GUC `app.tenant_id` 로 전달되고, 그 값의 출처는
 * ThreadLocal 인 {@link TenantContext} 다. `@Async` 는 다른 스레드에서 돌므로 승계하지 않으면
 * 컨텍스트가 비고, RLS 정책이 전부 차단해 <b>예외도 로그도 없이 0행</b>이 된다.
 *
 * <p>작업이 끝나면 반드시 정리한다 — 풀 스레드는 재사용되므로 남겨 두면 다음 작업이 남의
 * 테넌트로 실행된다(크로스테넌트 쓰기).
 */
public class TenantContextTaskDecorator implements TaskDecorator {

  @Override
  public Runnable decorate(Runnable runnable) {
    Long tenantId = TenantContext.get();
    return () -> {
      if (tenantId == null) {
        // 제출 시점에 테넌트가 없었다 — 없던 값을 만들어내지 않는다(fail-closed).
        TenantContext.clear();
      } else {
        TenantContext.set(tenantId);
      }
      try {
        runnable.run();
      } finally {
        TenantContext.clear();
      }
    };
  }
}
