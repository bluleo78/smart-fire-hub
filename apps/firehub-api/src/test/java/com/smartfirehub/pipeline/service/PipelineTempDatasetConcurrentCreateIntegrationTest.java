package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.datastore.DataStoreDb;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.pipeline.dto.CreatePipelineRequest;
import com.smartfirehub.pipeline.dto.ExecutionDetailResponse;
import com.smartfirehub.pipeline.dto.PipelineStepRequest;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 출력 데이터셋이 없는 SQL 단계를 동시에 여러 번 실행해도 임시(TEMP) 데이터셋 생성 경합으로 실패하지 않는지(WD-541).
 *
 * <p>결함: 러너가 「기존 TEMP 있음?」을 확인한 뒤 없으면 생성하는데 둘이 원자적이지 않아, 첫 실행 무렵 동시 실행 둘이 모두 「없음」을 보고 같은 {@code
 * ptmp_<파이프라인 id>_<단계>} 를 만들다 유일 제약(idx_dataset_table_name)에 걸려 진 쪽이 FAILED 였고, 단계 오류에 SQL 원문이 그대로
 * 저장됐다. 실행기 풀(코어 5)이 한 번에 5개를 병렬로 돌리므로 같은 파이프라인을 5번 연달아 실행해 경합을 키우고, 경합이 좁은 창이라 파이프라인을 새로 만들며 여러
 * 라운드 반복한다.
 *
 * <p>테스트 트랜잭션은 끈다 — 실행은 {@code @Async} 라 별도 스레드·커넥션에서 돌고 픽스처가 커밋돼 있어야 보인다
 * (PipelineTempDatasetRecreateIntegrationTest 와 같은 이유).
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PipelineTempDatasetConcurrentCreateIntegrationTest extends IntegrationTestBase {

  /** 한 라운드에서 동시에 던지는 실행 수 — 실행기 풀 코어 수(5)와 같거나 크다. */
  private static final int RUNS_PER_ROUND = 5;

  /** 경합이 좁은 창이라 라운드를 반복한다(라운드마다 새 파이프라인 = 새 첫 실행). */
  private static final int ROUNDS = 6;

  @Autowired private DSLContext dsl;

  /** 원본 데이터 테이블(data 스키마)이 있는 데이터 저장소 — 분리 모드에서는 플랫폼 DB 와 다른 서버다(WD-704). */
  @Autowired @DataStoreDb private DSLContext dataDsl;

  @Autowired private DatasetService datasetService;
  @Autowired private PipelineService pipelineService;
  @Autowired private PipelineExecutionService executionService;

  private String suffix;
  private String srcTable;
  private Long srcDatasetId;
  private Long userId;
  private final List<Long> pipelinesToCleanup = new ArrayList<>();

  @BeforeEach
  void setUpFixtures() {
    suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    srcTable = "wd541_src_" + suffix;
    userId = TenantRlsTestSupport.insertUser(dsl, "wd541_user_");
    inTenantFixture(
        () -> TenantRlsTestSupport.insertActiveMembership(dsl, userId, DEFAULT_TEST_TENANT_ID));
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, userId, DEFAULT_TEST_TENANT_ID, "USER");
    srcDatasetId =
        datasetService
            .createDataset(
                new CreateDatasetRequest(
                    "WD541 " + srcTable,
                    srcTable,
                    null,
                    null,
                    "TABLE",
                    "SOURCE",
                    List.of(
                        new DatasetColumnRequest("code", "code", "TEXT", null, true, false, null)),
                    null),
                userId)
            .id();
    // 원본 행은 물리 테이블이 있는 데이터 저장소에 넣는다 — 플랫폼 dsl 로 넣으면 분리 모드에서 relation 이 없다.
    dataDsl.execute("INSERT INTO " + DataSchema.qualify(srcTable) + " (code) VALUES ('A'), ('B')");
  }

  @AfterEach
  void tearDown() {
    TenantRlsTestSupport.cleanupAll(
        () -> pipelinesToCleanup.forEach(pipelineService::deletePipelineUnchecked),
        () -> datasetService.deleteDataset(srcDatasetId),
        () -> TestUsers.cleanup(dsl, fixtureTransactionTemplate, userId, DEFAULT_TEST_TENANT_ID));
  }

  /** 같은 파이프라인의 첫 실행을 동시에 여러 번 던져도 모두 COMPLETED, TEMP 는 1개, 오류 문구에 SQL·제약 이름이 없다. */
  @Test
  void concurrentFirstRuns_allComplete_andCreateExactlyOneTemp() {
    for (int round = 0; round < ROUNDS; round++) {
      Long pipelineId =
          pipelineService
              .createPipeline(
                  new CreatePipelineRequest(
                      "WD541 동시 " + suffix + "_" + round,
                      null,
                      List.of(
                          new PipelineStepRequest(
                              "추출",
                              null,
                              "SQL",
                              "SELECT code FROM " + DataSchema.qualify(srcTable),
                              null,
                              List.of(srcDatasetId),
                              null))),
                  userId)
              .id();
      pipelinesToCleanup.add(pipelineId);

      List<Long> executionIds = new ArrayList<>();
      for (int i = 0; i < RUNS_PER_ROUND; i++) {
        executionIds.add(executionService.executePipeline(pipelineId, userId));
      }
      List<String> statuses = new ArrayList<>();
      for (Long executionId : executionIds) {
        statuses.add(waitTerminal(pipelineId, executionId));
      }

      String errors = stepErrors(pipelineId);
      assertThat(statuses).as("라운드 %d 상태 — 오류: %s", round, errors).allMatch("COMPLETED"::equals);
      assertThat(errors == null ? "" : errors)
          .doesNotContain("idx_dataset_table_name")
          .doesNotContainIgnoringCase("insert into");
      assertThat(tempCount(pipelineId)).as("라운드 %d TEMP 데이터셋 수", round).isEqualTo(1);
    }
  }

  /** 종료 상태(COMPLETED·FAILED·CANCELLED)가 될 때까지 기다린다. */
  private String waitTerminal(Long pipelineId, Long executionId) {
    long deadline = System.currentTimeMillis() + 90_000;
    ExecutionDetailResponse detail = null;
    while (System.currentTimeMillis() < deadline) {
      detail = pipelineService.getExecutionById(pipelineId, executionId);
      if (List.of("COMPLETED", "FAILED", "CANCELLED").contains(detail.status())) {
        break;
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
    assertThat(detail).isNotNull();
    return detail.status();
  }

  /** 이 파이프라인 모든 단계 실행의 원문 오류(DB 직접 조회 — 조회 API 는 가릴 수 있다). */
  private String stepErrors(Long pipelineId) {
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "SELECT string_agg(se.error_message, ' | ') FROM pipeline_step_execution se"
                        + " JOIN pipeline_execution e ON e.id = se.execution_id"
                        + " WHERE e.pipeline_id = ? AND se.error_message IS NOT NULL",
                    pipelineId)
                .get(0, String.class));
  }

  private int tempCount(Long pipelineId) {
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "SELECT count(*) FROM dataset d JOIN pipeline_step s"
                        + " ON s.id = d.source_pipeline_step_id"
                        + " WHERE s.pipeline_id = ? AND d.origin_type = 'TEMP'",
                    pipelineId)
                .get(0, Integer.class));
  }
}
