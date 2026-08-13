package com.smartfirehub.support;

import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.global.tenant.TenantContext;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.jooq.Table;
import org.springframework.transaction.support.TransactionTemplate;

// RLS/TenantContext 를 다루는 테스트(RlsIsolationTest/TenantContextGucTest/
// NonTransactionalRlsReadGuardTest)가 각자 다른 rigor로 "TenantContext 설정 → 트랜잭션 실행 →
// finally 에서 컨텍스트 해제"를 반복 구현하고 있었다(일부는 예외 시 컨텍스트가 새는 결함이 있었다).
// 이후 단계에서 도메인 테이블마다 비슷한 RLS 테스트가 추가되므로, 그 반복만 여기서 공유하고 —
// 각 테스트가 무엇을 단언할지는 건드리지 않는다(과잉 일반화하지 않는다).
public final class TenantRlsTestSupport {

  private TenantRlsTestSupport() {}

  /** RLS 격리 검증용 카나리 테이블. 두 테스트가 각자 선언하던 것을 하나로 모은다. */
  public static final Table<?> TENANT_CANARY = table(name("tenant_canary"));

  // nanoTime 기반으로 시작해, 프로세스마다 겹치지 않는 값에서 증가시킨다. 실행마다 고유해야
  // 공유 테스트 DB 에 남는 커밋된 카나리 행이 다음 실행과 섞이지 않는다.
  private static final AtomicLong NEXT_TENANT_ID = new AtomicLong(900_000_000L + System.nanoTime() % 1_000_000L);

  /** 실행마다 고유한 테스트용 테넌트 id를 하나 발급한다. */
  public static long nextTenantId() {
    return NEXT_TENANT_ID.incrementAndGet();
  }

  /**
   * 주어진 테넌트 컨텍스트를 설정하고 트랜잭션 안에서 action 을 실행한 뒤, 성공/예외 여부와 관계없이
   * finally 에서 컨텍스트를 해제한다(결과 없음 버전).
   */
  public static void runInTenantTransaction(
      TransactionTemplate transactionTemplate, Long tenantId, Runnable action) {
    TenantContext.set(tenantId);
    try {
      transactionTemplate.executeWithoutResult(status -> action.run());
    } finally {
      TenantContext.clear();
    }
  }

  /** 위와 동일하되 트랜잭션 실행 결과를 반환한다(값 반환 버전). */
  public static <T> T runInTenantTransaction(
      TransactionTemplate transactionTemplate, Long tenantId, Supplier<T> action) {
    TenantContext.set(tenantId);
    try {
      return transactionTemplate.execute(status -> action.get());
    } finally {
      TenantContext.clear();
    }
  }
}
