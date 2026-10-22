package com.smartfirehub.pipeline.service;

import static com.smartfirehub.jooq.Tables.PERMISSION;
import static com.smartfirehub.jooq.Tables.PIPELINE;
import static com.smartfirehub.jooq.Tables.PIPELINE_EXECUTION;
import static com.smartfirehub.jooq.Tables.PIPELINE_TRIGGER;
import static com.smartfirehub.jooq.Tables.ROLE_PERMISSION;
import static com.smartfirehub.jooq.Tables.TRIGGER_EVENT;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.dto.TriggerFireResult;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * WD-167: 트리거는 발화 시점에 실행 주체(트리거를 만든 사용자)의 「파이프라인 실행」(pipeline:execute) 권한을 확인한다.
 *
 * <p>수정 전에는 트리거 경로에 실행 권한 확인이 없어, 트리거 권한만 가진 사람이 트리거를 만들어 실행 권한을 우회하거나 관리자가 실행 권한을 회수한 뒤에도 그 사람 이름의
 * 예약 트리거가 계속 실행됐다.
 *
 * <p>같은 사용자·같은 트리거로 "권한이 있으면 실행된다(양성 대조) → 권한을 회수하면 건너뛰고 실행 행이 생기지 않는다" 순서로 본다.
 */
class TriggerExecutePermissionTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private TriggerService triggerService;

  private SecurityFixture fx;
  private long userId;
  private long roleId;
  private long pipelineId;
  private long triggerId;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    userId = fx.createUser("tep_u");
    fx.removeUserRole(userId);
    roleId =
        fx.createRole(
            "tep_r_" + System.nanoTime(),
            fx.levelId("공개"),
            "pipeline:read",
            "pipeline:execute",
            "trigger:read",
            "trigger:write");
    fx.assignRole(userId, roleId);
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> {
          // 스텝 없는 파이프라인 — 실행되면 곧바로 끝난다
          pipelineId =
              dsl.insertInto(PIPELINE)
                  .set(PIPELINE.NAME, "tep_" + System.nanoTime())
                  .set(PIPELINE.IS_ACTIVE, true)
                  .set(PIPELINE.CREATED_BY, userId)
                  .returning(PIPELINE.ID)
                  .fetchOne()
                  .getId();
          triggerId =
              dsl.insertInto(PIPELINE_TRIGGER)
                  .set(PIPELINE_TRIGGER.PIPELINE_ID, pipelineId)
                  .set(PIPELINE_TRIGGER.TRIGGER_TYPE, "API")
                  .set(PIPELINE_TRIGGER.NAME, "실행 권한 확인")
                  .set(PIPELINE_TRIGGER.IS_ENABLED, true)
                  .set(PIPELINE_TRIGGER.CONFIG, JSONB.valueOf("{}"))
                  .set(PIPELINE_TRIGGER.TRIGGER_STATE, JSONB.valueOf("{}"))
                  .set(PIPELINE_TRIGGER.CREATED_BY, userId)
                  .returning(PIPELINE_TRIGGER.ID)
                  .fetchOne()
                  .getId();
        });
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    // 양성 대조의 비동기 실행이 끝날 틈을 준 뒤 파이프라인을 지운다(트리거·실행·이벤트는 FK CASCADE)
    Thread.sleep(1_000);
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> dsl.deleteFrom(PIPELINE).where(PIPELINE.ID.eq(pipelineId)).execute());
    fx.deleteUser(userId);
    fx.deleteRole(roleId);
  }

  private TriggerFireResult fire() {
    return TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> triggerService.fireTrigger(triggerId, Map.of()));
  }

  private int executions() {
    return TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> dsl.fetchCount(PIPELINE_EXECUTION, PIPELINE_EXECUTION.PIPELINE_ID.eq(pipelineId)));
  }

  private String lastEventReason() {
    return TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.select(TRIGGER_EVENT.EVENT_TYPE, TRIGGER_EVENT.DETAIL)
                .from(TRIGGER_EVENT)
                .where(TRIGGER_EVENT.TRIGGER_ID.eq(triggerId))
                .orderBy(TRIGGER_EVENT.ID.desc())
                .limit(1)
                .fetchOne(r -> r.value1() + " " + r.value2()));
  }

  @Test
  void trigger_runsOnlyWhileCreatorHoldsPipelineExecute() {
    // 양성 대조: 실행 권한이 있으면 실행된다
    TriggerFireResult fired = fire();
    assertThat(fired.outcome()).isEqualTo(TriggerFireResult.Outcome.FIRED);
    assertThat(executions()).isEqualTo(1);

    // 관리자가 이 사람 역할에서 실행 권한을 뺀다
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.deleteFrom(ROLE_PERMISSION)
                .where(ROLE_PERMISSION.ROLE_ID.eq(roleId))
                .and(
                    ROLE_PERMISSION.PERMISSION_ID.in(
                        dsl.select(PERMISSION.ID)
                            .from(PERMISSION)
                            .where(PERMISSION.CODE.eq("pipeline:execute"))))
                .execute());

    // 같은 트리거가 이제는 실행하지 않고 사유를 남긴다 — 실행 행은 늘지 않는다
    TriggerFireResult skipped = fire();
    assertThat(skipped.outcome()).isEqualTo(TriggerFireResult.Outcome.SKIPPED);
    assertThat(skipped.reason()).isEqualTo(TriggerService.REASON_EXECUTOR_NO_EXECUTE_PERMISSION);
    assertThat(executions()).isEqualTo(1);
    assertThat(lastEventReason()).startsWith("SKIPPED").contains("「파이프라인 실행」 권한이 없어");
  }
}
