package com.smartfirehub.support;

import com.smartfirehub.global.tenant.TenantContext;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.BeforeTransaction;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 통합 테스트 기반 클래스.
 *
 * <p>운영에서는 {@code JwtAuthenticationFilter} 가 요청마다 {@link TenantContext} 를 세우지만,
 * 테스트는 필터를 거치지 않고 서비스·리포지토리를 직접 호출한다. 컨텍스트가 없으면 트랜잭션
 * 매니저가 GUC 를 주입하지 않아 {@code tenant_id} DEFAULT 가 NULL 이 되고, NOT NULL 위반으로
 * 삽입이 실패한다. 그래서 각 테스트 전에 기본 테넌트를 세워 요청 스코프를 흉내 낸다.
 *
 * <p><b>세션 레벨 GUC(connection-init-sql)로 대신하지 말 것.</b> 그렇게 하면 트랜잭션 없이 도는
 * 코드에도 GUC 가 붙어, 운영에서만 조용히 빈 결과가 되는 결함을 테스트가 가려 버린다. 여기서
 * 세우는 것은 어디까지나 ThreadLocal 이며, GUC 는 여전히 트랜잭션이 열릴 때만 주입된다 —
 * 즉 "트랜잭션 없이 RLS 테이블을 만지는" 결함은 이 설정으로 가려지지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

  /** V81 이 시드한 기본 테넌트. 대부분의 픽스처가 이 테넌트에 속한다. */
  protected static final long DEFAULT_TEST_TENANT_ID = 1L;

  /**
   * 두 훅이 모두 필요하다. 클래스 레벨 {@code @Transactional} 테스트에서는
   * {@code TransactionalTestExecutionListener} 가 {@code @BeforeEach} <b>보다 먼저</b> 트랜잭션을
   * 열고 그 시점에 GUC 가 주입되므로 {@code @BeforeTransaction} 이어야 늦지 않고, 반대로
   * {@code @Transactional} 이 없는 클래스에서는 {@code @BeforeTransaction} 이 아예 호출되지 않아
   * {@code @BeforeEach} 가 필요하다. 같은 값을 두 번 set 하는 것은 멱등이다.
   */
  @BeforeTransaction
  @BeforeEach
  void setDefaultTenantContext() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
  }

  @AfterEach
  void clearTenantContext() {
    TenantContext.clear();
  }

  /**
   * 픽스처 트랜잭션용 템플릿. RLS 테이블은 트랜잭션이 열릴 때만 GUC 가 주입되므로, 테스트가 직접
   * 심는 픽스처 행은 반드시 트랜잭션 안에서 써야 한다.
   *
   * <p>지금까지 40여 개 테스트가 각자 이 필드를 선언해 왔다. <b>기존 테스트의 개종은 아직 하지
   * 않았다</b> — 이 밴드는 {@link #inTenantFixture} 를 도입하고 이 밴드가 만지는 테스트에만
   * 적용한다. 43파일 일괄 개종은 테넌트 인자 오버로드와 {@code TenantRlsTestSupport} 6시그니처
   * 변경까지 물려 있어 리뷰 표면이 폭발하므로 다음 밴드로 미룬다.
   */
  @Autowired protected TransactionTemplate fixtureTransactionTemplate;

  /**
   * 픽스처를 <b>기본 테넌트</b> 컨텍스트 + 트랜잭션 안에서 실행한다. 진입 전 컨텍스트는 복원된다.
   *
   * <p><b>경계 — 반드시 지킬 것.</b> 이 헬퍼는 <b>픽스처 생성·정리·검증 조회만</b> 감싼다. 검증
   * 대상 프로덕션 호출을 이 안에 넣지 마라. 넣는 순간 테스트가 열어 준 트랜잭션이 GUC 를 공급해,
   * "프로덕션 경로가 스스로 트랜잭션·테넌트 컨텍스트를 세우지 못한다"는 배선 결함을 영구히 가린다
   * — 이 이니셔티브에서 다섯 번 반복된 실패 패턴이며, 클래스 레벨 {@code @Transactional} 금지
   * 규칙과 같은 이유다. 검증 대상 호출은 이 블록 <b>밖</b>에 둔다.
   *
   * <p>이름을 {@code inTransaction} 이 아니라 {@code inTenantFixture} 로 둔 것도 그 때문이다 —
   * 호출부만 읽어도 "여긴 픽스처 구간"이라는 경계가 리뷰에서 보이게 하려는 것이다.
   */
  protected void inTenantFixture(Runnable action) {
    inTenantFixture(DEFAULT_TEST_TENANT_ID, action);
  }

  /** {@link #inTenantFixture(Runnable)} 의 값 반환 버전. */
  protected <T> T inTenantFixture(Supplier<T> action) {
    return inTenantFixture(DEFAULT_TEST_TENANT_ID, action);
  }

  /**
   * 지정한 테넌트로 픽스처를 실행한다(경계는 {@link #inTenantFixture(Runnable)} 과 동일).
   *
   * @param tenantId {@code null} 이면 "컨텍스트가 비어 있는 상태"를 재현한다 — fail-closed 검증용.
   *     이 때문에 원시 타입 {@code long} 이 아니라 {@link Long} 이다.
   */
  protected void inTenantFixture(Long tenantId, Runnable action) {
    TenantRlsTestSupport.runInTenantTransaction(fixtureTransactionTemplate, tenantId, action);
  }

  /** {@link #inTenantFixture(Long, Runnable)} 의 값 반환 버전. */
  protected <T> T inTenantFixture(Long tenantId, Supplier<T> action) {
    return TenantRlsTestSupport.runInTenantTransaction(fixtureTransactionTemplate, tenantId, action);
  }
}
