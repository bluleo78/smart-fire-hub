package com.smartfirehub.settings;

import static com.smartfirehub.support.TenantRlsTestSupport.createActiveTenant;
import static com.smartfirehub.support.TenantRlsTestSupport.deleteTenants;
import static com.smartfirehub.support.TenantRlsTestSupport.runInTenantTransaction;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.settings.repository.TenantSettingsRepository;
import com.smartfirehub.support.IntegrationTestBase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * tenant_settings 저장 시각이 DB 시계로 기록되는지 고정한다(WD-48).
 *
 * <p>임베딩 백로그 스윕은 이 시각을 DB now() 로 기록된 embedding_reembed_state.updated_at 과 비교한다. 앱(JVM) 시계로
 * 기록하면 앱·DB 시계 차이만큼 "설정 변경 없이 실패한 테넌트"를 "실패 뒤 설정을 저장한 테넌트"로 오판해 잡을 다시 넣는다.
 *
 * <p>판별 방법: 한 트랜잭션 안에서 저장한 뒤 {@code localtimestamp}(트랜잭션 시작 시각, DB 시계)와 정확히 같은지 본다. DB 시계로
 * 기록했다면 마이크로초까지 일치하고, 앱 시계로 기록했다면 트랜잭션 시작 이후의 다른 순간이라 어긋난다 — 시계 차이가 0 이어도 결정적으로
 * 구별된다.
 */
class TenantSettingsUpdatedAtClockTest extends IntegrationTestBase {

  @Autowired private TenantSettingsRepository repository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;

  @Test
  void 신규_저장과_갱신_모두_저장_시각을_DB_시계로_기록한다() {
    long tenant = createActiveTenant(dsl, "ts-clock");
    try {
      // 신규 삽입 경로
      assertThat(upsertAndCompareWithDbClock(tenant, "v1"))
          .as("신규 삽입의 updated_at 이 트랜잭션 시작 DB 시각과 같아야 한다")
          .isTrue();
      // ON CONFLICT 갱신 경로
      assertThat(upsertAndCompareWithDbClock(tenant, "v2"))
          .as("갱신의 updated_at 이 트랜잭션 시작 DB 시각과 같아야 한다")
          .isTrue();
    } finally {
      // 우리가 만든 테넌트만 지운다. tenant_settings 는 ON DELETE CASCADE 로 함께 사라진다.
      deleteTenants(dsl, tenant);
    }
  }

  /** 한 트랜잭션에서 저장 후 updated_at = localtimestamp 인지 돌려준다. */
  private boolean upsertAndCompareWithDbClock(long tenant, String value) {
    return runInTenantTransaction(
        transactionTemplate,
        tenant,
        () -> {
          repository.upsert("embedding.config", value, null);
          return dsl.fetchValue(
              "SELECT updated_at = localtimestamp FROM tenant_settings WHERE tenant_id = ? AND key ="
                  + " 'embedding.config'",
              tenant)
              .equals(Boolean.TRUE);
        });
  }
}
