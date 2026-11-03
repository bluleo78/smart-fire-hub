package com.smartfirehub.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.smartfirehub.global.platformdb.PlatformTenantSession;
import com.smartfirehub.support.IntegrationTestBase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CurrentTransactionTenant 특성 테스트(WD-486) — SQL 을 {@code PlatformTenantSession} 으로 위임하기 전후에 동작이 같음을
 * 고정한다. 핵심은 "트랜잭션이 없으면 warn 후 무시" 가드가 위임 뒤에도 남는 것이다.
 *
 * <p>가드는 DB 결과로는 검증할 수 없다 — 트랜잭션 밖의 {@code set_config(..., true)} 는 자동 커밋으로 즉시 사라져 가드가 없어도 값이 안
 * 남는다. 그래서 가드 테스트는 목 {@link PlatformTenantSession} 이 호출되는지로 직접 확인한다.
 */
class CurrentTransactionTenantTest extends IntegrationTestBase {

  @Autowired private CurrentTransactionTenant currentTransactionTenant;
  @Autowired private DSLContext dsl;
  @Autowired private TransactionTemplate tx;

  /** 현재 커넥션의 GUC 값을 읽는다(미설정이면 null). */
  private String readGuc() {
    return dsl.fetchOne("select current_setting('app.tenant_id', true)").get(0, String.class);
  }

  @Test
  @DisplayName("트랜잭션 안에서 apply(5) 하면 같은 트랜잭션에서 GUC 가 5 로 읽힌다")
  void applyInsideTransactionPlantsTenant() {
    String guc =
        tx.execute(
            s -> {
              currentTransactionTenant.apply(5L);
              return readGuc();
            });

    assertThat(guc).isEqualTo("5");
  }

  @Test
  @DisplayName("트랜잭션 밖 apply(5) 는 세션 포트를 호출하지 않고 무시한다(가드)")
  void applyOutsideTransactionNeverTouchesSession() {
    PlatformTenantSession session = mock(PlatformTenantSession.class);

    new CurrentTransactionTenant(session).apply(5L);

    verify(session, never()).applyToCurrentTransaction(anyLong());
  }

  @Test
  @DisplayName("트랜잭션 안 apply(5) 는 세션 포트로 위임한다")
  void applyInsideTransactionDelegatesToSession() {
    PlatformTenantSession session = mock(PlatformTenantSession.class);

    tx.executeWithoutResult(s -> new CurrentTransactionTenant(session).apply(5L));

    verify(session).applyToCurrentTransaction(5L);
  }
}
