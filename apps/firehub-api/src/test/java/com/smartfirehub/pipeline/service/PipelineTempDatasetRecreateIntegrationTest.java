package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                        new DatasetColumnRequest("code", "code", "TEXT", null, true, false, null),
                        // WD-222: SELECT 열 추가·이름 변경·제거 시나리오용 열
                        new DatasetColumnRequest("label", "label", "TEXT", null, true, false, null),
                        new DatasetColumnRequest(
                            "amount", "amount", "INTEGER", null, true, false, null)),
                    null),
                userId)
            .id();
    dsl.execute(
        "INSERT INTO "
            + DataSchema.qualify(srcTable)
            + " (code, label, amount) VALUES ('A', 'a', 1), ('B', 'b', 2)");
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
  // WD-222: 자동 생성(TEMP) 출력 스텝의 SELECT 열 변경
  // ------------------------------------------------------------------ //

  /**
   * 출력 미지정 스텝을 한 번 실행한 뒤 SELECT 열을 추가·이름 변경·제거하면 다음 실행이 각각 성공하고 TEMP 열이 새 SELECT 와 같다(WD-222). 결함:
   * 두 번째 실행부터 스텝 조회가 출력 id 를 TEMP 로 채워(coalesce) 러너의 TEMP 준비 분기가 죽어, 열 추가는 「INSERT has more
   * expressions than target columns」, 이름 변경은 「SELECT alias 를 출력 테이블 컬럼명과 맞춰 주세요」로 실패했고, 제거는 빠진 열이
   * NULL 로 남았다. 저장은 출력을 비워(null) 보낸다 — 고친 웹이 자동 생성 스텝을 저장하는 꼴이다. TEMP 데이터셋 id 는 그대로여야 한다(다음 스텝
   * 참조·화면 표시).
   */
  @Test
  void autoOutputStep_selectColumnChanges_rebuildTempEachRun() {
    Long pipelineId = createPipeline(sqlStep(STEP_NAME, "code, label", null, null));
    pipelinesToCleanup.add(pipelineId);
    assertThat(runAndWait(pipelineId)).as("첫 실행").isEqualTo("COMPLETED");
    Long temp = tempIdOf(pipelineId);
    assertThat(tempColumns(temp)).containsExactly("code", "label");

    assertSelectChangeRuns(
        pipelineId, temp, "code, label, amount", null, "code", "label", "amount");
    assertSelectChangeRuns(pipelineId, temp, "code, label AS title", null, "code", "title");
    assertSelectChangeRuns(pipelineId, temp, "code", null, "code");
  }

  /**
   * 화면 저장 경로(WD-222): 웹 편집기는 조회 응답의 해석된 출력(TEMP id)과 스텝 id 를 그대로 되돌려 보낸다. 서버가 그 값을 자기 TEMP 로 알아보고
   * 출력을 비워(null) 저장해야 하고, 실행은 열 변경마다 성공해야 한다.
   */
  @Test
  void screenSaveRoundTripsTempId_normalizedToNull_andColumnChangesRun() {
    Long pipelineId = createPipeline(sqlStep(STEP_NAME, "code, label", null, null));
    pipelinesToCleanup.add(pipelineId);
    assertThat(runAndWait(pipelineId)).as("첫 실행").isEqualTo("COMPLETED");
    Long temp = tempIdOf(pipelineId);

    // 조회 응답은 화면 표시를 위해 TEMP id 를 채워 주되, 자동 생성임을 함께 알린다.
    var loaded = inTenantFixture(() -> pipelineService.getPipelineById(pipelineId)).steps().get(0);
    assertThat(loaded.outputDatasetId()).as("해석된 출력(TEMP)").isEqualTo(temp);
    assertThat(loaded.outputAutoGenerated()).as("자동 생성 여부").isTrue();

    assertSelectChangeRuns(
        pipelineId, temp, "code, label, amount", loaded.id(), "code", "label", "amount");
    assertThat(storedOutputId(pipelineId)).as("저장 후 output_dataset_id").isNull();
    assertSelectChangeRuns(pipelineId, temp, "code, label AS title", loaded.id(), "code", "title");
    assertSelectChangeRuns(pipelineId, temp, "code", loaded.id(), "code");
    assertThat(storedOutputId(pipelineId)).as("저장 후 output_dataset_id").isNull();
  }

  /**
   * 이미 운영 DB 에 output_dataset_id 가 TEMP id 로 고정된 스텝(이전 화면 저장)도 저장을 거치지 않고 자동 생성처럼 동작한다(WD-222) —
   * 러너가 출력 id 가 그 스텝 소유 TEMP 인지 DB 로 판정한다. 고정된 상태에서는 TEMP 를 삭제할 수 없으므로(출력 참조 거부) 같은 id 로 다시 만들어야
   * 한다.
   */
  @Test
  void dbPinnedTempOutput_columnChangesRun_withoutSave() {
    Long pipelineId = createPipeline(sqlStep(STEP_NAME, "code, label", null, null));
    pipelinesToCleanup.add(pipelineId);
    assertThat(runAndWait(pipelineId)).as("첫 실행").isEqualTo("COMPLETED");
    Long temp = tempIdOf(pipelineId);

    for (String[] change :
        new String[][] {
          {"code, label, amount", "code", "label", "amount"},
          {"code, label AS title", "code", "title"},
          {"code", "code"}
        }) {
      // 저장 경로를 거치지 않고 DB 를 직접 고친다 — 고정 출력 + 바뀐 SQL.
      setStepDirectly(pipelineId, temp, selectSql(change[0]));
      String status = runAndWait(pipelineId);
      assertThat(status)
          .as("고정 출력 + SELECT '" + change[0] + "' 실행 — 오류: " + lastError(pipelineId))
          .isEqualTo("COMPLETED");
      assertThat(tempIdOf(pipelineId)).as("TEMP id 유지").isEqualTo(temp);
      assertThat(tempColumns(temp))
          .containsExactly(java.util.Arrays.copyOfRange(change, 1, change.length));
      assertThat(rowCount(temp)).as("적재 행 수").isEqualTo(2);
    }
  }

  // ------------------------------------------------------------------ //
  // WD-224: 다른 스텝의 TEMP 를 출력으로 지정하는 저장 거부
  // ------------------------------------------------------------------ //

  /**
   * 다른 파이프라인 스텝이 만든 TEMP 를 출력으로 지정한 생성·수정은 400(IllegalArgumentException)과 한국어 안내로 거부된다(WD-224). 결함:
   * 저장 검증이 가시성만 봐 204 로 저장됐고, 두 파이프라인이 실행될 때마다 서로의 결과를 덮어썼다. 거부된 수정은 원래 출력(자동 생성)을 바꾸지 않는다.
   */
  @Test
  void otherPipelineTempAsOutput_createAndUpdateRejected() {
    Long ownerPipeline = createPipeline(sqlStep(STEP_NAME, "code", null, null));
    pipelinesToCleanup.add(ownerPipeline);
    assertThat(runAndWait(ownerPipeline)).as("TEMP 를 만드는 실행").isEqualTo("COMPLETED");
    Long foreignTemp = tempIdOf(ownerPipeline);
    assertThat(foreignTemp).isNotNull();

    // 생성 거부
    assertThatThrownBy(
            () ->
                pipelineService.createPipeline(
                    new CreatePipelineRequest(
                        pipelineName + " X",
                        null,
                        List.of(sqlStep("쓰기", "code", foreignTemp, null))),
                    userId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("스텝 '쓰기'")
        .hasMessageContaining("자동으로 만든 임시 데이터셋")
        .hasMessageContaining("「자동 생성」");

    // 수정 거부 — 출력 미지정으로 만든 파이프라인을 남의 TEMP 로 바꾸려는 저장
    Long writer =
        pipelineService
            .createPipeline(
                new CreatePipelineRequest(
                    pipelineName + " X", null, List.of(sqlStep("쓰기", "code", null, null))),
                userId)
            .id();
    pipelinesToCleanup.add(writer);
    Long writerStepId = stepIdOf(writer, "쓰기");
    assertThatThrownBy(
            () ->
                pipelineService.updatePipeline(
                    writer,
                    new UpdatePipelineRequest(
                        pipelineName + " X",
                        null,
                        null,
                        List.of(sqlStep("쓰기", "code", foreignTemp, writerStepId))),
                    userId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("자동으로 만든 임시 데이터셋");
    assertThat(storedOutputId(writer)).as("거부된 수정은 출력을 바꾸지 않는다").isNull();
  }

  /**
   * 같은 파이프라인 안 다른 스텝의 TEMP 도 거부된다(WD-224) — 덮어쓰기는 같다. 수정 경로의 왕복 보존 집합에는 형제 스텝의 TEMP 가 이미 들어 있어, 판정을
   * 왕복 보존 예외 안에 두면 통과해 버린다(이 테스트가 그 회귀를 잡는다). 이미 DB 에 그렇게 고정된 스텝(레거시)도 다음 저장에서 같은 안내로 막힌다.
   */
  @Test
  void siblingStepTempAsOutput_rejected_evenWhenAlreadyPinned() {
    Long pipelineId =
        pipelineService
            .createPipeline(
                new CreatePipelineRequest(
                    pipelineName,
                    null,
                    List.of(sqlStep("A", "code", null, null), sqlStep("B", "code", null, null))),
                userId)
            .id();
    pipelinesToCleanup.add(pipelineId);
    assertThat(runAndWait(pipelineId)).as("두 스텝 TEMP 생성 실행").isEqualTo("COMPLETED");
    Long stepA = stepIdOf(pipelineId, "A");
    Long stepB = stepIdOf(pipelineId, "B");
    Long tempA = tempOfStep(stepA);
    assertThat(tempA).isNotNull();

    java.util.function.Supplier<UpdatePipelineRequest> pinBToA =
        () ->
            new UpdatePipelineRequest(
                pipelineName,
                null,
                null,
                List.of(sqlStep("A", "code", tempA, stepA), sqlStep("B", "code", tempA, stepB)));
    assertThatThrownBy(() -> pipelineService.updatePipeline(pipelineId, pinBToA.get(), userId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("스텝 'B'")
        .hasMessageContaining("자동으로 만든 임시 데이터셋");

    // 레거시: B 가 이미 A 의 TEMP 로 고정돼 있어도(왕복 보존 대상) 같은 값을 되돌려 보내는 저장은 막히고, 무엇을 바꿀지 알려 준다.
    inTenantFixture(
        () ->
            dsl.execute(
                "UPDATE pipeline_step SET output_dataset_id = ? WHERE id = ?", tempA, stepB));
    assertThatThrownBy(() -> pipelineService.updatePipeline(pipelineId, pinBToA.get(), userId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("스텝 'B'")
        .hasMessageContaining("새 데이터셋으로 바꾸거나 「자동 생성」으로 두세요");
    // 안내대로 B 를 자동 생성으로 바꾸면 저장된다(A 는 자기 TEMP 왕복 → null 정규화).
    pipelineService.updatePipeline(
        pipelineId,
        new UpdatePipelineRequest(
            pipelineName,
            null,
            null,
            List.of(sqlStep("A", "code", tempA, stepA), sqlStep("B", "code", null, stepB))),
        userId);
    assertThat(storedOutputOfStep(stepA)).as("A 자기 TEMP 왕복 → 정규화").isNull();
    assertThat(storedOutputOfStep(stepB)).as("B 자동 생성").isNull();
  }

  /** 자기 TEMP 왕복 저장은 성공하고 null 로 정규화되며, 일반(사용자) 데이터셋 출력은 생성·수정 모두 허용된다(WD-224 가 막지 않는 경로). */
  @Test
  void ownTempRoundTrip_andRegularDatasetOutput_allowed() {
    Long pipelineId = createPipeline(sqlStep(STEP_NAME, "code", null, null));
    pipelinesToCleanup.add(pipelineId);
    assertThat(runAndWait(pipelineId)).isEqualTo("COMPLETED");
    Long temp = tempIdOf(pipelineId);
    Long stepId = stepIdOf(pipelineId, STEP_NAME);

    pipelineService.updatePipeline(
        pipelineId,
        new UpdatePipelineRequest(
            pipelineName, null, null, List.of(sqlStep(STEP_NAME, "code", temp, stepId))),
        userId);
    assertThat(storedOutputId(pipelineId)).as("자기 TEMP 왕복 → null").isNull();

    // 일반 데이터셋(원천 데이터셋)을 출력으로 — 저장만 본다(실행하지 않는다).
    pipelineService.updatePipeline(
        pipelineId,
        new UpdatePipelineRequest(
            pipelineName, null, null, List.of(sqlStep(STEP_NAME, "code", srcDatasetId, stepId))),
        userId);
    assertThat(storedOutputId(pipelineId)).isEqualTo(srcDatasetId);
    Long created =
        pipelineService
            .createPipeline(
                new CreatePipelineRequest(
                    pipelineName + " R", null, List.of(sqlStep("쓰기", "code", srcDatasetId, null))),
                userId)
            .id();
    pipelinesToCleanup.add(created);
    assertThat(storedOutputId(created)).isEqualTo(srcDatasetId);
  }

  private Long stepIdOf(Long pipelineId, String stepName) {
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "SELECT id FROM pipeline_step WHERE pipeline_id = ? AND name = ?",
                    pipelineId,
                    stepName)
                .get(0, Long.class));
  }

  private Long tempOfStep(Long stepId) {
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "SELECT max(id) FROM dataset WHERE source_pipeline_step_id = ?"
                        + " AND origin_type = 'TEMP'",
                    stepId)
                .get(0, Long.class));
  }

  private Long storedOutputOfStep(Long stepId) {
    return inTenantFixture(
        () ->
            dsl.fetchOne("SELECT output_dataset_id FROM pipeline_step WHERE id = ?", stepId)
                .get(0, Long.class));
  }

  /** 요청을 저장하고 실행해 성공·TEMP id 유지·열 구성·값(빠진/새 열이 NULL 이 아님)을 확인한다. */
  private void assertSelectChangeRuns(
      Long pipelineId, Long temp, String selectList, Long stepId, String... expectedColumns) {
    pipelineService.updatePipeline(
        pipelineId,
        new UpdatePipelineRequest(
            pipelineName,
            null,
            null,
            // stepId 가 있으면 화면처럼 TEMP id 를 출력으로 되돌려 보낸다.
            List.of(sqlStep(STEP_NAME, selectList, stepId == null ? null : temp, stepId))),
        userId);
    String status = runAndWait(pipelineId);
    assertThat(status)
        .as("SELECT '" + selectList + "' 실행 — 오류: " + lastError(pipelineId))
        .isEqualTo("COMPLETED");
    assertThat(tempIdOf(pipelineId)).as("TEMP id 유지").isEqualTo(temp);
    assertThat(tempColumns(temp)).containsExactly(expectedColumns);
    assertThat(physicalColumns(temp)).as("물리 테이블 열").containsAll(List.of(expectedColumns));
    assertThat(rowCount(temp)).as("적재 행 수").isEqualTo(2);
    String last = expectedColumns[expectedColumns.length - 1];
    assertThat(nullCount(temp, last)).as("마지막 열 '" + last + "' 의 NULL 수").isZero();
  }

  private String selectSql(String selectList) {
    return "SELECT " + selectList + " FROM " + DataSchema.qualify(srcTable);
  }

  private Long createPipeline(PipelineStepRequest step) {
    return pipelineService
        .createPipeline(new CreatePipelineRequest(pipelineName, "WD-222", List.of(step)), userId)
        .id();
  }

  /** SQL 스텝 요청 — outputDatasetId·id 는 화면 왕복을 흉내 낼 때만 채운다. */
  private PipelineStepRequest sqlStep(
      String name, String selectList, Long outputDatasetId, Long stepId) {
    return new PipelineStepRequest(
        name,
        null,
        "SQL",
        selectSql(selectList),
        outputDatasetId,
        List.of(srcDatasetId),
        null,
        "REPLACE",
        null,
        null,
        null,
        null,
        stepId);
  }

  private void setStepDirectly(Long pipelineId, Long outputId, String sql) {
    inTenantFixture(
        () ->
            dsl.execute(
                "UPDATE pipeline_step SET output_dataset_id = ?, script_content = ?"
                    + " WHERE pipeline_id = ?",
                outputId,
                sql,
                pipelineId));
  }

  private Long storedOutputId(Long pipelineId) {
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "SELECT output_dataset_id FROM pipeline_step WHERE pipeline_id = ?", pipelineId)
                .get(0, Long.class));
  }

  /** TEMP 의 사용자 열(dataset_column, 순서대로). */
  private List<String> tempColumns(Long datasetId) {
    return inTenantFixture(
        () ->
            dsl.fetch(
                    "SELECT column_name FROM dataset_column WHERE dataset_id = ?"
                        + " ORDER BY column_order",
                    datasetId)
                .getValues(0, String.class));
  }

  private List<String> physicalColumns(Long datasetId) {
    String table = tableNameOf(datasetId);
    return inTenantFixture(
        () ->
            dsl.fetch(
                    "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = ? AND table_name = ?",
                    DataSchema.current(),
                    table)
                .getValues(0, String.class));
  }

  private Integer rowCount(Long datasetId) {
    String table = tableNameOf(datasetId);
    return inTenantFixture(
        () ->
            dsl.fetchOne("SELECT count(*) FROM " + DataSchema.qualify(table))
                .get(0, Integer.class));
  }

  private Integer nullCount(Long datasetId, String column) {
    String table = tableNameOf(datasetId);
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "SELECT count(*) FROM "
                        + DataSchema.qualify(table)
                        + " WHERE "
                        + column
                        + " IS NULL")
                .get(0, Integer.class));
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
