package com.smartfirehub.global.tenant;

import com.smartfirehub.tenant.repository.TenantRepository;
import java.util.Collection;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 테넌트를 순회해 테넌트별로 작업을 실행한다. 목록의 <b>출처</b>는 두 가지다 — ACTIVE 테넌트
 * ({@link #forEachActiveTenant}) 이거나, 호출자가 정해서 넘긴 목록({@link #forEachTenant}).
 * 루프·격리·로깅은 두 경우 모두 이 클래스가 소유한다.
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
    forEachTenant(tenantRepository.findActiveTenantIds(), work);
  }

  /**
   * 호출자가 <b>출처를 정해서 넘긴</b> 테넌트 목록을 순회한다. 격리·로깅 규약은
   * {@link #forEachActiveTenant} 와 같다.
   *
   * <p><b>왜 출처를 인자로 받는가.</b> 아웃박스 계열 배경 잡(워커·스위퍼·보존잡)은 ACTIVE 테넌트가
   * 아니라 {@code outbox_tenant_ids} 가 돌려주는 "실제로 일감이 있는 테넌트"를 돌아야 한다 —
   * ACTIVE 만 돌면 비활성·정지 테넌트의 행이 영원히 처리되지 않는다(계획 R4). R4 가 배제한 것은
   * <b>출처</b>({@code findActiveTenantIds})이지 루프가 아닌데, 출처가 이 클래스에 용접돼 있어서
   * 세 호출자가 루프·try/catch·로그 문장을 통째로 베껴 갔다. 출처만 호출자에게 돌려주면
   * 그 셋이 이 메서드를 공유할 수 있다.
   *
   * <p><b>{@code Exception} 이 아니라 {@code Throwable} 을 잡는다.</b> 베껴진 사본들이 이미
   * 갈라져 있었고(워커만 {@code Throwable}), 넓은 쪽이 옳다 — {@code Error} 가 빠져나가면 남은
   * 테넌트가 통째로 처리되지 않아 이 메서드가 존재하는 이유("나머지 테넌트는 계속 처리한다")가
   * 무너진다. 넓히는 방향은 기존 호출자를 퇴행시키지 않지만 좁히는 방향은 퇴행시킨다.
   *
   * <p>반환값을 누적해야 하는 호출자는 콜백 밖에 누산기를 두고 콜백 안에서 더한다 — 이 메서드가
   * 합계 타입을 알 필요가 없다.
   */
  public void forEachTenant(Collection<Long> tenantIds, Consumer<Long> work) {
    for (Long tenantId : tenantIds) {
      try {
        TenantContext.runScoped(tenantId, () -> work.accept(tenantId));
      } catch (Throwable t) {
        log.error("테넌트 {} 배경 작업 실패 — 나머지 테넌트는 계속 처리한다", tenantId, t);
      }
    }
  }
}
