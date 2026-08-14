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

  /**
   * 현재 테넌트를 반환하고, 없으면 예외를 던진다.
   *
   * <p>배경 잡 enqueue 처럼 "테넌트가 없으면 애초에 잘못된 호출"인 지점에서 쓴다. 조용히 null 을
   * 흘려보내면 잡이 실행 시점에 0행으로 무동작해 원인 추적이 불가능해진다.
   */
  public static long require() {
    Long tenantId = get();
    if (tenantId == null) {
      throw new IllegalStateException("테넌트 컨텍스트가 없는 상태에서 배경 잡을 예약할 수 없다");
    }
    return tenantId;
  }

  /**
   * 주어진 테넌트로 작업을 실행하고, 끝나면 <b>진입 전 상태로 되돌린다</b>.
   *
   * <p>배경 잡 진입점(JobRunr {@code @Job})에서 쓴다. 손으로 {@code set} → {@code try} →
   * {@code finally clear} 를 쓰면 "본문 첫 문장을 try 밖에 두면 컨텍스트가 샌다" 같은 규칙을 사람이
   * 기억해야 하고, 실제로 그 실수가 한 번 있었다. 여기서 구조로 강제한다.
   *
   * <p>{@code clear()} 가 아니라 <b>복원</b>인 이유: 같은 메서드가 잡 진입점(진입 전 null)과 이미
   * 컨텍스트가 있는 경로(예: {@code @Async} 리스너) 양쪽에서 불릴 수 있기 때문이다. 무조건 지우면
   * 후자에서 호출자의 컨텍스트를 빼앗아, 그 뒤에 문장이 하나라도 추가되는 순간 조용히 0행이 된다.
   */
  public static void runScoped(long tenantId, Runnable work) {
    Long previous = get();
    set(tenantId);
    try {
      work.run();
    } finally {
      if (previous == null) {
        clear();
      } else {
        set(previous);
      }
    }
  }
}
