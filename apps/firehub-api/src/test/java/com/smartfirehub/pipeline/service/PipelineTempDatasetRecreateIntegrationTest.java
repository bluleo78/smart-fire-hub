package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.pipeline.dto.CreatePipelineRequest;
import com.smartfirehub.pipeline.dto.ExecutionDetailResponse;
import com.smartfirehub.pipeline.dto.PipelineStepRequest;
import com.smartfirehub.pipeline.dto.UpdatePipelineRequest;
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
 * 파이프라인을 지우고 같은 이름으로 다시 만들면 실행이 성공하는지(WD-170) — 실제 실행(러너)까지 관통한다.
 *
 * <p>결함: 출력 미지정 스텝은 첫 실행 때 러너가 「〈파이프라인〉 > 〈스텝〉 (자동생성)」 임시 데이터셋을 만든다. 파이프라인을 지워도 그 데이터셋이 연결만 끊긴 채
 * 남아, 같은 이름으로 다시 만든 파이프라인의 첫 실행이 새 임시 데이터셋을 만들려다 「이미 있는 데이터셋 이름입니다」로 매번 FAILED 였다. WD-155 이후 데이터셋
 * 이름 중복은 「보이는 범위 안」만 막는데 러너 스레드에는 인증 주체가 없어 그 검사가 걸리지 않는다 — 그래서 다른 파이프라인 id 로 다시 만든 경우는 옛 TEMP 가
 * 사라졌는지로 확인하고, 이름 충돌이 여전히 실행 실패로 드러나는 경로(같은 파이프라인에서 스텝을 뺐다가 같은 이름으로 다시 넣기 — 물리 테이블 이름 {@code
 * ptmp_<파이프라인 id>_<스텝>} 충돌)는 실행 성공까지 확인한다.
 *
 * <p>테스트 트랜잭션은 끈다 — 실행은 {@code @Async} 라 별도 스레드·커넥션에서 돌고, 픽스처가 커밋돼 있어야 보인다
 * (PipelineSelectJudgmentIntegrationTest 와 같은 이유). 이름에 난수를 붙여 공유 DB 에서 충돌하지 않게 한다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PipelineTempDatasetRecreateIntegrationTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private DatasetService datasetService;
  @Autowired private PipelineService pipelineService;
  @Autowired private PipelineExecutionService executionService;

  private static final String STEP_NAME = "추출";

  private String suffix;
  private String srcTable;
  private String pipelineName;
  private Long srcDatasetId;
  private Long userId;

  /** 정리할 파이프라인 — 테스트가 만든 것 중 아직 지우지 않은 것. */
  private final List<Long> pipelinesToCleanup = new ArrayList<>();

  @BeforeEach
  void setUpFixtures() {
    suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    srcTable = "wd170_src_" + suffix;
    pipelineName = "WD170 재생성 " + suffix;

    userId = TenantRlsTestSupport.insertUser(dsl, "wd170_user_");
    inTenantFixture(
        () -> TenantRlsTestSupport.insertActiveMembership(dsl, userId, DEFAULT_TEST_TENANT_ID));
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, userId, DEFAULT_TEST_TENANT_ID, "USER");

    srcDatasetId =
        datasetService
            .createDataset(
                new CreateDatasetRequest(
                    "WD170 " + srcTable,
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
    dsl.execute("INSERT INTO " + DataSchema.qualify(srcTable) + " (code) VALUES ('A'), ('B')");
  }

  @AfterEach
  void tearDown() {
    // 파이프라인 삭제가 이제 그 TEMP 까지 지운다. 실행이 감사 로그를 남기므로 user 정리는 마지막이다(FK).
    TenantRlsTestSupport.cleanupAll(
        () -> pipelinesToCleanup.forEach(pipelineService::deletePipeline),
        () -> datasetService.deleteDataset(srcDatasetId),
        () -> TestUsers.cleanup(dsl, fixtureTransactionTemplate, userId, DEFAULT_TEST_TENANT_ID));
  }

  @Test
  void deleteThenRecreateSameName_runSucceeds_andOldTempIsGone() {
    // 1) 첫 파이프라인을 실행해 러너가 TEMP 를 만들게 한다.
    Long firstId = createPipeline();
    assertThat(runAndWait(firstId)).as("첫 실행").isEqualTo("COMPLETED");
    Long firstTemp = tempIdOf(firstId);
    assertThat(firstTemp).as("러너가 만든 TEMP").isNotNull();
    String firstTable = tableNameOf(firstTemp);
    assertThat(tableExists(firstTable)).isTrue();

    // 2) 파이프라인을 지우면 TEMP 메타·물리 테이블이 함께 사라진다.
    pipelineService.deletePipeline(firstId);
    assertThat(datasetExists(firstTemp)).as("옛 TEMP 메타").isFalse();
    assertThat(tableExists(firstTable)).as("옛 TEMP 물리 테이블").isFalse();

    // 3) 같은 이름으로 다시 만든 파이프라인의 실행이 이름 충돌 없이 성공한다.
    Long recreatedId = createPipeline();
    pipelinesToCleanup.add(recreatedId);
    String status = runAndWait(recreatedId);
    assertThat(status).as("재생성 후 실행 — 오류: " + lastError(recreatedId)).isEqualTo("COMPLETED");
    Long secondTemp = tempIdOf(recreatedId);
    assertThat(secondTemp).isNotNull().isNotEqualTo(firstTemp);
  }

  /**
   * 같은 파이프라인에서 스텝을 빼고 저장한 뒤 같은 이름의 스텝을 다시 넣으면 실행이 성공한다(WD-170). 러너 TEMP 의 물리 테이블 이름은 {@code
   * ptmp_<파이프라인 id>_<스텝 이름>} 이라, 뺀 스텝의 TEMP 가 남아 있으면 다시 넣은 스텝의 TEMP 생성이 「테이블 이름을 쓸 수 없음」으로 매번
   * 실패했다(데이터셋 이름 중복 검사와 달리 테이블 이름은 보이는 범위와 무관하게 막는다).
   */
  @Test
  void removeStepThenReaddSameName_runSucceeds() {
    Long pipelineId = createPipeline();
    pipelinesToCleanup.add(pipelineId);
    assertThat(runAndWait(pipelineId)).as("첫 실행").isEqualTo("COMPLETED");
    Long firstTemp = tempIdOf(pipelineId);
    String firstTable = tableNameOf(firstTemp);

    // 스텝을 다른 이름 스텝으로 바꿔 저장(원래 스텝은 빠진다) → 같은 이름 스텝을 다시 넣어 저장.
    pipelineService.updatePipeline(
        pipelineId,
        new UpdatePipelineRequest(pipelineName, null, null, List.of(step("다른스텝"))),
        userId);
    assertThat(datasetExists(firstTemp)).as("뺀 스텝의 TEMP 메타").isFalse();
    assertThat(tableExists(firstTable)).as("뺀 스텝의 TEMP 물리 테이블").isFalse();
    pipelineService.updatePipeline(
        pipelineId,
        new UpdatePipelineRequest(pipelineName, null, null, List.of(step(STEP_NAME))),
        userId);

    String status = runAndWait(pipelineId);
    assertThat(status)
        .as("같은 이름 스텝 재추가 후 실행 — 오류: " + lastError(pipelineId))
        .isEqualTo("COMPLETED");
  }

  // ------------------------------------------------------------------ //
  // helpers
  // ------------------------------------------------------------------ //

  private Long createPipeline() {
    return pipelineService
        .createPipeline(
            new CreatePipelineRequest(pipelineName, "WD-170", List.of(step(STEP_NAME))), userId)
        .id();
  }

  /** 출력 미지정(러너가 TEMP 를 만든다) SQL 스텝. */
  private PipelineStepRequest step(String name) {
    return new PipelineStepRequest(
        name,
        null,
        "SQL",
        "SELECT code FROM " + DataSchema.qualify(srcTable),
        null,
        List.of(srcDatasetId),
        null);
  }

  /** 파이프라인을 실행하고 종료 상태까지 기다린 뒤 최종 상태를 돌려준다. */
  private String runAndWait(Long pipelineId) {
    Long executionId = executionService.executePipeline(pipelineId, userId);
    long deadline = System.currentTimeMillis() + 60_000;
    ExecutionDetailResponse detail = null;
    while (System.currentTimeMillis() < deadline) {
      detail = pipelineService.getExecutionById(pipelineId, executionId);
      if ("COMPLETED".equals(detail.status()) || "FAILED".equals(detail.status())) {
        break;
      }
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
    assertThat(detail).isNotNull();
    return detail.status();
  }

  /** 실행 이력의 원문 오류(조회 API 는 조회자 판정으로 가릴 수 있어 DB 를 직접 읽는다). */
  private String lastError(Long pipelineId) {
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "SELECT max(se.error_message) FROM pipeline_step_execution se"
                        + " JOIN pipeline_execution e ON e.id = se.execution_id WHERE e.pipeline_id = ?",
                    pipelineId)
                .get(0, String.class));
  }

  /** 파이프라인 스텝(하나)의 러너 TEMP id. */
  private Long tempIdOf(Long pipelineId) {
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "SELECT max(d.id) FROM dataset d JOIN pipeline_step s"
                        + " ON s.id = d.source_pipeline_step_id"
                        + " WHERE s.pipeline_id = ? AND d.origin_type = 'TEMP'",
                    pipelineId)
                .get(0, Long.class));
  }

  private String tableNameOf(Long datasetId) {
    return inTenantFixture(
        () -> dsl.fetchValue("SELECT table_name FROM dataset WHERE id = ?", datasetId).toString());
  }

  private boolean datasetExists(Long datasetId) {
    return inTenantFixture(
        () -> dsl.fetchCount(dsl.selectOne().from("dataset").where("id = ?", datasetId)) > 0);
  }

  private boolean tableExists(String table) {
    return inTenantFixture(
        () ->
            Boolean.TRUE.equals(
                dsl.fetchValue("SELECT to_regclass(?) IS NOT NULL", DataSchema.qualify(table))));
  }
}
