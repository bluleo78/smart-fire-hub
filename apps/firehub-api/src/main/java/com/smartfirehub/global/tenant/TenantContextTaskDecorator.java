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
 *
 * <p><b>정리는 무조건 clear 가 아니라 실행 전 값 복원이다.</b> 풀 스레드에서는 실행 전 값이 없어
 * 결과가 clear 와 같지만, 거부 정책이 {@code CallerRunsPolicy} 인 풀에서는 큐가 포화되면 작업이
 * <b>제출 스레드에서 인라인 실행</b>된다. 그때 무조건 clear 하면 제출자의 테넌트를 지워 버려,
 * 뒤이어 제출되는 작업이 null 을 캡처하고 fail-closed 로 조용히 무동작이 된다. 복원이면 그 경로가
 * 사라진다.
 */
public class TenantContextTaskDecorator implements TaskDecorator {

  @Override
  public Runnable decorate(Runnable runnable) {
    Long tenantId = TenantContext.get();
    return () -> {
      Long previous = TenantContext.get();
      if (tenantId == null) {
        // 제출 시점에 테넌트가 없었다 — 없던 값을 만들어내지 않는다(fail-closed).
        TenantContext.clear();
      } else {
        TenantContext.set(tenantId);
      }
      try {
        runnable.run();
      } finally {
        if (previous == null) {
          TenantContext.clear();
        } else {
          TenantContext.set(previous);
        }
      }
    };
  }
}
