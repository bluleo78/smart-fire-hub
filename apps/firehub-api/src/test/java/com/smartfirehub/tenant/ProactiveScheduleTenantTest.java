package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.proactive.service.ProactiveJobSchedulerService;
import com.smartfirehub.proactive.service.ProactiveJobService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 스케줄 proactive 잡의 cron 발화가 <b>잡 소유자의 테넌트 컨텍스트</b> 안에서 실행되는지 고정한다
 * (최종 리뷰 Critical-2).
 *
 * <p>왜 필요한가: 스케줄러 스레드에는 원 HTTP 요청이 없어 승계할 테넌트가 없다. V99 로
 * {@code report_template} 에 RLS 가 걸렸으므로, 컨텍스트 없이 실행하면 양식 조회가 예외도 로그도 없이
 * 0행이 되어 사용자의 sections·style 없는 리포트가 만들어진다. {@code ReportTemplateRepository} 에
 * 트랜잭션을 붙이는 것만으로는 부족하다 — GUC 값이 비면 트랜잭션이 있어도 결과가 같기 때문이다.
 *
 * <p><b>이 클래스에는 클래스 레벨 {@code @Transactional} 이 없다 — 의도된 것이다.</b> 붙이는 순간 테스트
 * 트랜잭션이 GUC 를 공급해 운영에 없는 조건이 만들어진다. 픽스처와 정리만 트랜잭션으로 감싸고, 검증
 * 대상인 {@code runScheduledJob} 호출은 트랜잭션 밖에 둔다.
 *
 * <p>실제 실행({@code ProactiveJobService.executeJob})은 AI 호출·비동기라 그대로 돌릴 수 없으므로
 * 목으로 대체하고, <b>호출되는 순간의 {@link TenantContext#get()}</b> 를 기록해 단언한다 —
 * {@code CleanupSchedulerTenantTest} 의 {@code SqlProbe} 와 같은 관측 지점 캡처 방식이다.
 */
class ProactiveScheduleTenantTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private ProactiveJobSchedulerService schedulerService;

  @MockitoBean private ProactiveJobService proactiveJobService;

  /** executeJob 이 불린 순간의 테넌트 컨텍스트. 배선이 없으면 null 이 기록된다. */
  private final AtomicReference<Long> observedTenant = new AtomicReference<>();

  private long tenantId;
  private Long userId;
  private Long jobId;

  @BeforeEach
  void setUp() {
    tenantId = TenantRlsTestSupport.createActiveTenant(dsl, "proactive-sched");
    userId = TenantRlsTestSupport.insertUser(dsl, "proactivesched");
    insertMembership(userId, tenantId);
    jobId = insertScheduleJob(userId);

    observedTenant.set(null);
    Mockito.doAnswer(
            invocation -> {
              observedTenant.set(TenantContext.get());
              return null;
            })
        .when(proactiveJobService)
        .executeJob(Mockito.anyLong(), Mockito.anyLong());
  }

  @AfterEach
  void cleanup() {
    dsl.deleteFrom(table(name("proactive_job")))
        .where(field(name("id"), Long.class).eq(jobId))
        .execute();
    dsl.deleteFrom(table(name("membership")))
        .where(field(name("user_id"), Long.class).eq(userId))
        .execute();
    TenantRlsTestSupport.deleteUser(dsl, userId);
    TenantRlsTestSupport.deleteTenants(dsl, tenantId);
    TenantContext.clear();
  }

  @Test
  @DisplayName("cron 발화는 잡 소유자의 테넌트 컨텍스트 안에서 실행을 위임한다")
  void scheduledFiringRunsInOwnerTenantContext() {
    // 스케줄러 스레드 재현: 앰비언트 트랜잭션도 테넌트 컨텍스트도 없다.
    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
        .as("이 테스트는 트랜잭션 밖에서 돌아야 스케줄러 스레드를 재현한다")
        .isFalse();
    TenantContext.clear();

    schedulerService.runScheduledJob(jobId);

    assertThat(observedTenant.get())
        .as(
            "cron 발화가 테넌트 컨텍스트 없이 executeJob 을 부르면, RLS 가 걸린 report_template 조회가"
                + " 0행이 되어 양식 없는 리포트가 생성된다(예외도 로그도 없이)")
        .isEqualTo(tenantId);
  }

  @Test
  @DisplayName("발화 후 스케줄러 스레드의 컨텍스트는 진입 전 상태(없음)로 복원된다")
  void contextIsRestoredAfterFiring() {
    // runScoped 는 clear 가 아니라 '진입 전 값 복원' 계약이다. 진입 전이 없었으므로 결과는 비어야 한다 —
    // 남으면 스케줄러 스레드가 재사용될 때 다른 잡이 남의 테넌트를 물려받는다.
    TenantContext.clear();
    schedulerService.runScheduledJob(jobId);
    assertThat(TenantContext.get()).isNull();
  }

  @Test
  @DisplayName("소유자의 멤버십이 여러 개면 기본 테넌트로 떨어뜨리지 않고 실행을 건너뛴다")
  void ambiguousTenantSkipsExecution() {
    // 테넌트 2·3 에만 속한 소유자를 기본 테넌트(1)로 돌리면, report_template 조회가 RLS 로 0행이
    // 되어 sections·style 이 빠진 리포트가 조용히 만들어진다 — 이 밴드가 고친 결함과 같은 형태다.
    long secondTenant = TenantRlsTestSupport.createActiveTenant(dsl, "proactive-sched2");
    try {
      insertMembership(userId, secondTenant);
      TenantContext.clear();

      schedulerService.runScheduledJob(jobId);

      // observedTenant 가 null 인 것만 보면 "executeJob 이 아예 안 불렸다" 와 "컨텍스트 없이
      // 불렸다" 를 구분하지 못해 공허하게 통과한다. 호출 자체가 없어야 한다.
      Mockito.verify(proactiveJobService, Mockito.never())
          .executeJob(Mockito.anyLong(), Mockito.anyLong());
      assertThat(observedTenant.get()).isNull();
    } finally {
      dsl.deleteFrom(table(name("membership")))
          .where(field(name("tenant_id"), Long.class).eq(secondTenant))
          .execute();
      TenantRlsTestSupport.deleteTenants(dsl, secondTenant);
    }
  }

  // ── 픽스처 ────────────────────────────────────────────────────────────

  /** membership 은 전역(RLS 미적용) 테이블이라 테넌트 컨텍스트 없이 삽입한다. */
  private void insertMembership(Long user, long tenant) {
    dsl.insertInto(table(name("membership")))
        .set(field(name("user_id"), Long.class), user)
        .set(field(name("tenant_id"), Long.class), tenant)
        .set(field(name("role"), String.class), "MEMBER")
        .set(field(name("status"), String.class), "ACTIVE")
        .execute();
  }

  /** proactive_job 은 아직 RLS 대상이 아니다(P2-d 예정) — 컨텍스트 없이 삽입된다. */
  private Long insertScheduleJob(Long owner) {
    return dsl.insertInto(table(name("proactive_job")))
        .set(field(name("user_id"), Long.class), owner)
        .set(field(name("name"), String.class), "sched-" + TenantRlsTestSupport.nextTenantId())
        .set(field(name("prompt"), String.class), "test")
        .set(field(name("config"), JSONB.class), JSONB.valueOf("{}"))
        .set(field(name("enabled"), Boolean.class), true)
        .set(field(name("trigger_type"), String.class), "SCHEDULE")
        .set(field(name("cron_expression"), String.class), "0 0 3 * * *")
        .returning(field(name("id"), Long.class))
        .fetchOne()
        .get(field(name("id"), Long.class));
  }
}
