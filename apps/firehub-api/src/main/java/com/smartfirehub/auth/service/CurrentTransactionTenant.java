package com.smartfirehub.auth.service;

import com.smartfirehub.global.tenant.TenantAwareTransactionManager;
import com.smartfirehub.global.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * <b>이미 열려 있는</b> 트랜잭션에 RLS GUC {@code app.tenant_id} 를 뒤늦게 심는다.
 *
 * <p>정상 경로는 {@link TenantAwareTransactionManager} 다 — {@link TenantContext} 를 읽어
 * {@code doBegin} 에서 GUC 를 주입한다. 그런데 그 방식은 "트랜잭션이 열리기 <b>전에</b> 테넌트를
 * 안다"를 전제한다. 로그인은 그 전제를 만족하지 못한다: {@code /auth/login} 은 permitAll 이라 진입
 * 시점에 컨텍스트가 없고, 활성 테넌트는 비밀번호 검증과 멤버십 조회를 <b>마친 뒤</b>에야 정해진다.
 * 그 시점에 {@code TenantContext.set} 을 해도 GUC 는 이미 확정(미설정)이라 아무 효과가 없다.
 *
 * <p>대안으로 감사 기록만 {@code REQUIRES_NEW} 로 분리하는 방법도 있으나, 별도 커넥션·별도 커밋이
 * 되어 바깥 트랜잭션이 아직 커밋하지 않은 행(예: 같은 트랜잭션에서 만들어진 사용자)을 보지 못해
 * FK 위반이 난다. 여기서는 같은 트랜잭션·같은 커넥션을 유지한다.
 *
 * <p>{@code set_config(..., true)} 의 세 번째 인자 {@code true} 는 <b>트랜잭션-로컬</b>이다 —
 * 커밋/롤백과 함께 값이 사라지므로 커넥션이 풀에 반납된 뒤 다음 요청이 테넌트를 물려받는 사고가
 * 일어나지 않는다. 트랜잭션 매니저가 쓰는 것과 같은 형태다.
 *
 * <p><b>남용 금지</b>: 트랜잭션이 열리기 전에 테넌트를 알 수 있는 경로는 전부
 * {@code TenantContext} + 트랜잭션 경계 분리(예: {@code SignupTransaction})로 해결한다. 이 클래스는
 * "테넌트가 트랜잭션보다 늦게 결정되는" 예외 경로만을 위한 것이다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class CurrentTransactionTenant {

  private final DSLContext dsl;

  /**
   * 현재 트랜잭션의 {@code app.tenant_id} 를 설정한다. 이후 이 트랜잭션에서 실행되는 문장은 해당
   * 테넌트의 RLS 정책을 적용받고, {@code tenant_id} 컬럼 DEFAULT 도 이 값으로 채워진다.
   *
   * <p>트랜잭션이 없으면 아무것도 하지 않는다 — 트랜잭션 밖에서 부르면 커넥션이 매 문장마다 달라져
   * GUC 가 어디에 붙었는지 보장할 수 없기 때문이다. 조용히 넘기지 않고 경고를 남긴다.
   */
  void apply(long tenantId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      log.warn("트랜잭션 밖에서 app.tenant_id({}) 를 심으려 했다 — 무시한다", tenantId);
      return;
    }
    dsl.execute("select set_config('app.tenant_id', ?, true)", String.valueOf(tenantId));
  }
}
