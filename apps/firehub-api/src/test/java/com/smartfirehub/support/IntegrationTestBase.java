package com.smartfirehub.support;

import com.smartfirehub.global.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.BeforeTransaction;

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
}
