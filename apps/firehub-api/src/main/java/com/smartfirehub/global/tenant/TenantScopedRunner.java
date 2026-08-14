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
 * 트랜잭션 안에서 일어나야 한다. 이 저장소에서는 도메인 리포지토리에 클래스 레벨
 * {@code @Transactional} 이 붙어 있어 그 조건이 충족된다(REQUIRED 라 이미 열린 트랜잭션에는 합류).
 * 콜백이 리포지토리를 거치지 않고 {@code DSLContext} 를 직접 쓰면 GUC 가 비어 조용히 0행이 되니
 * 주의할 것 — 그 경우 콜백 안에서 {@code TransactionTemplate} 으로 감싸야 한다.
 */
@Slf4j
@Component
public class TenantScopedRunner {

  private final TenantRepository tenantRepository;

  public TenantScopedRunner(TenantRepository tenantRepository) {
    this.tenantRepository = tenantRepository;
  }

  public void forEachActiveTenant(Consumer<Long> work) {
    for (Long tenantId : tenantRepository.findActiveTenantIds()) {
      TenantContext.set(tenantId);
      try {
        work.accept(tenantId);
      } catch (Exception e) {
        log.error("테넌트 {} 배경 작업 실패 — 나머지 테넌트는 계속 처리한다", tenantId, e);
      } finally {
        TenantContext.clear();
      }
    }
  }
}
