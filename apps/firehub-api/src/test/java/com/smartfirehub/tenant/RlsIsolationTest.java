package com.smartfirehub.tenant;

import static com.smartfirehub.support.TenantRlsTestSupport.TENANT_CANARY;
import static com.smartfirehub.support.TenantRlsTestSupport.nextTenantId;
import static com.smartfirehub.support.TenantRlsTestSupport.runInTenantTransaction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;

import com.smartfirehub.support.IntegrationTestBase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** 카나리 테이블로 RLS 격리를 증명한다. 도메인 테이블 정책(P2)이 이 패턴을 그대로 따른다. */
class RlsIsolationTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private TransactionTemplate transactionTemplate;

  // 이 테스트가 삽입하는 테넌트 id 목록. IntegrationTestBase 가 @Transactional 이 아니라 커밋된
  // 카나리 행이 다음 실행까지 남으므로, 매 테스트 후 자신이 만든 행을 지워 재실행 시에도
  // countAs(...)==1 같은 절대 카운트 단언이 안전하게 통과하도록 한다. 실행마다 고유한 id를 쓰므로
  // 다른 프로세스/이전 실행의 잔여 행과도 섞이지 않는다.
  private final long tenant101 = nextTenantId();
  private final long tenant102 = nextTenantId();
  private final long tenant103 = nextTenantId();
  private final long tenant104 = nextTenantId();

  @AfterEach
  void tearDown() {
    for (long tenantId : new long[] {tenant101, tenant102, tenant103, tenant104}) {
      runInTenantTransaction(transactionTemplate, tenantId, () -> dsl.deleteFrom(TENANT_CANARY).execute());
    }
  }

  /** 테넌트 컨텍스트에서 카나리 행을 하나 만든다. tenant_id 는 컬럼 DEFAULT 가 GUC 에서 채운다. */
  private void insertAs(long tenantId, String val) {
    runInTenantTransaction(
        transactionTemplate,
        tenantId,
        () -> dsl.insertInto(TENANT_CANARY).set(field(name("val"), String.class), val).execute());
  }

  private int countAs(Long tenantId) {
    return runInTenantTransaction(transactionTemplate, tenantId, () -> dsl.fetchCount(TENANT_CANARY));
  }

  @Test
  @DisplayName("다른 테넌트의 행은 보이지 않는다")
  void rowsAreInvisibleAcrossTenants() {
    insertAs(tenant101, "for-101");

    assertThat(countAs(tenant101)).isEqualTo(1);
    assertThat(countAs(tenant102)).isZero();
  }

  @Test
  @DisplayName("INSERT 시 tenant_id 를 명시하지 않아도 GUC 값이 채워진다")
  void tenantIdIsFilledFromGucOnInsert() {
    insertAs(tenant103, "default-filled");

    Long stored =
        runInTenantTransaction(
            transactionTemplate,
            tenant103,
            () ->
                dsl.select(field(name("tenant_id"), Long.class))
                    .from(TENANT_CANARY)
                    .where(field(name("val"), String.class).eq("default-filled"))
                    .fetchOne(0, Long.class));

    assertThat(stored).isEqualTo(tenant103);
  }

  @Test
  @DisplayName("테넌트 컨텍스트가 없으면 아무 행도 보이지 않는다(fail-closed)")
  void nothingIsVisibleWithoutTenantContext() {
    insertAs(tenant104, "for-104");

    assertThat(countAs(null)).isZero();
  }
}
