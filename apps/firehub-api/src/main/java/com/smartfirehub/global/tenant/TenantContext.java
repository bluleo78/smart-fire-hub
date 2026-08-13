package com.smartfirehub.global.tenant;

/**
 * 현재 요청의 활성 테넌트를 보관하는 요청 스코프 홀더.
 *
 * <p>이 값은 {@link TenantAwareTransactionManager} 가 트랜잭션 시작 시 PostgreSQL GUC
 * {@code app.tenant_id} 로 주입하고, RLS 정책이 그 GUC 를 읽어 행을 격리한다.
 *
 * <p><b>반드시 finally 에서 {@link #clear()} 를 호출해야 한다.</b> 서블릿 컨테이너는 스레드를 풀에서
 * 재사용하므로, 정리하지 않으면 다음 요청이 이전 요청의 테넌트를 물려받아 크로스 테넌트 접근이 된다.
 */
public final class TenantContext {

  private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

  private TenantContext() {}

  /** 현재 스레드의 활성 테넌트를 설정한다. */
  public static void set(Long tenantId) {
    CURRENT.set(tenantId);
  }

  /** 현재 스레드의 활성 테넌트. 미설정이면 null — 이 경우 GUC 가 비어 RLS 가 모든 행을 차단한다. */
  public static Long get() {
    return CURRENT.get();
  }

  /** 현재 스레드의 테넌트를 제거한다. 요청/작업 종료 시 finally 에서 반드시 호출한다. */
  public static void clear() {
    CURRENT.remove();
  }
}
