package com.smartfirehub.tenant;

import static com.smartfirehub.support.TenantRlsTestSupport.runInTenantTransaction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** TenantContext 의 값이 트랜잭션 시작 시 app.tenant_id GUC 로 주입되는지 검증한다. */
class TenantContextGucTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private TransactionTemplate transactionTemplate;

  @AfterEach
  void tearDown() {
    // runInTenantTransaction 은 각 호출이 끝날 때 이미 컨텍스트를 정리하지만, 풀 스레드에 남을 가능성을
    // 완전히 배제하기 위한 안전망으로 한 번 더 지운다.
    TenantContext.clear();
  }

  private String readGuc() {
    return dsl.select(field("current_setting('app.tenant_id', true)", String.class))
        .fetchOne(0, String.class);
  }

  @Test
  @DisplayName("TenantContext 에 값이 있으면 트랜잭션 안에서 GUC 로 읽힌다")
  void gucIsInjectedFromTenantContext() {
    String guc = runInTenantTransaction(transactionTemplate, 7L, this::readGuc);

    assertThat(guc).isEqualTo("7");
  }

  @Test
  @DisplayName("TenantContext 가 비어 있으면 GUC 를 설정하지 않는다(fail-closed)")
  void gucIsNotSetWhenContextIsEmpty() {
    // tenantId=null → 헬퍼가 TenantContext.set(null) 을 호출하는 것과 같아, 컨텍스트가 비어 있는
    // 상태를 그대로 재현한다.
    String guc = runInTenantTransaction(transactionTemplate, null, this::readGuc);

    // 미설정이면 NULL, 같은 커넥션에서 이전에 설정된 적이 있으면 빈 문자열.
    // 어느 쪽이든 NULLIF(...,'') 를 거치면 NULL 이 되어 행이 보이지 않는다.
    assertThat(guc).satisfiesAnyOf(
        v -> assertThat(v).isNull(),
        v -> assertThat(v).isEmpty());
  }

  @Test
  @DisplayName("GUC 는 트랜잭션-로컬이라 트랜잭션이 끝나면 값이 남지 않는다")
  void gucDoesNotLeakAcrossTransactions() {
    runInTenantTransaction(transactionTemplate, 7L, this::readGuc);

    // 이 두 번째 읽기는 컨텍스트가 없는 새 트랜잭션에서 이루어져야 하므로, 앞의 helper 호출과 별개로
    // tenantId=null 로 다시 호출한다(같은 컨텍스트를 이어 쓰지 않는다).
    String afterClear = runInTenantTransaction(transactionTemplate, null, this::readGuc);

    assertThat(afterClear).isNullOrEmpty();
  }
}
