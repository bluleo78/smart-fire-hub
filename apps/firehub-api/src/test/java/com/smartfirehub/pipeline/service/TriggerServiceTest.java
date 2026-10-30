package com.smartfirehub.pipeline.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.pipeline.dto.*;
import com.smartfirehub.pipeline.exception.CyclicTriggerDependencyException;
import com.smartfirehub.pipeline.exception.PipelineNotFoundException;
import com.smartfirehub.pipeline.exception.TriggerNotFoundException;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class TriggerServiceTest extends IntegrationTestBase {

  @Autowired private TriggerService triggerService;

  @Autowired private PipelineService pipelineService;

  @Autowired private DSLContext dsl;

  private Long testUserId;
  private Long pipelineId;
  private Long pipelineId2;

  @BeforeEach
  void setUp() {
    // Create test user
    testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "trigger_test_user")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Trigger Test User")
            .set(USER.EMAIL, "trigger_test@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
    TenantRlsTestSupport.insertActiveMembership(dsl, testUserId, DEFAULT_TEST_TENANT_ID);
    // WD-167: 트리거는 실행 주체(생성자)의 pipeline:execute 를 발화 시점에 확인한다 — 실행 권한을 준다
    TenantRlsTestSupport.grantAdminRole(dsl, testUserId);

    // Create test pipelines
    PipelineDetailResponse pipeline =
        pipelineService.createPipeline(
            new CreatePipelineRequest("Test Pipeline 1", "Description 1", List.of()), testUserId);
    pipelineId = pipeline.id();

    PipelineDetailResponse pipeline2 =
        pipelineService.createPipeline(
            new CreatePipelineRequest("Test Pipeline 2", "Description 2", List.of()), testUserId);
    pipelineId2 = pipeline2.id();
  }

  @Test
  void createScheduleTrigger_success() {
    CreateTriggerRequest request =
        new CreateTriggerRequest(
            "Daily Schedule",
            TriggerType.SCHEDULE,
            "Run every day at 9 AM",
            Map.of("cron", "0 9 * * *", "timezone", "Asia/Seoul", "concurrencyPolicy", "SKIP"));

    TriggerResponse response = triggerService.createTrigger(pipelineId, request, testUserId);

    assertThat(response.id()).isNotNull();
    assertThat(response.name()).isEqualTo("Daily Schedule");
    assertThat(response.triggerType()).isEqualTo("SCHEDULE");
    assertThat(response.isEnabled()).isTrue();
    assertThat(response.config()).containsKey("cron");
    assertThat(response.config().get("cron")).isEqualTo("0 0 9 * * *");
  }

  @Test
  void createApiTrigger_returnsRawToken() {
    CreateTriggerRequest request =
        new CreateTriggerRequest("API Trigger", TriggerType.API, "External API trigger", Map.of());

    TriggerResponse response = triggerService.createTrigger(pipelineId, request, testUserId);

    assertThat(response.id()).isNotNull();
    assertThat(response.triggerType()).isEqualTo("API");
    assertThat(response.config()).containsKey("rawToken");
    // 토큰 해시는 서버 소유 키라 생성 응답에도 싣지 않는다(WD-243) — 원본 토큰만 한 번 보인다.
    assertThat(response.config()).doesNotContainKey("tokenHash");
    assertThat(response.config().get("rawToken")).isNotNull();
  }

  @Test
  void createPipelineChainTrigger_success() {
    CreateTriggerRequest request =
        new CreateTriggerRequest(
            "Chain Trigger",
            TriggerType.PIPELINE_CHAIN,
            "Run after pipeline 2",
            Map.of("upstreamPipelineId", pipelineId2.intValue(), "condition", "SUCCESS"));

    TriggerResponse response = triggerService.createTrigger(pipelineId, request, testUserId);

    assertThat(response.id()).isNotNull();
    assertThat(response.triggerType()).isEqualTo("PIPELINE_CHAIN");
  }

  @Test
  void createPipelineChainTrigger_selfReference_throwsException() {
    CreateTriggerRequest request =
        new CreateTriggerRequest(
            "Self Chain",
            TriggerType.PIPELINE_CHAIN,
            "Self reference",
            Map.of("upstreamPipelineId", pipelineId.intValue(), "condition", "SUCCESS"));

    assertThatThrownBy(() -> triggerService.createTrigger(pipelineId, request, testUserId))
        .isInstanceOf(CyclicTriggerDependencyException.class);
  }

  @Test
  void createWebhookTrigger_success() {
    CreateTriggerRequest request =
        new CreateTriggerRequest(
            "Webhook Trigger", TriggerType.WEBHOOK, "Receive webhooks", Map.of());

    TriggerResponse response = triggerService.createTrigger(pipelineId, request, testUserId);

    assertThat(response.id()).isNotNull();
    assertThat(response.triggerType()).isEqualTo("WEBHOOK");
    assertThat(response.config()).containsKey("webhookId");
  }

  @Test
  void createDatasetChangeTrigger_missingDatasetIds_throwsException() {
    CreateTriggerRequest request =
        new CreateTriggerRequest(
            "Dataset Change", TriggerType.DATASET_CHANGE, "Detect changes", Map.of());

    assertThatThrownBy(() -> triggerService.createTrigger(pipelineId, request, testUserId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("datasetIds");
  }

  @Test
  void getTriggers_returnsListForPipeline() {
    triggerService.createTrigger(
        pipelineId,
        new CreateTriggerRequest("Trigger 1", TriggerType.API, null, Map.of()),
        testUserId);
    triggerService.createTrigger(
        pipelineId,
        new CreateTriggerRequest("Trigger 2", TriggerType.WEBHOOK, null, Map.of()),
        testUserId);

    List<TriggerResponse> triggers = triggerService.getTriggers(pipelineId);

    assertThat(triggers).hasSize(2);
  }

  @Test
  void updateTrigger_success() {
    TriggerResponse created =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest("Original", TriggerType.API, "Original desc", Map.of()),
            testUserId);

    triggerService.updateTrigger(
        created.id(), new UpdateTriggerRequest("Updated", null, "Updated desc", null), testUserId);

    TriggerResponse updated = triggerService.getTriggerById(created.id());
    assertThat(updated.name()).isEqualTo("Updated");
    assertThat(updated.description()).isEqualTo("Updated desc");
  }

  /**
   * 설명을 지우고 저장하면 실제로 지워지고(빈 문자열 = 지움), 설명 키 없이(null) 보낸 부분 수정은 기존 설명을 그대로 둔다(WD-113). 예전엔 빈 설명도
   * null 로 와서 "바꾸지 않음"으로 처리돼 옛 설명이 남았다.
   */
  @Test
  void updateTrigger_blankDescriptionClears_nullKeepsExisting() {
    TriggerResponse created =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest("With desc", TriggerType.API, "Original desc", Map.of()),
            testUserId);

    triggerService.updateTrigger(
        created.id(), new UpdateTriggerRequest("Renamed", null, null, null), testUserId);
    assertThat(triggerService.getTriggerById(created.id()).description())
        .isEqualTo("Original desc");

    triggerService.updateTrigger(
        created.id(), new UpdateTriggerRequest("Renamed", null, "", null), testUserId);
    assertThat(triggerService.getTriggerById(created.id()).description()).isNull();
  }

  @Test
  void deleteTrigger_success() {
    TriggerResponse created =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest("To Delete", TriggerType.API, null, Map.of()),
            testUserId);

    triggerService.deleteTrigger(created.id());

    // WD-226: 내부 id 없는 한국어 문구로 404
    assertThatThrownBy(() -> triggerService.getTriggerById(created.id()))
        .isInstanceOf(TriggerNotFoundException.class)
        .hasMessage("트리거를 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.");
  }

  @Test
  void toggleTrigger_disablesAndEnables() {
    TriggerResponse created =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest("Toggle Test", TriggerType.API, null, Map.of()),
            testUserId);

    assertThat(created.isEnabled()).isTrue();

    triggerService.toggleTrigger(created.id(), false);
    TriggerResponse disabled = triggerService.getTriggerById(created.id());
    assertThat(disabled.isEnabled()).isFalse();

    triggerService.toggleTrigger(created.id(), true);
    TriggerResponse enabled = triggerService.getTriggerById(created.id());
    assertThat(enabled.isEnabled()).isTrue();
  }

  @Test
  void resolveApiToken_findsCorrectTrigger() {
    TriggerResponse created =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest("API Token Test", TriggerType.API, null, Map.of()),
            testUserId);

    String rawToken = (String) created.config().get("rawToken");
    assertThat(rawToken).isNotNull();

    TriggerResponse resolved = triggerService.resolveApiToken(rawToken);
    assertThat(resolved).isNotNull();
    assertThat(resolved.id()).isEqualTo(created.id());
  }

  @Test
  void resolveApiToken_invalidToken_returnsNull() {
    TriggerResponse resolved = triggerService.resolveApiToken("invalid-token-value");
    assertThat(resolved).isNull();
  }

  @Test
  void fireTrigger_inactivePipeline_recordsSkippedEvent() {
    // Create trigger
    TriggerResponse created =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest("Fire Test", TriggerType.API, null, Map.of()),
            testUserId);

    // Deactivate pipeline
    pipelineService.updatePipeline(
        pipelineId,
        new UpdatePipelineRequest("Test Pipeline 1", "Description 1", false, null),
        testUserId);

    // Fire trigger
    TriggerFireResult result = triggerService.fireTrigger(created.id(), Map.of());

    // 발화 결과가 "건너뜀 + 한국어 사유"여야 외부 트리거 컨트롤러가 409 로 알릴 수 있다(WD-152)
    assertThat(result.outcome()).isEqualTo(TriggerFireResult.Outcome.SKIPPED);
    assertThat(result.reason()).isEqualTo("파이프라인이 비활성 상태라 실행하지 않았습니다");
    assertThat(result.executionId()).isNull();

    // 이벤트 로그의 사유도 같은 한국어 문구다 — 예전엔 영어 원문("Pipeline is not active")이었다
    List<TriggerEventResponse> events = triggerService.getTriggerEvents(pipelineId, 10);
    assertThat(events)
        .anyMatch(
            e ->
                "SKIPPED".equals(e.eventType())
                    && TriggerService.REASON_PIPELINE_INACTIVE.equals(e.detail().get("reason")));
  }

  /**
   * 회귀 테스트(WD-152): 스케줄 트리거가 동시 실행 정책(건너뛰기)에 걸리면 건너뜀 이벤트의 사유가 한국어로 남고, 발화 결과도 같은 사유를 돌려준다. 동시 실행
   * 정책은 스케줄 트리거에만 적용된다(API·웹훅 트리거는 이 분기를 타지 않는다).
   */
  @Test
  void fireTrigger_scheduleSkipPolicyWithRunningExecution_recordsKoreanReason() {
    TriggerResponse created =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest(
                "Skip Policy",
                TriggerType.SCHEDULE,
                null,
                Map.of("cron", "0 0 1 1 *", "timezone", "Asia/Seoul", "concurrencyPolicy", "SKIP")),
            testUserId);

    // 진행 중인 실행을 직접 만든다 — 실제 실행을 띄우면 비동기 러너가 끝내 버려 판정이 경합한다
    dsl.insertInto(PIPELINE_EXECUTION)
        .set(PIPELINE_EXECUTION.PIPELINE_ID, pipelineId)
        .set(PIPELINE_EXECUTION.STATUS, "RUNNING")
        .set(PIPELINE_EXECUTION.EXECUTED_BY, testUserId)
        .execute();

    TriggerFireResult result = triggerService.fireTrigger(created.id(), Map.of());

    assertThat(result.outcome()).isEqualTo(TriggerFireResult.Outcome.SKIPPED);
    assertThat(result.reason()).isEqualTo("이미 실행 중인 실행이 있어 동시 실행 정책(건너뛰기)에 따라 실행하지 않았습니다");
    List<TriggerEventResponse> events = triggerService.getTriggerEvents(pipelineId, 10);
    assertThat(events)
        .anyMatch(
            e ->
                "SKIPPED".equals(e.eventType())
                    && TriggerService.REASON_CONCURRENT_SKIP.equals(e.detail().get("reason")));
  }

  @Test
  void createScheduleTrigger_missingCron_throwsException() {
    CreateTriggerRequest request =
        new CreateTriggerRequest(
            "Bad Schedule", TriggerType.SCHEDULE, null, Map.of("timezone", "Asia/Seoul"));

    assertThatThrownBy(() -> triggerService.createTrigger(pipelineId, request, testUserId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cron");
  }

  // ── WD-228: 단건 API 는 경로의 파이프라인 소속 트리거만 다룬다 ──

  /** 파이프라인 1 에 스케줄 트리거를 하나 만든다 — 다른 파이프라인 경로로 건드려 보는 대상. */
  private TriggerResponse createScheduleTriggerOnPipeline1() {
    return triggerService.createTrigger(
        pipelineId,
        new CreateTriggerRequest(
            "Owned Trigger", TriggerType.SCHEDULE, "orig", Map.of("cron", "0 9 * * *")),
        testUserId);
  }

  @Test
  void getTriggerById_otherPipeline_throwsNotFound() {
    TriggerResponse created = createScheduleTriggerOnPipeline1();

    // 다른(존재하는) 파이프라인과 없는 파이프라인 모두 없는 트리거와 같은 404 여야 한다.
    assertThatThrownBy(() -> triggerService.getTriggerById(pipelineId2, created.id()))
        .isInstanceOf(TriggerNotFoundException.class)
        .hasMessage(TriggerNotFoundException.MESSAGE);
    assertThatThrownBy(() -> triggerService.getTriggerById(99_999_999L, created.id()))
        .isInstanceOf(TriggerNotFoundException.class);
    // 맞는 파이프라인이면 그대로 조회된다.
    assertThat(triggerService.getTriggerById(pipelineId, created.id()).name())
        .isEqualTo("Owned Trigger");
  }

  @Test
  void updateTrigger_otherPipeline_throwsNotFoundAndLeavesTriggerUnchanged() {
    TriggerResponse created = createScheduleTriggerOnPipeline1();
    TriggerResponse before = triggerService.getTriggerById(created.id());

    assertThatThrownBy(
            () ->
                triggerService.updateTrigger(
                    pipelineId2,
                    created.id(),
                    new UpdateTriggerRequest("renamed", false, "changed", null),
                    testUserId))
        .isInstanceOf(TriggerNotFoundException.class);

    TriggerResponse after = triggerService.getTriggerById(created.id());
    assertThat(after.name()).isEqualTo(before.name());
    assertThat(after.description()).isEqualTo(before.description());
    assertThat(after.isEnabled()).isTrue();

    // 맞는 파이프라인이면 수정된다.
    triggerService.updateTrigger(
        pipelineId,
        created.id(),
        new UpdateTriggerRequest("renamed", null, null, null),
        testUserId);
    assertThat(triggerService.getTriggerById(created.id()).name()).isEqualTo("renamed");
  }

  @Test
  void deleteTrigger_otherPipeline_throwsNotFoundAndKeepsTrigger() {
    TriggerResponse created = createScheduleTriggerOnPipeline1();

    assertThatThrownBy(() -> triggerService.deleteTrigger(pipelineId2, created.id(), testUserId))
        .isInstanceOf(TriggerNotFoundException.class);
    assertThat(triggerService.getTriggers(pipelineId))
        .extracting(TriggerResponse::id)
        .contains(created.id());

    // 맞는 파이프라인이면 지워진다.
    triggerService.deleteTrigger(pipelineId, created.id(), testUserId);
    assertThat(triggerService.getTriggers(pipelineId))
        .extracting(TriggerResponse::id)
        .doesNotContain(created.id());
  }

  @Test
  void toggleTrigger_otherPipeline_throwsNotFoundAndKeepsState() {
    TriggerResponse created = createScheduleTriggerOnPipeline1();

    assertThatThrownBy(
            () -> triggerService.toggleTrigger(pipelineId2, created.id(), false, testUserId))
        .isInstanceOf(TriggerNotFoundException.class);
    assertThat(triggerService.getTriggerById(created.id()).isEnabled()).isTrue();

    // 맞는 파이프라인이면 꺼진다.
    triggerService.toggleTrigger(pipelineId, created.id(), false, testUserId);
    assertThat(triggerService.getTriggerById(created.id()).isEnabled()).isFalse();
  }

  @Test
  void createTrigger_missingPipeline_throwsPipelineNotFound() {
    // 예전엔 FK 위반으로 409 「연결할 항목을 찾을 수 없습니다」가 났다 — 이제는 파이프라인 없음 404.
    assertThatThrownBy(
            () ->
                triggerService.createTrigger(
                    99_999_999L,
                    new CreateTriggerRequest(
                        "Orphan", TriggerType.SCHEDULE, null, Map.of("cron", "0 9 * * *")),
                    testUserId))
        .isInstanceOf(PipelineNotFoundException.class)
        .hasMessage(PipelineNotFoundException.PIPELINE_MESSAGE);
    assertThat(dsl.fetchCount(PIPELINE_TRIGGER, PIPELINE_TRIGGER.NAME.eq("Orphan"))).isZero();
  }

  // ── WD-244: 트리거 목록·이벤트 조회도 파이프라인 존재를 확인하고 limit 범위를 검사한다 ──

  @Test
  void getTriggers_missingPipeline_throwsPipelineNotFound() {
    // 예전엔 없는 파이프라인 id 로도 빈 목록이 나갔다 — 상세·실행 목록과 같은 404 로 맞춘다.
    assertThatThrownBy(() -> triggerService.getTriggers(99_999_999L))
        .isInstanceOf(PipelineNotFoundException.class)
        .hasMessage(PipelineNotFoundException.PIPELINE_MESSAGE);
  }

  @Test
  void getTriggers_deletedPipeline_throwsPipelineNotFound() {
    // 열린 상세 화면에서 다른 사람이 지운 뒤 다시 불러오는 경우 — 방금 삭제한 id 도 404.
    pipelineService.deletePipelineUnchecked(pipelineId2);
    assertThatThrownBy(() -> triggerService.getTriggers(pipelineId2))
        .isInstanceOf(PipelineNotFoundException.class);
    assertThatThrownBy(() -> triggerService.getTriggerEvents(pipelineId2, 20))
        .isInstanceOf(PipelineNotFoundException.class);
  }

  @Test
  void getTriggerEvents_missingPipeline_throwsPipelineNotFoundBeforeLimitCheck() {
    // 존재 확인이 limit 검증보다 먼저다 — 없는 파이프라인이면 limit 이 틀려도 404.
    assertThatThrownBy(() -> triggerService.getTriggerEvents(99_999_999L, 20))
        .isInstanceOf(PipelineNotFoundException.class)
        .hasMessage(PipelineNotFoundException.PIPELINE_MESSAGE);
    assertThatThrownBy(() -> triggerService.getTriggerEvents(99_999_999L, -1))
        .isInstanceOf(PipelineNotFoundException.class);
  }

  @Test
  void getTriggersAndEvents_existingPipelineWithoutTriggers_returnEmpty() {
    // 있는 파이프라인에 트리거·이벤트가 없으면 여전히 빈 목록이다(404 가 아니다).
    assertThat(triggerService.getTriggers(pipelineId)).isEmpty();
    assertThat(triggerService.getTriggerEvents(pipelineId, 20)).isEmpty();
    // 경계값 1·상한은 허용한다.
    assertThat(triggerService.getTriggerEvents(pipelineId, 1)).isEmpty();
    assertThat(triggerService.getTriggerEvents(pipelineId, TriggerService.MAX_TRIGGER_EVENT_LIMIT))
        .isEmpty();
  }

  @Test
  void getTriggerEvents_limitOutOfRange_throwsKoreanBadRequest() {
    // 음수는 예전엔 DB 오류(「저장할 수 없는 값…」)로, 상한 초과는 그대로 통과했다.
    for (int limit : new int[] {-1, 0, TriggerService.MAX_TRIGGER_EVENT_LIMIT + 1, 100_000}) {
      assertThatThrownBy(() -> triggerService.getTriggerEvents(pipelineId, limit))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("limit 은 1~200 사이여야 합니다.");
    }
  }

  /** WD-402: 단일 주소 항목은 /128 로 비교해 IPv6 축약·전개 표기가 같은 주소로 맞는다. */
  @Test
  void isIpAllowed_singleIpv6Entry_matchesAcrossNotations() {
    assertThat(triggerService.isIpAllowed("2001:db8::1", List.of("2001:db8:0:0:0:0:0:1"))).isTrue();
    assertThat(triggerService.isIpAllowed("2001:db8:0:0:0:0:0:1", List.of("2001:db8::1"))).isTrue();
    assertThat(triggerService.isIpAllowed("2001:db8::2", List.of("2001:db8::1"))).isFalse();
    assertThat(triggerService.isIpAllowed("10.0.0.1", List.of("10.0.0.1"))).isTrue();
  }

  /**
   * WD-402: 숫자 리터럴이 아닌 옛 항목(앞자리 0·호스트 이름)은 DNS 없이 문자열 비교로만 남는다 — 정규 주소와는 맞지 않고, 같은 문자열을 헤더로 보내는 위조
   * 값도 리터럴이 아니라 통과하지 못한다.
   */
  @Test
  void isIpAllowed_nonLiteralEntryOrSource_neverMatchesBySpoofing() {
    assertThat(triggerService.isIpAllowed("10.0.0.1", List.of("010.0.0.1"))).isFalse();
    assertThat(triggerService.isIpAllowed("010.0.0.1", List.of("010.0.0.1"))).isFalse();
    assertThat(triggerService.isIpAllowed("myhost", List.of("myhost"))).isFalse();
    assertThat(triggerService.isIpAllowed("garbage", List.of("10.0.0.0/8"))).isFalse();
    assertThat(triggerService.isIpAllowed("[2001:db8::7]", List.of("2001:db8::/32"))).isTrue();
  }
}
