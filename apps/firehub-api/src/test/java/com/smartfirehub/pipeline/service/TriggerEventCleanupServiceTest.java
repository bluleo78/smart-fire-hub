package com.smartfirehub.pipeline.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.*;

import com.smartfirehub.pipeline.dto.*;
import com.smartfirehub.pipeline.repository.TriggerEventRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <b>클래스 레벨 {@code @Transactional} 을 뺐다 — 의도된 것이다(P2-b Task 9).</b> 붙어 있으면
 * {@code cleanupOldEvents()} 의 테넌트 순회가 테스트 트랜잭션에 얹혀 <b>모든 순회 패스가 테넌트 1
 * 의 GUC 로</b> 실행된다 — 순회 배선을 통째로 지워도 통과하는 사각지대다. 픽스처와 검증만
 * 트랜잭션으로 감싸고 검증 대상 호출은 밖에 남긴다. 롤백이 없으므로 심은 행은 직접 지운다.
 */
class TriggerEventCleanupServiceTest extends IntegrationTestBase {

  @Autowired private TriggerEventCleanupService cleanupService;

  @Autowired private TriggerEventRepository triggerEventRepository;

  @Autowired private TriggerService triggerService;

  @Autowired private PipelineService pipelineService;

  @Autowired private DSLContext dsl;

  @Autowired private TransactionTemplate tx;

  private Long testUserId;
  private Long pipelineId;
  private Long triggerId;

  @BeforeEach
  void setUp() {
    String unique = String.valueOf(System.nanoTime());
    testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "cleanup_test_user_" + unique)
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Cleanup Test User")
            .set(USER.EMAIL, "cleanup_test_" + unique + "@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();

    PipelineDetailResponse pipeline =
        pipelineService.createPipeline(
            new CreatePipelineRequest("Cleanup Test Pipeline", "Description", List.of()),
            testUserId);
    pipelineId = pipeline.id();

    TriggerResponse trigger =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest("Cleanup Test Trigger", TriggerType.API, null, Map.of()),
            testUserId);
    triggerId = trigger.id();
  }

  @AfterEach
  void cleanup() {
    // pipeline 을 지우면 트리거·이벤트가 FK CASCADE 로 사라진다. RLS 대상이라 트랜잭션 안에서.
    inTenantTx(() -> dsl.deleteFrom(PIPELINE).where(PIPELINE.ID.eq(pipelineId)).execute());
    dsl.deleteFrom(USER).where(USER.ID.eq(testUserId)).execute();
  }

  @Test
  void cleanupOldEvents_deletesEventsOlderThan90Days() {
    // trigger_event 는 RLS 대상(V96)이라 픽스처 INSERT 도 테넌트 트랜잭션 안이어야 한다.
    insertEventDaysAgo(100);
    insertEventDaysAgo(10);

    // Verify both events exist
    List<TriggerEventResponse> before = triggerEventRepository.findByPipelineId(pipelineId, 100);
    assertThat(before).hasSize(2);

    runCleanupOutsideTransaction();

    // Verify old event was deleted, recent event remains
    List<TriggerEventResponse> after = triggerEventRepository.findByPipelineId(pipelineId, 100);
    assertThat(after).hasSize(1);
  }

  @Test
  void cleanupOldEvents_withNoOldEvents_deletesNothing() {
    // Insert only a recent event
    triggerEventRepository.create(triggerId, pipelineId, null, "FIRED", Map.of("test", "recent"));

    List<TriggerEventResponse> before = triggerEventRepository.findByPipelineId(pipelineId, 100);
    assertThat(before).hasSize(1);

    runCleanupOutsideTransaction();

    // Verify nothing was deleted
    List<TriggerEventResponse> after = triggerEventRepository.findByPipelineId(pipelineId, 100);
    assertThat(after).hasSize(1);
  }

  @Test
  void cleanupOldEvents_withNoEvents_completesWithoutError() {
    // No events exist — should not throw
    runCleanupOutsideTransaction();
  }

  // ── 픽스처 헬퍼 ────────────────────────────────────────────────────────

  /**
   * 검증 대상 호출 — 트랜잭션 밖에서 부른다(순회가 스스로 컨텍스트·트랜잭션을 잡아야 한다).
   *
   * <p>{@code forEachActiveTenant} 는 진입 전 컨텍스트를 복원하므로 호출 뒤에도 기본 테넌트가
   * 남는다 — 이후 검증 조회가 컨텍스트 없이 돌아 조용히 0행이 되는 일은 없다.
   */
  private void runCleanupOutsideTransaction() {
    cleanupService.cleanupOldEvents();
  }

  /** 지정한 일수 전 시각으로 트리거 이벤트 하나를 심는다(테넌트 트랜잭션 안). */
  private void insertEventDaysAgo(int daysAgo) {
    inTenantTx(
        () ->
            dsl.insertInto(table(name("trigger_event")))
                .set(field(name("trigger_event", "trigger_id"), Long.class), triggerId)
                .set(field(name("trigger_event", "pipeline_id"), Long.class), pipelineId)
                .set(field(name("trigger_event", "event_type"), String.class), "FIRED")
                .set(
                    field(name("trigger_event", "created_at"), LocalDateTime.class),
                    LocalDateTime.now().minusDays(daysAgo))
                .execute());
  }

  /**
   * 픽스처를 테넌트 트랜잭션 안에서 실행한다.
   *
   * <p>{@code runInTenantTransaction} 이 진입 전 컨텍스트를 복원하므로, 이어지는
   * {@code cleanupOldEvents()} 의 테넌트 순회는 기본 테넌트 컨텍스트에서 시작된다.
   */
  private <T> T inTenantTx(java.util.function.Supplier<T> action) {
    return TenantRlsTestSupport.runInTenantTransaction(tx, DEFAULT_TEST_TENANT_ID, action);
  }
}
