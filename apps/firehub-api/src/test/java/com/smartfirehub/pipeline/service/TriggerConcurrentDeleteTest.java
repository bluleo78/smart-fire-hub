package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.pipeline.dto.CreatePipelineRequest;
import com.smartfirehub.pipeline.dto.CreateTriggerRequest;
import com.smartfirehub.pipeline.dto.TriggerResponse;
import com.smartfirehub.pipeline.dto.TriggerType;
import com.smartfirehub.pipeline.exception.TriggerNotFoundException;
import com.smartfirehub.support.ConcurrentCalls;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.PausedTransactionRace;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * WD-584 — 같은 파이프라인 트리거를 동시에 여러 번 지우면 모두 204 가 되고 「삭제」 감사가 요청 수만큼 남던 문제.
 *
 * <p>진 쪽 요청은 순차 두 번째 삭제와 같은 404({@link TriggerNotFoundException})여야 하고 감사는 실제로 지운 1건만 남아야 한다. 클래스
 * 레벨 {@code @Transactional} 을 쓰지 않는다 — 각 요청이 자기 트랜잭션으로 커밋해야 서로를 기다린다.
 */
class TriggerConcurrentDeleteTest extends IntegrationTestBase {

  @Autowired private PipelineService pipelineService;
  @Autowired private TriggerService triggerService;
  @Autowired private TriggerSchedulerService schedulerService;
  @Autowired private DSLContext dsl;

  private Long userId;
  private String suffix;

  /** 정리 대상 파이프라인(트리거는 FK CASCADE 로 함께 지워진다). */
  private final List<Long> pipelines = new ArrayList<>();

  /** 정리 때 예약을 풀 트리거 — 테스트가 실패해 행이 남아도 예약이 다음 테스트로 새지 않게. */
  private final List<Long> triggers = new ArrayList<>();

  @BeforeEach
  void setUp() {
    suffix = Long.toString(System.nanoTime());
    userId = TenantRlsTestSupport.insertUser(dsl, "wd584_trg_");
    TenantRlsTestSupport.insertActiveMembership(dsl, userId, DEFAULT_TEST_TENANT_ID);
    // 트리거 생성자 자격(WD-167) — PipelineTriggerAuditTest 와 같이 관리자 역할을 준다.
    inTenantFixture(() -> TenantRlsTestSupport.grantAdminRole(dsl, userId));
  }

  @AfterEach
  void tearDown() {
    triggers.forEach(schedulerService::unregisterSchedule);
    inTenantFixture(
        () -> {
          for (Long p : pipelines) {
            dsl.execute("DELETE FROM pipeline WHERE id = ?", p);
          }
          dsl.execute("DELETE FROM audit_log WHERE user_id = ?", userId);
        });
    TenantRlsTestSupport.deleteMembership(dsl, userId);
    TenantRlsTestSupport.deleteUser(dsl, userId);
  }

  /** 빈 파이프라인 하나와 그 안의 SCHEDULE 트리거(1월 1일 01:00 — 테스트 중 발화하지 않는다). */
  private TriggerResponse createTrigger(String label) {
    Long pipelineId =
        pipelineService
            .createPipeline(
                new CreatePipelineRequest("wd584-" + suffix + "-" + label, null, List.of()), userId)
            .id();
    pipelines.add(pipelineId);
    TriggerResponse created =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest(
                "trg-" + suffix + "-" + label,
                TriggerType.SCHEDULE,
                null,
                Map.of("cron", "0 1 1 1 *", "timezone", "Asia/Seoul", "concurrencyPolicy", "SKIP")),
            userId);
    triggers.add(created.id());
    return created;
  }

  private int deleteAudits(Long triggerId) {
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "SELECT count(*) FROM audit_log WHERE action_type = 'DELETE' AND resource ="
                        + " 'pipeline_trigger' AND resource_id = ?",
                    String.valueOf(triggerId))
                .get(0, Integer.class));
  }

  @Test
  void 같은_트리거를_6건_동시에_지우면_1건만_성공하고_나머지는_404다() throws Exception {
    TriggerResponse trigger = createTrigger("six");

    List<Throwable> results =
        ConcurrentCalls.run(
            6,
            DEFAULT_TEST_TENANT_ID,
            () -> triggerService.deleteTrigger(trigger.pipelineId(), trigger.id(), userId));

    assertThat(results.stream().filter(Objects::isNull).count()).isEqualTo(1);
    assertThat(results.stream().filter(Objects::nonNull).toList())
        .hasSize(5)
        .allMatch(TriggerNotFoundException.class::isInstance);
    assertThat(deleteAudits(trigger.id())).isEqualTo(1);
  }

  @Test
  void 지운_트랜잭션이_커밋되기_전_같은_삭제는_기다렸다가_404이고_감사는_1건이다() throws Exception {
    TriggerResponse trigger = createTrigger("paused");

    PausedTransactionRace.Outcome<Void> out =
        PausedTransactionRace.run(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () -> triggerService.deleteTrigger(trigger.pipelineId(), trigger.id(), userId),
            () -> {
              triggerService.deleteTrigger(trigger.pipelineId(), trigger.id(), userId);
              return null;
            });

    assertThat(out.secondBlocked()).isTrue();
    assertThat(out.secondError()).isInstanceOf(TriggerNotFoundException.class);
    assertThat(deleteAudits(trigger.id())).isEqualTo(1);
  }

  @Test
  void 순차로_두_번_지우면_두_번째는_404다() {
    TriggerResponse trigger = createTrigger("seq");

    triggerService.deleteTrigger(trigger.pipelineId(), trigger.id(), userId);

    assertThatThrownBy(
            () -> triggerService.deleteTrigger(trigger.pipelineId(), trigger.id(), userId))
        .isInstanceOf(TriggerNotFoundException.class);
    assertThat(deleteAudits(trigger.id())).isEqualTo(1);
  }
}
