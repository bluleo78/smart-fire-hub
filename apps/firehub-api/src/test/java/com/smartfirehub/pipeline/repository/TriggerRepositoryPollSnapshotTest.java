package com.smartfirehub.pipeline.repository;

import static com.smartfirehub.jooq.Tables.PIPELINE;
import static com.smartfirehub.jooq.Tables.PIPELINE_TRIGGER;
import static org.assertj.core.api.Assertions.assertThat;

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
 * 데이터 변경 트리거 폴러 상태 정리(WD-399) — 트리거를 다시 켤 때·감시 데이터셋을 지울 때 행 수 스냅숏과 함께 변경 카운터 스냅숏({@code
 * lastChangeSnapshot})도 지워지는지 본다. 남으면 다시 켠 트리거가 꺼진 동안의 변경으로 켜자마자 발화하거나, 지운 데이터셋의 기준선이 상태에 남는다.
 */
class TriggerRepositoryPollSnapshotTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private TriggerRepository triggerRepository;

  private SecurityFixture fx;
  private long creator;
  private long pipelineId;
  private long triggerId;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    creator = fx.createUser("trps_creator");
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> {
          pipelineId =
              dsl.insertInto(PIPELINE)
                  .set(PIPELINE.NAME, "trps" + System.nanoTime())
                  .set(PIPELINE.IS_ACTIVE, true)
                  .set(PIPELINE.CREATED_BY, creator)
                  .returning(PIPELINE.ID)
                  .fetchOne()
                  .getId();
          // 폴러가 남기는 상태 그대로 — 감시 데이터셋 101·102 의 행 수·변경 카운터 + 마지막 확인 시각
          triggerId =
              dsl.insertInto(PIPELINE_TRIGGER)
                  .set(PIPELINE_TRIGGER.PIPELINE_ID, pipelineId)
                  .set(PIPELINE_TRIGGER.TRIGGER_TYPE, "DATASET_CHANGE")
                  .set(PIPELINE_TRIGGER.NAME, "변경 감시")
                  .set(PIPELINE_TRIGGER.IS_ENABLED, true)
                  .set(PIPELINE_TRIGGER.CONFIG, JSONB.valueOf("{\"datasetIds\":[101,102]}"))
                  .set(
                      PIPELINE_TRIGGER.TRIGGER_STATE,
                      JSONB.valueOf(
                          "{\"lastSnapshot\":{\"101\":3,\"102\":2},"
                              + "\"lastChangeSnapshot\":{\"101\":40,\"102\":9},"
                              + "\"lastRelationSnapshot\":{\"101\":7,\"102\":8},"
                              + "\"lastCheckedAt\":\"2026-10-30T09:00:00\"}"))
                  .set(PIPELINE_TRIGGER.CREATED_BY, creator)
                  .returning(PIPELINE_TRIGGER.ID)
                  .fetchOne()
                  .getId();
        });
  }

  @AfterEach
  void tearDown() {
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> {
          dsl.deleteFrom(PIPELINE_TRIGGER).where(PIPELINE_TRIGGER.ID.eq(triggerId)).execute();
          dsl.deleteFrom(PIPELINE).where(PIPELINE.ID.eq(pipelineId)).execute();
        });
    fx.deleteUser(creator);
  }

  /** 테넌트 트랜잭션에서 작업을 하고 저장된 trigger_state 를 읽는다. */
  private Map<String, Object> runThenReadState(Runnable action) {
    return TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> {
          action.run();
          return triggerRepository.findById(triggerId).orElseThrow().triggerState();
        });
  }

  @Test
  void clearPollSnapshot_removesRowAndChangeSnapshots() {
    Map<String, Object> state =
        runThenReadState(() -> triggerRepository.clearPollSnapshot(triggerId));

    assertThat(state)
        .doesNotContainKeys(
            "lastSnapshot", "lastChangeSnapshot", "lastRelationSnapshot", "lastCheckedAt");
  }

  @Test
  void removeWatchedDataset_removesItsEntryFromBothSnapshots() {
    Map<String, Object> state =
        runThenReadState(() -> triggerRepository.removeWatchedDataset(101L));

    assertThat(state.get("lastSnapshot")).isEqualTo(Map.of("102", 2));
    assertThat(state.get("lastChangeSnapshot")).isEqualTo(Map.of("102", 9));
    assertThat(state.get("lastRelationSnapshot")).isEqualTo(Map.of("102", 8));
    assertThat(state.get("lastCheckedAt")).isEqualTo("2026-10-30T09:00:00");
  }
}
