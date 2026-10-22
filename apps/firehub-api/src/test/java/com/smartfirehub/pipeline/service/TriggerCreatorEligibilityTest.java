package com.smartfirehub.pipeline.service;

import static com.smartfirehub.jooq.Tables.PERMISSION;
import static com.smartfirehub.jooq.Tables.PIPELINE;
import static com.smartfirehub.jooq.Tables.PIPELINE_EXECUTION;
import static com.smartfirehub.jooq.Tables.PIPELINE_TRIGGER;
import static com.smartfirehub.jooq.Tables.ROLE;
import static com.smartfirehub.jooq.Tables.ROLE_PERMISSION;
import static com.smartfirehub.jooq.Tables.TRIGGER_EVENT;
import static com.smartfirehub.jooq.Tables.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.notification.service.NotificationService;
import com.smartfirehub.pipeline.dto.TriggerFireResult;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.Map;
import java.util.Set;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * WD-169: 트리거를 만든 사람이 정지·제거되면 「권한 없음」과 다른 사유로 건너뛰고, 자격 문제로 처음 건너뛸 때 파이프라인 주인과 워크스페이스 관리자에게 한 번만
 * 알린다. 트리거는 끄지 않는다 — 복구하면 다음 발화부터 저절로 실행된다.
 *
 * <p>사람 셋: 만든 사람(creator, 실행 권한 역할), 파이프라인 주인(owner — 기본은 creator 와 다른 사람), 이 워크스페이스 관리자(admin).
 * 테넌트 1 은 공유라 다른 활성 관리자가 있을 수 있다 — 수신자는 정확히 일치가 아니라 포함/불포함으로 본다.
 */
class TriggerCreatorEligibilityTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private TriggerService triggerService;
  // 실제 전송(SSE)은 그대로 두고 호출 인자만 기록한다
  @MockitoSpyBean private NotificationService notificationService;

  private SecurityFixture fx;
  private long creator;
  private long owner;
  private long admin;
  private long roleId;
  private long pipelineId;
  private long triggerId;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    creator = fx.createUser("tce_c");
    owner = fx.createUser("tce_o");
    admin = fx.createUser("tce_a");
    fx.removeUserRole(creator);
    roleId =
        fx.createRole(
            "tce_r_" + System.nanoTime(),
            fx.levelId("공개"),
            "pipeline:read",
            "pipeline:execute",
            "trigger:read",
            "trigger:write");
    fx.assignRole(creator, roleId);
    long adminRoleId =
        TenantRlsTestSupport.runInTenantTransaction(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () ->
                dsl.select(ROLE.ID)
                    .from(ROLE)
                    .where(ROLE.NAME.eq("ADMIN"))
                    .and(ROLE.TENANT_ID.eq(DEFAULT_TEST_TENANT_ID))
                    .fetchOne(ROLE.ID));
    fx.assignRole(admin, adminRoleId);
    createPipelineAndTrigger(owner);
    clearInvocations(notificationService);
  }

  /** 스텝 없는 파이프라인(실행되면 곧바로 끝난다)과 creator 가 만든 API 트리거. */
  private void createPipelineAndTrigger(long pipelineOwner) {
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> {
          pipelineId =
              dsl.insertInto(PIPELINE)
                  .set(PIPELINE.NAME, "tce_" + System.nanoTime())
                  .set(PIPELINE.IS_ACTIVE, true)
                  .set(PIPELINE.CREATED_BY, pipelineOwner)
                  .returning(PIPELINE.ID)
                  .fetchOne()
                  .getId();
          triggerId =
              dsl.insertInto(PIPELINE_TRIGGER)
                  .set(PIPELINE_TRIGGER.PIPELINE_ID, pipelineId)
                  .set(PIPELINE_TRIGGER.TRIGGER_TYPE, "API")
                  .set(PIPELINE_TRIGGER.NAME, "만든 사람 자격 확인")
                  .set(PIPELINE_TRIGGER.IS_ENABLED, true)
                  .set(PIPELINE_TRIGGER.CONFIG, JSONB.valueOf("{}"))
                  .set(PIPELINE_TRIGGER.TRIGGER_STATE, JSONB.valueOf("{}"))
                  .set(PIPELINE_TRIGGER.CREATED_BY, creator)
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
    // 제거 시나리오가 멤버십을 지웠다가 되살리지 못했을 때도 정리가 되도록 되살린다(없을 때만)
    for (long u : new long[] {creator, owner, admin}) {
      dsl.execute(
          "insert into membership(user_id, tenant_id, role, status) values (?, ?, 'MEMBER', 'ACTIVE')"
              + " on conflict do nothing",
          u,
          DEFAULT_TEST_TENANT_ID);
      fx.deleteUser(u);
    }
    fx.deleteRole(roleId);
  }

  // ── 조작 도우미 ─────────────────────────────────────────────

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

  private String lastEvent() {
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

  private void setAccountActive(long userId, boolean active) {
    dsl.update(USER).set(USER.IS_ACTIVE, active).where(USER.ID.eq(userId)).execute();
  }

  private void setMembershipStatus(long userId, String status) {
    dsl.execute(
        "update membership set status = ? where user_id = ? and tenant_id = ?",
        status,
        userId,
        DEFAULT_TEST_TENANT_ID);
  }

  private void removeMembership(long userId) {
    dsl.execute(
        "delete from membership where user_id = ? and tenant_id = ?",
        userId,
        DEFAULT_TEST_TENANT_ID);
  }

  private void setCreatorExecutePermission(boolean granted) {
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> {
          long permId =
              dsl.select(PERMISSION.ID)
                  .from(PERMISSION)
                  .where(PERMISSION.CODE.eq("pipeline:execute"))
                  .fetchOne(PERMISSION.ID);
          if (granted) {
            dsl.insertInto(ROLE_PERMISSION)
                .set(ROLE_PERMISSION.ROLE_ID, roleId)
                .set(ROLE_PERMISSION.PERMISSION_ID, permId)
                .onConflictDoNothing()
                .execute();
          } else {
            dsl.deleteFrom(ROLE_PERMISSION)
                .where(ROLE_PERMISSION.ROLE_ID.eq(roleId))
                .and(ROLE_PERMISSION.PERMISSION_ID.eq(permId))
                .execute();
          }
        });
  }

  /** 이 트리거의 건너뜀 알림이 정확히 한 번 나갔는지 보고, 그 수신자를 돌려준다. 확인 뒤 기록을 지운다. */
  @SuppressWarnings("unchecked")
  private Set<Long> notifiedOnceWith(String reason) {
    ArgumentCaptor<Set<Long>> recipients = ArgumentCaptor.forClass(Set.class);
    verify(notificationService, times(1))
        .notifyTriggerSkipped(
            eq(DEFAULT_TEST_TENANT_ID),
            recipients.capture(),
            eq(pipelineId),
            eq(triggerId),
            eq("만든 사람 자격 확인"),
            any(),
            any(),
            eq(reason));
    clearInvocations(notificationService);
    return recipients.getValue();
  }

  private void notNotified() {
    verify(notificationService, never())
        .notifyTriggerSkipped(
            anyLong(), any(), eq(pipelineId), eq(triggerId), any(), any(), any(), any());
  }

  private void assertSkippedWith(TriggerFireResult result, String reason, int expectedExecutions) {
    assertThat(result.outcome()).isEqualTo(TriggerFireResult.Outcome.SKIPPED);
    assertThat(result.reason()).isEqualTo(reason);
    assertThat(lastEvent()).startsWith("SKIPPED").contains(reason);
    assertThat(executions()).isEqualTo(expectedExecutions);
  }

  /**
   * 공통 시나리오: 양성 대조(실행) → 자격 상실 → 건너뜀(새/기존 사유)·알림 1회 → 다시 건너뜀·알림 없음 → 복구 → 정상 실행 → 다시 자격 상실 → 건너뜀·알림
   * 다시 1회. 수신자는 주인·관리자를 포함하고 만든 사람은 포함하지 않는다(만든 사람 ≠ 주인).
   */
  private void assertSkipNotifyOnceAndResume(Runnable lose, Runnable restore, String reason) {
    // 양성 대조: 자격이 있으면 실행된다
    assertThat(fire().outcome()).isEqualTo(TriggerFireResult.Outcome.FIRED);
    assertThat(executions()).isEqualTo(1);
    notNotified();

    lose.run();
    assertSkippedWith(fire(), reason, 1);
    Set<Long> first = notifiedOnceWith(reason);
    assertThat(first).contains(owner, admin).doesNotContain(creator);

    // 두 번째 건너뜀: 직전 이벤트가 같은 사유의 건너뜀이라 알리지 않는다
    assertSkippedWith(fire(), reason, 1);
    notNotified();

    // 복구하면 트리거를 다시 켜지 않아도 다음 발화가 정상 실행된다
    restore.run();
    assertThat(fire().outcome()).isEqualTo(TriggerFireResult.Outcome.FIRED);
    assertThat(executions()).isEqualTo(2);
    notNotified();

    // 정상 실행이 낀 뒤 다시 자격을 잃으면 다시 알린다
    lose.run();
    assertSkippedWith(fire(), reason, 2);
    assertThat(notifiedOnceWith(reason)).contains(owner, admin);
  }

  // ── 시나리오 ─────────────────────────────────────────────

  @Test
  void creatorAccountDeactivated_skipsWithInactiveReason_notifiesOnce_resumesAfterReactivation() {
    assertSkipNotifyOnceAndResume(
        () -> setAccountActive(creator, false),
        () -> setAccountActive(creator, true),
        TriggerService.REASON_EXECUTOR_INACTIVE);
  }

  @Test
  void creatorMembershipSuspended_skipsWithInactiveReason_notifiesOnce_resumesAfterReinstate() {
    assertSkipNotifyOnceAndResume(
        () -> setMembershipStatus(creator, "SUSPENDED"),
        () -> setMembershipStatus(creator, "ACTIVE"),
        TriggerService.REASON_EXECUTOR_INACTIVE);
  }

  /** 제거(멤버십 행 삭제) — 역할(user_role)이 그대로 남아 있어도 멤버가 아니면 돌지 않는다. */
  @Test
  void creatorRemovedFromWorkspace_skipsWithInactiveReason_notifiesOnce_resumesAfterReinvite() {
    assertSkipNotifyOnceAndResume(
        () -> removeMembership(creator),
        () -> TenantRlsTestSupport.insertActiveMembership(dsl, creator, DEFAULT_TEST_TENANT_ID),
        TriggerService.REASON_EXECUTOR_INACTIVE);
  }

  /** 계정·멤버십은 정상이고 권한만 회수 — 기존 WD-167 사유 그대로, 알림은 같은 규칙. */
  @Test
  void creatorLosesExecutePermission_keepsPermissionReason_notifiesOnce_resumesAfterRegrant() {
    assertSkipNotifyOnceAndResume(
        () -> setCreatorExecutePermission(false),
        () -> setCreatorExecutePermission(true),
        TriggerService.REASON_EXECUTOR_NO_EXECUTE_PERMISSION);
  }

  /** 만든 사람이 곧 주인이고 그 사람이 정지됐으면 그 사람에게는 보내지 않는다 — 관리자는 받는다. */
  @Test
  void creatorIsOwnerAndSuspended_notNotifiedThemselves_adminIs() {
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> dsl.deleteFrom(PIPELINE).where(PIPELINE.ID.eq(pipelineId)).execute());
    createPipelineAndTrigger(creator);

    setMembershipStatus(creator, "SUSPENDED");
    assertSkippedWith(fire(), TriggerService.REASON_EXECUTOR_INACTIVE, 0);
    Set<Long> recipients = notifiedOnceWith(TriggerService.REASON_EXECUTOR_INACTIVE);
    assertThat(recipients).contains(admin).doesNotContain(creator, owner);
  }

  /** 만든 사람이 곧 주인이고 권한만 잃었으면 여전히 멤버라 본인도 받는다 — 자기 권한이 없다는 걸 알아야 한다. */
  @Test
  void creatorIsOwnerAndLosesPermission_isNotifiedThemselves() {
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> dsl.deleteFrom(PIPELINE).where(PIPELINE.ID.eq(pipelineId)).execute());
    createPipelineAndTrigger(creator);

    setCreatorExecutePermission(false);
    assertSkippedWith(fire(), TriggerService.REASON_EXECUTOR_NO_EXECUTE_PERMISSION, 0);
    assertThat(notifiedOnceWith(TriggerService.REASON_EXECUTOR_NO_EXECUTE_PERMISSION))
        .contains(creator, admin);
  }

  /** 사유가 바뀌면(정지 → 복귀했지만 권한 없음) 직전과 다른 사유라 다시 알린다. */
  @Test
  void reasonChanges_notifiesAgain() {
    setMembershipStatus(creator, "SUSPENDED");
    assertSkippedWith(fire(), TriggerService.REASON_EXECUTOR_INACTIVE, 0);
    notifiedOnceWith(TriggerService.REASON_EXECUTOR_INACTIVE);

    setMembershipStatus(creator, "ACTIVE");
    setCreatorExecutePermission(false);
    assertSkippedWith(fire(), TriggerService.REASON_EXECUTOR_NO_EXECUTE_PERMISSION, 0);
    notifiedOnceWith(TriggerService.REASON_EXECUTOR_NO_EXECUTE_PERMISSION);
  }

  /** 정지된 주인(만든 사람과 다른 사람)에게도 보내지 않는다 — 정지·제거된 사람에게 이 워크스페이스 소식을 보내지 않는다. */
  @Test
  void suspendedOwner_isExcludedFromRecipients() {
    setMembershipStatus(owner, "SUSPENDED");
    setAccountActive(creator, false);
    assertSkippedWith(fire(), TriggerService.REASON_EXECUTOR_INACTIVE, 0);
    assertThat(notifiedOnceWith(TriggerService.REASON_EXECUTOR_INACTIVE))
        .contains(admin)
        .doesNotContain(owner, creator);
  }
}
