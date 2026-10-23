package com.smartfirehub.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 트랜잭션 템플릿 빈 구성 회귀 테스트(WD-207).
 *
 * <p>readOnly 템플릿 빈을 추가하면 Boot 가 기본 {@code transactionTemplate} 을 만들지 않으므로, 한정자 없는 주입이 readOnly 를
 * 받으면 PG 쓰기가 전부 실패한다. 서비스를 손으로 조립하는 Tx 테스트로는 잡히지 않아 컨텍스트에서 직접 확인한다.
 */
class TransactionTemplateConfigTest extends IntegrationTestBase {

  @Autowired private PlatformTransactionManager transactionManager;

  /** 한정자 없는 주입 — 기본(쓰기) 템플릿이어야 한다. */
  @Autowired private TransactionTemplate defaultTemplate;

  @Autowired
  @Qualifier(TransactionTemplateConfig.READ_ONLY)
  private TransactionTemplate readOnlyTemplate;

  /** 기본 템플릿은 readOnly 가 아니고, 한정자 템플릿만 readOnly 다. 둘 다 @Primary 매니저(TenantAware)를 쓴다. */
  @Test
  void defaultIsWritable_qualifiedIsReadOnly_sameManager() {
    assertThat(defaultTemplate.isReadOnly()).isFalse();
    assertThat(readOnlyTemplate.isReadOnly()).isTrue();
    assertThat(defaultTemplate).isNotSameAs(readOnlyTemplate);
    assertThat(defaultTemplate.getTransactionManager()).isSameAs(transactionManager);
    assertThat(readOnlyTemplate.getTransactionManager()).isSameAs(transactionManager);
  }
}
