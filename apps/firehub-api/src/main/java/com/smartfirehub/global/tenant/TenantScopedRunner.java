package com.smartfirehub.global.tenant;

import com.smartfirehub.tenant.repository.TenantRepository;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ACTIVE 테넌트를 순회해 테넌트별로 작업을 실행한다.
 *
 * <p>왜 필요한가: `@Scheduled`·`@PostConstruct` 처럼 원 HTTP 요청이 없는 경로에는 승계할 테넌트가
 * 없다. {@link TenantContextTaskDecorator} 로는 해결할 수 없고, 테넌트를 명시적으로 순회하는 것이
 * 유일한 방법이다. 순회하지 않으면 RLS 가 전부 차단해 정리·폴링 작업이 조용히 무동작이 된다.
 *
 * <p>한 테넌트의 실패가 나머지를 막지 않는다 — 스케줄러는 다음 주기까지 기다려야 하므로,
 * 한 테넌트의 데이터 문제로 전체가 멈추면 장애가 전파된다.
 *
 * <p><b>이 클래스는 ThreadLocal 만 세운다 — 트랜잭션은 열지 않는다.</b> RLS GUC 는
 * {@code TenantAwareTransactionManager.doBegin} 에서만 주입되므로, 콜백 안의 DB 접근은 반드시
 * 트랜잭션 안에서 일어나야 한다. 이 클래스 자체는 그것을 보장하지 않는다 — 콜백이 클래스 레벨
 * {@code @Transactional} 이 붙은 리포지토리를 거치거나(REQUIRED 라 이미 열린 트랜잭션에는 합류)
 * 콜백 안에서 {@code TransactionTemplate} 으로 직접 감싸야 GUC 가 주입된다. 콜백이 {@code
 * DSLContext} 를 트랜잭션 없이 직접 쓰면 GUC 가 비어 조용히 0행이 되니 주의할 것.
 */
@Slf4j
@Component
public class TenantScopedRunner {

  private final TenantRepository tenantRepository;

  public TenantScopedRunner(TenantRepository tenantRepository) {
    this.tenantRepository = tenantRepository;
  }

  /**
   * ACTIVE 테넌트를 하나씩 컨텍스트에 세우고 작업을 실행한다. 한 테넌트가 실패해도 나머지는 계속한다.
   *
   * <p>정리는 {@link TenantContext#runScoped} 에 맡긴다 — 즉 {@code clear} 가 아니라 <b>진입 전 값
   * 복원</b>이다. 오늘 호출자는 전부 {@code @Scheduled}/{@code @PostConstruct} 라 진입 전 값이 없어
   * 결과가 같지만, 이미 테넌트가 있는 경로에서 "전 테넌트 대상 작업"을 부르는 호출자가 생기면
   * 무조건 clear 는 <b>호출자의 컨텍스트를 빼앗아</b> 그 뒤 문장을 조용히 0행으로 만든다.
   * 형제 헬퍼({@code runScoped}, {@code TenantContextTaskDecorator})와 의미론을 맞춰 그 함정을 닫는다.
   */
  public void forEachActiveTenant(Consumer<Long> work) {
    for (Long tenantId : tenantRepository.findActiveTenantIds()) {
      try {
        TenantContext.runScoped(tenantId, () -> work.accept(tenantId));
      } catch (Exception e) {
        log.error("테넌트 {} 배경 작업 실패 — 나머지 테넌트는 계속 처리한다", tenantId, e);
      }
    }
  }
}
