package com.smartfirehub.securitylevel.pythonread;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.securitylevel.pythonread.PythonReadGrantBackend.NarrowResult;
import com.smartfirehub.support.DataStorePostgresTestContainer;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.HashSet;
import java.util.Set;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 리뷰 9(WD-624) — 분리 설치({@code firehub.data-store.postgres.separate=true})에서는 커밋 직전 데이터셋 좁히기를 <b>확인
 * 못 함</b>으로 다룬다. 데이터 저장소 연결이 그 트랜잭션에 처음 묶이는 것이 커밋 직전 단계면 그 연결은 플랫폼 커밋 뒤(완료 콜백)에서야 커밋되고 실패해도 로그만 남아,
 * 커밋 응답 전에 회수가 커밋됐다고 확인할 수 없기 때문이다. 그래서 데이터 저장소를 건드리지 않고(연결을 묶지 않고) 막을 슬롯만 돌려준다 — 리스너가 대체 경로(커밋 직후
 * 그 슬롯 끊기 + 실행기 뒷정리)로 막는다.
 */
class PythonReadGrantSplitModeTest extends IntegrationTestBase {

  @DynamicPropertySource
  static void separateDataStoreProperties(DynamicPropertyRegistry registry) {
    DataStorePostgresTestContainer.registerSeparateDataStoreProperties(registry);
  }

  @Autowired private PythonReadGrants grants;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;

  /** 공개 → 민감: 확인 못 함 + 막을 슬롯 {1,2}. 이 트랜잭션에 새 자원(데이터 저장소 연결)을 묶지 않는다. */
  @Test
  void narrowing_inSplitMode_isUnconfirmed_withExposedSlots_andNeverBindsDataStore() {
    SecurityFixture fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    long from = fx.levelId("공개");
    long to = fx.levelId("민감");

    NarrowResult result =
        TenantRlsTestSupport.runInTenantTransaction(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () -> {
              Set<Object> before =
                  new HashSet<>(TransactionSynchronizationManager.getResourceMap().keySet());
              NarrowResult r = grants.narrowDatasetInCurrentTx(1L, from, to);
              assertThat(TransactionSynchronizationManager.getResourceMap().keySet())
                  .as("데이터 저장소 연결을 이 트랜잭션에 묶지 않는다")
                  .isEqualTo(before);
              return r;
            });

    assertThat(result.confirmed()).isFalse();
    assertThat(result.exposedSlots()).isEqualTo(Set.of(1, 2));
    assertThat(result.needsFallback()).isTrue();
  }

  /** 넓어지는 변경(민감 → 공개)은 분리 설치에서도 막을 슬롯이 없어 대체 경로가 아무것도 끊지 않는다. */
  @Test
  void wideningChange_inSplitMode_needsNoFallback() {
    SecurityFixture fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    long from = fx.levelId("민감");
    long to = fx.levelId("공개");

    NarrowResult result =
        TenantRlsTestSupport.runInTenantTransaction(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () -> grants.narrowDatasetInCurrentTx(1L, from, to));

    assertThat(result.needsFallback()).isFalse();
  }
}
