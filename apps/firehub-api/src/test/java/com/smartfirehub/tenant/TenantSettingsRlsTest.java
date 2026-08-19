package com.smartfirehub.tenant;

import static com.smartfirehub.support.TenantRlsTestSupport.createActiveTenant;
import static com.smartfirehub.support.TenantRlsTestSupport.deleteTenants;
import static com.smartfirehub.support.TenantRlsTestSupport.runInTenantTransaction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.settings.repository.TenantSettingsRepository;
import com.smartfirehub.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code tenant_settings} 의 RLS 격리 + {@link TenantSettingsRepository} 계약을 검증한다(Task 3).
 *
 * <p>RLS 를 켠 테이블이므로 반드시 {@link com.smartfirehub.support.TenantRlsTestSupport#runInTenantTransaction}
 * 안에서 읽고 쓴다 — GUC 는 트랜잭션 로컬이고 {@code TenantAwareTransactionManager.doBegin} 에서만
 * 주입되기 때문에, 밖에서 읽으면 0행을 보고 "격리가 되는구나" 하며 통과하는 공허한 테스트가 된다.
 */
class TenantSettingsRlsTest extends IntegrationTestBase {

  @Autowired private TenantSettingsRepository repository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private org.jooq.DSLContext dsl;

  @Test
  void 두_테넌트의_오버라이드는_서로_보이지_않는다() {
    long a = createActiveTenant(dsl, "ts-a");
    long b = createActiveTenant(dsl, "ts-b");
    try {
      // 첫 인자는 TransactionTemplate 이다(@Autowired TransactionTemplate transactionTemplate).
      runInTenantTransaction(transactionTemplate, a, () -> repository.upsert("ai.model", "model-a", null));
      runInTenantTransaction(transactionTemplate, b, () -> repository.upsert("ai.model", "model-b", null));

      assertThat(runInTenantTransaction(transactionTemplate, a, () -> repository.findValue("ai.model")))
          .contains("model-a");
      assertThat(runInTenantTransaction(transactionTemplate, b, () -> repository.findValue("ai.model")))
          .contains("model-b");
    } finally {
      // 우리가 만든 테넌트만 지운다. tenant_settings 는 ON DELETE CASCADE 로 함께 사라진다.
      deleteTenants(dsl, a, b);
    }
  }

  @Test
  void 컨텍스트가_없으면_아무_행도_보이지_않는다() {
    // fail-closed 확인. 정책이 NULL 조건 → 전 행 차단이라는 것을 실제로 단언한다.
    long a = createActiveTenant(dsl, "ts-noctx");
    try {
      runInTenantTransaction(transactionTemplate, a, () -> repository.upsert("ai.model", "model-a", null));

      // tenantId=null 은 TenantRlsTestSupport 의 규약대로 "컨텍스트가 비어 있는 상태"를 재현한다.
      var visible =
          runInTenantTransaction(transactionTemplate, (Long) null, () -> repository.findValue("ai.model"));

      assertThat(visible)
          .as("컨텍스트 없이 방금 만든 행이 보이면 정책이 fail-open 이다")
          .isEmpty();
    } finally {
      deleteTenants(dsl, a);
    }
  }

  @Test
  void 같은_키를_다시_쓰면_행이_늘지_않고_갱신된다() {
    // PK (tenant_id, key) + ON CONFLICT DO UPDATE 계약.
    long a = createActiveTenant(dsl, "ts-upsert");
    try {
      runInTenantTransaction(transactionTemplate, a, () -> repository.upsert("ai.model", "first", null));
      runInTenantTransaction(transactionTemplate, a, () -> repository.upsert("ai.model", "second", null));

      // 값이 갱신됐는지.
      assertThat(runInTenantTransaction(transactionTemplate, a, () -> repository.findValue("ai.model")))
          .contains("second");
      // 행이 하나뿐인지(PK 충돌로 새 행이 추가되지 않았는지) findByPrefix 로 확인한다.
      var byPrefix = runInTenantTransaction(transactionTemplate, a, () -> repository.findByPrefix("ai"));
      assertThat(byPrefix).hasSize(1).containsEntry("ai.model", "second");
    } finally {
      deleteTenants(dsl, a);
    }
  }

  @Test
  void 값이_null이면_삭제_대신_거부된다() {
    // value 컬럼은 NOT NULL 이다. upsert 가 null 을 "삭제"의 동의어로 받아들이면 delete(key) 라는
    // 전용 통로와 의미가 겹쳐 호출부마다 어느 쪽을 쓸지 갈린다 — 그래서 즉시 거부한다.
    long a = createActiveTenant(dsl, "ts-null");
    try {
      assertThatThrownBy(
              () ->
                  runInTenantTransaction(
                      transactionTemplate, a, () -> repository.upsert("ai.model", null, null)))
          .isInstanceOf(NullPointerException.class);
    } finally {
      deleteTenants(dsl, a);
    }
  }
}
