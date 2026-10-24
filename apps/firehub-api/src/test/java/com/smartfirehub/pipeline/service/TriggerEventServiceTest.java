package com.smartfirehub.pipeline.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.pipeline.dto.*;
import com.smartfirehub.pipeline.event.PipelineCompletedEvent;
import com.smartfirehub.pipeline.repository.TriggerRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <b>클래스 레벨 {@code @Transactional} 을 뺐다 — 의도된 것이다(P2-b Task 9).</b> 붙어 있으면 {@code
 * pollDatasetChanges} 같은 테넌트 순회 경로가 테스트 트랜잭션에 얹혀 모든 순회 패스가 테넌트 1 의 GUC 로 실행되고, 순회 배선을 지워도 통과하는
 * 사각지대가 생긴다. 롤백이 사라졌으므로 픽스처는 {@link #cleanup()} 에서 직접 지우고 유니크 컬럼은 실행마다 고유하게 만든다.
 */
class TriggerEventServiceTest extends IntegrationTestBase {

  @Autowired private TriggerEventService triggerEventService;

  @Autowired private TriggerService triggerService;

  @Autowired private PipelineService pipelineService;

  @Autowired private TriggerRepository triggerRepository;

  @Autowired private DSLContext dsl;

  @Autowired private TransactionTemplate tx;

  @Autowired private com.smartfirehub.dataset.service.DatasetService datasetService;

  /** WD-221 테스트가 만든 데이터셋 — 테스트가 지우지 않은 것은 정리에서 지운다. */
  private final List<Long> createdDatasetIds = new java.util.ArrayList<>();

  private Long testUserId;
  private Long upstreamPipelineId;
  private Long downstreamPipelineId;

  @BeforeEach
  void setUp() {
    String unique = String.valueOf(System.nanoTime());
    testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "event_test_user_" + unique)
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Event Test User")
            .set(USER.EMAIL, "event_test_" + unique + "@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
    TenantRlsTestSupport.insertActiveMembership(dsl, testUserId, DEFAULT_TEST_TENANT_ID);
    // WD-167: 트리거는 실행 주체(생성자)의 pipeline:execute 를 발화 시점에 확인한다 — 실행 권한을 준다
    TenantRlsTestSupport.runInTenantTransaction(
        tx, DEFAULT_TEST_TENANT_ID, () -> TenantRlsTestSupport.grantAdminRole(dsl, testUserId));

    PipelineDetailResponse upstream =
        pipelineService.createPipeline(
            new CreatePipelineRequest("Upstream Pipeline", "Upstream", List.of()), testUserId);
    upstreamPipelineId = upstream.id();

    PipelineDetailResponse downstream =
        pipelineService.createPipeline(
            new CreatePipelineRequest("Downstream Pipeline", "Downstream", List.of()), testUserId);
    downstreamPipelineId = downstream.id();
  }

  @AfterEach
  void cleanup() {
    // WD-221 데이터셋(카탈로그 행 + 실제 테이블)을 지운다. 삭제 경로가 트리거 감시 목록도 정리하므로 파이프라인보다 먼저 지워도 된다.
    for (Long datasetId : createdDatasetIds) {
      try {
        datasetService.deleteDataset(datasetId);
      } catch (com.smartfirehub.dataset.exception.DatasetNotFoundException ignored) {
        // 테스트 본문에서 이미 지웠다
      }
    }
    // pipeline 을 지우면 트리거·이벤트가 FK CASCADE 로 함께 사라진다. RLS 대상이라 트랜잭션 안에서.
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        DEFAULT_TEST_TENANT_ID,
        () -> {
          dsl.deleteFrom(PIPELINE).where(PIPELINE.ID.eq(downstreamPipelineId)).execute();
          dsl.deleteFrom(PIPELINE).where(PIPELINE.ID.eq(upstreamPipelineId)).execute();
        });
    // 트리거 발화가 감사 로그를 남긴다. 롤백이 없어졌으므로 user 를 지우기 전에 직접 정리해야
    // FK 로 막히지 않는다. V99 부터 audit_log 에도 RLS 가 걸리므로(형태 b) 트랜잭션 밖의 bare
    // delete 는 GUC 가 비어 NULL 테넌트 행만 지우고, 기본 테넌트 행이 남아 "user" 삭제가 FK 로
    // 터진다 — 정리도 테넌트 컨텍스트 트랜잭션 안에서 한다.
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        DEFAULT_TEST_TENANT_ID,
        () -> dsl.deleteFrom(AUDIT_LOG).where(AUDIT_LOG.USER_ID.eq(testUserId)).execute());
    TenantRlsTestSupport.deleteMembership(dsl, testUserId);
    dsl.deleteFrom(USER).where(USER.ID.eq(testUserId)).execute();
  }

  @Test
  void chainTriggerLookup_withMatchingUpstream_findsTriggers() {
    // Create chain trigger: downstream fires when upstream completes with SUCCESS
    triggerService.createTrigger(
        downstreamPipelineId,
        new CreateTriggerRequest(
            "Chain on Success",
            TriggerType.PIPELINE_CHAIN,
            "Fire on upstream success",
            Map.of("upstreamPipelineId", upstreamPipelineId.intValue(), "condition", "SUCCESS")),
        testUserId);

    // Verify chain trigger lookup finds the trigger by upstream pipeline ID
    List<TriggerResponse> chainTriggers =
        triggerRepository.findEnabledChainTriggersByUpstreamId(upstreamPipelineId);
    assertThat(chainTriggers).hasSize(1);
    assertThat(chainTriggers.get(0).pipelineId()).isEqualTo(downstreamPipelineId);
    assertThat(chainTriggers.get(0).config().get("condition")).isEqualTo("SUCCESS");
  }

  @Test
  void chainTriggerLookup_withNonMatchingUpstream_returnsEmpty() {
    // Create chain trigger for a different upstream
    triggerService.createTrigger(
        downstreamPipelineId,
        new CreateTriggerRequest(
            "Chain on Success",
            TriggerType.PIPELINE_CHAIN,
            "Fire on upstream success",
            Map.of("upstreamPipelineId", upstreamPipelineId.intValue(), "condition", "SUCCESS")),
        testUserId);

    // Query with a non-existent upstream pipeline ID
    List<TriggerResponse> chainTriggers =
        triggerRepository.findEnabledChainTriggersByUpstreamId(99999L);
    assertThat(chainTriggers).isEmpty();
  }

  @Test
  void onPipelineCompleted_withDisabledTrigger_doesNotFindIt() {
    // Create and then disable chain trigger
    TriggerResponse created =
        triggerService.createTrigger(
            downstreamPipelineId,
            new CreateTriggerRequest(
                "Disabled Chain",
                TriggerType.PIPELINE_CHAIN,
                "Disabled trigger",
                Map.of(
                    "upstreamPipelineId", upstreamPipelineId.intValue(), "condition", "SUCCESS")),
            testUserId);

    triggerService.toggleTrigger(created.id(), false);

    // Disabled triggers should not appear in lookup
    List<TriggerResponse> chainTriggers =
        triggerRepository.findEnabledChainTriggersByUpstreamId(upstreamPipelineId);
    assertThat(chainTriggers).isEmpty();
  }

  @Test
  void onPipelineCompleted_withNoChainTriggers_doesNothing() {
    // No chain triggers exist for this pipeline
    PipelineCompletedEvent event =
        new PipelineCompletedEvent(upstreamPipelineId, 4L, "COMPLETED", null);

    // Should not throw (async execution won't affect test thread)
    triggerEventService.onPipelineCompleted(event);

    // Verify no chain triggers found for this upstream
    List<TriggerResponse> chainTriggers =
        triggerRepository.findEnabledChainTriggersByUpstreamId(upstreamPipelineId);
    assertThat(chainTriggers).isEmpty();
  }

  @Test
  void fireTrigger_withActivePipeline_recordsEvent() {
    // Create an API trigger (simpler than chain for direct fire test)
    TriggerResponse created =
        triggerService.createTrigger(
            downstreamPipelineId,
            new CreateTriggerRequest("Fire Test", TriggerType.API, null, Map.of()),
            testUserId);

    // Fire trigger directly
    triggerService.fireTrigger(created.id(), Map.of());

    // Verify event was recorded (FIRED or ERROR depending on pipeline execution)
    List<TriggerEventResponse> events = triggerService.getTriggerEvents(downstreamPipelineId, 10);
    assertThat(events).isNotEmpty();
  }

  @Test
  void pollDatasetChanges_withNoDatasetChangeTriggers_doesNothing() {
    // No dataset change triggers exist — should complete without error
    triggerEventService.pollDatasetChanges();
  }

  /**
   * WD-153: 사용자가 취소한 상위 실행(CANCELLED)은 조건이 ANY 여도 체인을 발화하지 않는다. 같은 트리거가 FAILED 로는 발화하는 것을 함께 보여 단언이
   * 공허하지 않게 한다. 러너는 취소 시 완료 이벤트를 아예 발행하지 않으므로(PipelineAsyncRunnerTest) 이 분기는 그 계약이 깨졌을 때의 방어다. 리스너는
   * {@code @Async} 라 프록시를 벗겨 동기로 부른다.
   */
  @Test
  void onPipelineCompleted_cancelledUpstream_doesNotFireEvenAnyCondition() {
    triggerService.createTrigger(
        downstreamPipelineId,
        new CreateTriggerRequest(
            "Chain on Any",
            TriggerType.PIPELINE_CHAIN,
            "Fire on any upstream result",
            Map.of("upstreamPipelineId", upstreamPipelineId.intValue(), "condition", "ANY")),
        testUserId);
    TriggerEventService target =
        org.springframework.test.util.AopTestUtils.getUltimateTargetObject(triggerEventService);

    target.onPipelineCompleted(
        new PipelineCompletedEvent(upstreamPipelineId, 990_001L, "CANCELLED", testUserId));
    assertThat(downstreamExecutionCount()).isZero();

    // 대조: 같은 ANY 트리거가 FAILED 로는 발화한다.
    target.onPipelineCompleted(
        new PipelineCompletedEvent(upstreamPipelineId, 990_002L, "FAILED", testUserId));
    assertThat(downstreamExecutionCount()).isEqualTo(1);
  }

  // -----------------------------------------------------------------------
  // WD-221: 데이터 변경 트리거 — 첫 폴링 무발화·삭제 대상 정리·전부 삭제 시 꺼짐과 이유
  // -----------------------------------------------------------------------

  /** 실제 테이블이 있는 데이터셋을 만든다(행 수 통계 행이 있어야 폴러가 기준선을 잡는다). */
  private Long createWatchedDataset(String label) {
    String table = "wd221_" + label + "_" + System.nanoTime();
    Long id =
        TenantRlsTestSupport.runInTenantTransaction(
            tx,
            DEFAULT_TEST_TENANT_ID,
            () -> {
              dsl.execute(
                  "CREATE TABLE "
                      + com.smartfirehub.global.tenant.DataSchema.qualify(table)
                      + " (id bigint)");
              return dsl.insertInto(DATASET)
                  .set(DATASET.NAME, table)
                  .set(DATASET.TABLE_NAME, table)
                  .set(DATASET.STORAGE_TYPE, "TABLE")
                  .set(DATASET.ORIGIN_TYPE, "SOURCE")
                  .set(DATASET.CREATED_BY, testUserId)
                  .returning(DATASET.ID)
                  .fetchOne()
                  .getId();
            });
    createdDatasetIds.add(id);
    return id;
  }

  private Long createDatasetChangeTrigger(List<Long> datasetIds) {
    return triggerService
        .createTrigger(
            downstreamPipelineId,
            new CreateTriggerRequest(
                "Watch datasets",
                TriggerType.DATASET_CHANGE,
                null,
                Map.of(
                    "datasetIds", datasetIds, "pollingIntervalSeconds", 30, "debounceSeconds", 0)),
            testUserId)
        .id();
  }

  /** 폴링 주기를 기다리지 않고 다음 폴링이 이 트리거를 보게 lastCheckedAt 을 지운 뒤 한 번 폴링한다. */
  private void pollNow(Long triggerId) {
    TriggerResponse t = triggerRepository.findById(triggerId).orElseThrow();
    Map<String, Object> state = new java.util.HashMap<>(t.triggerState());
    state.remove("lastCheckedAt");
    triggerRepository.updateTriggerState(triggerId, state);
    triggerEventService.pollDatasetChanges();
  }

  private int firedCount(Long triggerId) {
    return TenantRlsTestSupport.runInTenantTransaction(
        tx,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.fetchCount(
                TRIGGER_EVENT,
                TRIGGER_EVENT.TRIGGER_ID.eq(triggerId).and(TRIGGER_EVENT.EVENT_TYPE.eq("FIRED"))));
  }

  @SuppressWarnings("unchecked")
  private List<Number> watchedIds(Long triggerId) {
    return (List<Number>)
        triggerRepository.findById(triggerId).orElseThrow().config().get("datasetIds");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> snapshotOf(Long triggerId) {
    Object snap =
        triggerRepository.findById(triggerId).orElseThrow().triggerState().get("lastSnapshot");
    return snap == null ? Map.of() : (Map<String, Object>) snap;
  }

  /**
   * 만든 직후 첫 폴링은 데이터가 바뀌지 않았으므로 발화하지 않고 기준선만 잡는다(예전엔 직전값 -1 과 비교해 빈 데이터셋도 바로 발화). 감시 대상을 추가하는 수정 뒤의
   * 폴링도 같다.
   */
  @Test
  void datasetChange_firstPollAndAddedTarget_doNotFire() {
    Long a = createWatchedDataset("a");
    Long triggerId = createDatasetChangeTrigger(List.of(a));

    pollNow(triggerId);
    assertThat(firedCount(triggerId)).isZero();
    assertThat(snapshotOf(triggerId)).containsKey(String.valueOf(a));

    Long c = createWatchedDataset("c");
    triggerService.updateTrigger(
        triggerId,
        new UpdateTriggerRequest(
            null,
            null,
            null,
            Map.of(
                "datasetIds", List.of(a, c), "pollingIntervalSeconds", 30, "debounceSeconds", 0)),
        testUserId);
    pollNow(triggerId);
    assertThat(firedCount(triggerId)).isZero();
    assertThat(snapshotOf(triggerId)).containsKeys(String.valueOf(a), String.valueOf(c));
  }

  /**
   * 감시 대상 [A, B] 중 B 를 지우면 삭제 경로가 B 를 감시 목록·스냅숏에서 빼고, 이후 여러 번 폴링해도 B 로 발화하지 않는다(예전엔 매 주기 FIRED
   * changedDatasets:[B]). 트리거는 A 를 계속 감시하므로 켜진 채다.
   */
  @Test
  void datasetChange_deletingOneTarget_prunesItAndNeverRefires() {
    Long a = createWatchedDataset("a");
    Long b = createWatchedDataset("b");
    Long triggerId = createDatasetChangeTrigger(List.of(a, b));
    pollNow(triggerId);
    assertThat(snapshotOf(triggerId)).containsKey(String.valueOf(b));

    datasetService.deleteDataset(b);

    assertThat(watchedIds(triggerId)).extracting(Number::longValue).containsExactly(a);
    assertThat(snapshotOf(triggerId)).doesNotContainKey(String.valueOf(b));
    for (int i = 0; i < 3; i++) {
      pollNow(triggerId);
    }
    assertThat(firedCount(triggerId)).isZero();
    TriggerResponse view = triggerService.getTriggerById(triggerId);
    assertThat(view.isEnabled()).isTrue();
    assertThat(view.watchedDatasetsMissing()).isFalse();
  }

  /**
   * 이 수정 이전에 지워져 감시 목록에 남은 id(운영의 옛 행)도 폴러가 발화 없이 감시 목록·스냅숏에서 뺀다. 삭제 경로를 거치지 않은 상태를 만들려고 config 와
   * 스냅숏에 없는 id 를 직접 넣는다.
   */
  @Test
  void datasetChange_legacyDeletedIdInConfig_isPrunedByPollerWithoutFiring() {
    Long a = createWatchedDataset("a");
    Long triggerId = createDatasetChangeTrigger(List.of(a));
    pollNow(triggerId);
    long ghost = 987_654_321L;
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.execute(
                "UPDATE pipeline_trigger SET config = jsonb_set(config, '{datasetIds}',"
                    + " (config -> 'datasetIds') || to_jsonb(?::bigint)),"
                    + " trigger_state = jsonb_set(trigger_state, ARRAY['lastSnapshot', ?::text],"
                    + " '7'::jsonb) WHERE id = ?",
                ghost,
                String.valueOf(ghost),
                triggerId));
    assertThat(watchedIds(triggerId)).extracting(Number::longValue).contains(ghost);

    pollNow(triggerId);
    pollNow(triggerId);

    assertThat(firedCount(triggerId)).isZero();
    assertThat(watchedIds(triggerId)).extracting(Number::longValue).containsExactly(a);
    assertThat(snapshotOf(triggerId)).doesNotContainKey(String.valueOf(ghost));
  }

  /**
   * 감시 대상이 전부 지워지면 트리거가 꺼지고, 조회 응답이 꺼진 이유(watchedDatasetsMissing)를 보이며, 다시 켜기는 한국어로 거부된다. 예전엔 이유 없이
   * 꺼졌고, 다시 켜면 삭제된 데이터셋으로 한 번 더 발화했다.
   */
  @Test
  void datasetChange_allTargetsDeleted_disablesWithReason_andRejectsReenable() {
    Long a = createWatchedDataset("a");
    Long b = createWatchedDataset("b");
    Long triggerId = createDatasetChangeTrigger(List.of(a, b));
    pollNow(triggerId);

    datasetService.deleteDataset(a);
    datasetService.deleteDataset(b);

    TriggerResponse view = triggerService.getTriggerById(triggerId);
    assertThat(view.isEnabled()).isFalse();
    assertThat(view.watchedDatasetsMissing()).isTrue();
    assertThat(watchedIds(triggerId)).isEmpty();
    // 목록 조회도 같은 이유를 보인다
    assertThat(
            triggerService.getTriggers(downstreamPipelineId).stream()
                .filter(t -> t.id().equals(triggerId))
                .findFirst()
                .orElseThrow()
                .watchedDatasetsMissing())
        .isTrue();

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> triggerService.toggleTrigger(triggerId, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(TriggerService.WATCHED_DATASETS_MISSING_MESSAGE);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                triggerService.updateTrigger(
                    triggerId, new UpdateTriggerRequest(null, true, null, null), testUserId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(TriggerService.WATCHED_DATASETS_MISSING_MESSAGE);
    assertThat(triggerRepository.findById(triggerId).orElseThrow().isEnabled()).isFalse();

    // 다시 켜려 해도 폴링이 발화하지 않는다
    pollNow(triggerId);
    assertThat(firedCount(triggerId)).isZero();
  }

  /** 꺼진 트리거를 다시 켜면 기준선을 새로 잡는다 — 꺼진 동안의 차이로 켜자마자 발화하지 않게. 감시 대상이 있으면 켜기는 성공한다(대조). */
  @Test
  void datasetChange_reenable_clearsBaseline() {
    Long a = createWatchedDataset("a");
    Long triggerId = createDatasetChangeTrigger(List.of(a));
    pollNow(triggerId);
    triggerService.toggleTrigger(triggerId, false);
    // 꺼진 동안 기준선이 실제와 달라진 상황(=변경)을 만든다
    Map<String, Object> state =
        new java.util.HashMap<>(triggerRepository.findById(triggerId).orElseThrow().triggerState());
    state.put("lastSnapshot", Map.of(String.valueOf(a), 12345));
    triggerRepository.updateTriggerState(triggerId, state);

    triggerService.toggleTrigger(triggerId, true);

    assertThat(triggerRepository.findById(triggerId).orElseThrow().isEnabled()).isTrue();
    assertThat(snapshotOf(triggerId)).isEmpty();
    pollNow(triggerId);
    assertThat(firedCount(triggerId)).isZero();
  }

  private int downstreamExecutionCount() {
    return TenantRlsTestSupport.runInTenantTransaction(
        tx,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.fetchCount(
                PIPELINE_EXECUTION, PIPELINE_EXECUTION.PIPELINE_ID.eq(downstreamPipelineId)));
  }
}
