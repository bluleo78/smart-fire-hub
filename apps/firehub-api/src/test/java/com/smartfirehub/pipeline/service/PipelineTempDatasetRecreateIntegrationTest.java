package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DataTableService;
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
        () -> pipelinesToCleanup.forEach(pipelineService::deletePipelineUnchecked),
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
    pipelineService.deletePipelineUnchecked(firstId);
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

  // ------------------------------------------------------------------ //
  // WD-238: 열 변경 후 본 실행이 실패하면 이전 결과 보존
  // ------------------------------------------------------------------ //

  /** 열을 바꾼 SELECT 가 LIMIT 0 사전 점검은 통과하고 실제 행에서 0 나누기로 실패한다(원천의 amount=2 행). */
  private static final String FAILING_SELECT = "code, 10 / (amount - 2) AS bad";

  /**
   * 자동 생성(TEMP) 출력 스텝의 SELECT 열을 바꾼 실행이 본 SQL 에서 실패하면 이전 결과 행·열 메타(id·검색 대상 설정 포함)·물리 테이블(OID)이 그대로
   * 남고 데이터셋 id 도 그대로다 — REPLACE·APPEND 모두(WD-238). 결함: 러너가 TEMP 를 새 열 구성으로 DROP→CREATE 해 커밋한 뒤 본
   * SQL 을 다른 트랜잭션에서 돌려, 실패하면 행이 0 이고 열 메타는 실패한 SQL 의 것인 빈 테이블이 남았다. 보관본은 남지 않아야 하고, 고친 SQL 로 다시 돌리면
   * 새 열 구성으로 성공한다.
   */
  @Test
  void columnChangeThenRunFails_keepsPreviousRowsAndColumns_replaceAndAppend() {
    for (String strategy : List.of("REPLACE", "APPEND")) {
      String name = pipelineName + " " + strategy;
      Long pipelineId =
          pipelineService
              .createPipeline(
                  new CreatePipelineRequest(
                      name,
                      "WD-238",
                      List.of(sqlStep(STEP_NAME, "code, label", null, null, strategy))),
                  userId)
              .id();
      pipelinesToCleanup.add(pipelineId);
      assertThat(runAndWait(pipelineId)).as(strategy + " 첫 실행").isEqualTo("COMPLETED");
      Long temp = tempIdOf(pipelineId);
      // 검색 대상 설정은 응답 DTO 에 없는 열이다 — 되돌리기가 행 그대로 보관했는지 본다.
      inTenantFixture(
          () ->
              dsl.execute(
                  "UPDATE dataset_column SET is_searchable = true"
                      + " WHERE dataset_id = ? AND column_name = 'label'",
                  temp));
      List<String> columnIdsBefore = columnIdentity(temp);
      Long oidBefore = tableOid(temp);

      updateStep(pipelineId, name, sqlStep(STEP_NAME, FAILING_SELECT, null, null, strategy));
      assertThat(runAndWait(pipelineId)).as(strategy + " 열 변경 + 실행 오류").isEqualTo("FAILED");
      assertThat(lastError(pipelineId)).contains("0");

      assertThat(tempIdOf(pipelineId)).as(strategy + " TEMP id 유지").isEqualTo(temp);
      assertThat(tempColumns(temp)).as(strategy + " 열 메타 보존").containsExactly("code", "label");
      assertThat(columnIdentity(temp))
          .as(strategy + " 컬럼 행(id·검색 대상) 보존")
          .isEqualTo(columnIdsBefore);
      assertThat(physicalColumns(temp))
          .as(strategy + " 물리 열")
          .contains("code", "label")
          .doesNotContain("bad");
      assertThat(rowCount(temp)).as(strategy + " 이전 행 보존").isEqualTo(2);
      assertThat(tableOid(temp)).as(strategy + " 같은 물리 테이블로 되돌림").isEqualTo(oidBefore);
      assertThat(backupExists(temp)).as(strategy + " 보관본 정리").isFalse();
      assertThat(updatedAtIndexExists(temp)).as(strategy + " _updated_at 인덱스 되돌림").isTrue();

      // SQL 을 고치면 새 열 구성으로 성공하고 보관본이 남지 않는다.
      updateStep(
          pipelineId,
          name,
          sqlStep(STEP_NAME, "code, amount * 10 AS scaled", null, null, strategy));
      assertThat(runAndWait(pipelineId))
          .as(strategy + " 고친 SQL — 오류: " + lastError(pipelineId))
          .isEqualTo("COMPLETED");
      assertThat(tempIdOf(pipelineId)).isEqualTo(temp);
      assertThat(tempColumns(temp)).containsExactly("code", "scaled");
      assertThat(rowCount(temp)).as(strategy + " 새 행").isEqualTo(2);
      assertThat(nullCount(temp, "scaled")).isZero();
      assertThat(backupExists(temp)).isFalse();
      assertThat(updatedAtIndexExists(temp)).isTrue();
    }
  }

  /**
   * 서버가 재생성 도중 멈춰 남은 보관본(되돌리기·정리 전)은 다음 재생성이 지우고 실행은 성공한다. 파이프라인(=TEMP) 삭제도 남은 보관본을 함께 지운다(WD-238).
   */
  @Test
  void leftoverRebuildBackup_clearedByNextRebuild_andByDelete() {
    Long pipelineId = createPipeline(sqlStep(STEP_NAME, "code, label", null, null));
    pipelinesToCleanup.add(pipelineId);
    assertThat(runAndWait(pipelineId)).isEqualTo("COMPLETED");
    Long temp = tempIdOf(pipelineId);
    String table = tableNameOf(temp);
    plantBackup(table);
    assertThat(backupExists(temp)).isTrue();

    assertSelectChangeRuns(
        pipelineId, temp, "code, label, amount", null, "code", "label", "amount");

    plantBackup(table);
    pipelineService.deletePipelineUnchecked(pipelineId);
    pipelinesToCleanup.remove(pipelineId);
    assertThat(
            inTenantFixture(
                () ->
                    dsl.fetchValue(
                        "SELECT to_regclass(?) IS NOT NULL",
                        DataSchema.qualify(DataTableService.rebuildBackupTableName(table)))))
        .as("삭제가 남은 보관본도 지운다")
        .isEqualTo(false);
  }

  /**
   * PG 회귀(WD-256): 크래시 흔적 맞춤은 PG 에서 아무 DDL 도 내지 않고 {@code NONE} 이다 — PG 보관은 재생성 트랜잭션과 함께 커밋·롤백돼 롤백
   * 흔적이 없고, 스텝 도중 멈춰 남은 보관본은 다음 재생성이 지운다(위 테스트). 심어 둔 보관본이 그대로 남는지로 「DDL 없음」을 본다.
   */
  @Test
  void reconcileOnPg_isNoopNone_leftoverBackupUntouched() {
    Long pipelineId = createPipeline(sqlStep(STEP_NAME, "code, label", null, null));
    pipelinesToCleanup.add(pipelineId);
    assertThat(runAndWait(pipelineId)).isEqualTo("COMPLETED");
    Long temp = tempIdOf(pipelineId);
    plantBackup(tableNameOf(temp));

    assertThat(datasetService.reconcilePipelineTempTable(temp))
        .isEqualTo(com.smartfirehub.dataset.storage.DataTableStore.RebuildBackupState.NONE);
    assertThat(backupExists(temp)).isTrue();
    assertThat(tempColumns(temp)).containsExactly("code", "label");
    // 재실행(재사용 경로 — 맞춤이 먼저 불린다)도 그대로 성공한다.
    assertThat(runAndWait(pipelineId)).isEqualTo("COMPLETED");
    assertThat(rowCount(temp)).isEqualTo(2);
  }

  /**
   * 되돌리기는 재생성 때 낮아진 등급을 재생성 전 등급으로 되돌린다(WD-238) — 재생성 TEMP 는 「새 빈 TEMP」로 입력 등급에 정확히 맞춰져 낮아질 수 있는데,
   * 실패로 이전 행을 되살리면 그 행이 낮은 등급으로 노출된다. 러너 대신 서비스 진입점을 직접 불러 낮춤을 흉내 낸다.
   */
  @Test
  void restoreRebuild_raisesLoweredLevelBack() {
    Long pipelineId = createPipeline(sqlStep(STEP_NAME, "code, label", null, null));
    pipelinesToCleanup.add(pipelineId);
    assertThat(runAndWait(pipelineId)).isEqualTo("COMPLETED");
    Long temp = tempIdOf(pipelineId);
    Long low =
        inTenantFixture(
            () ->
                dsl.fetchOne("SELECT id FROM security_level ORDER BY rank ASC LIMIT 1")
                    .get(0, Long.class));
    Long high =
        inTenantFixture(
            () ->
                dsl.fetchOne("SELECT id FROM security_level ORDER BY rank DESC LIMIT 1")
                    .get(0, Long.class));
    assertThat(low).isNotEqualTo(high);
    setLevel(temp, high);

    DatasetService.PipelineTempRebuild rebuild =
        datasetService.rebuildPipelineTempTable(
            temp, List.of(new DatasetColumnRequest("x", "x", "TEXT", null, true, false, null)));
    assertThat(rebuild.previousLevelId()).isEqualTo(high);
    assertThat(backupExists(temp)).isTrue();
    assertThat(rowCount(temp)).as("새 테이블은 비어 있다").isZero();
    setLevel(temp, low); // 러너의 「새 TEMP 등급 = 입력 등급」 낮춤

    datasetService.restorePipelineTempTable(rebuild, userId);
    assertThat(levelOf(temp)).as("등급 복원").isEqualTo(high);
    assertThat(tempColumns(temp)).containsExactly("code", "label");
    assertThat(rowCount(temp)).isEqualTo(2);
    assertThat(backupExists(temp)).isFalse();
  }

  /**
   * 되돌리기는 이 실행이 옮긴 보관본(OID)일 때만 한다(WD-238) — 같은 TEMP 를 겹쳐 다시 만든 다른 실행이 보관본 이름에 자기 테이블을 두었으면, 되돌리기가
   * 그 테이블을 원래 이름으로 올리고 옛 열 메타를 써서 물리 열과 메타가 어긋난다. 보관본을 다른 테이블로 바꿔치기한 뒤 되돌리기가 거부되고 현재 테이블·메타가 그대로인지
   * 본다.
   */
  @Test
  void restoreRebuild_refusesBackupOfAnotherRun() {
    Long pipelineId = createPipeline(sqlStep(STEP_NAME, "code, label", null, null));
    pipelinesToCleanup.add(pipelineId);
    assertThat(runAndWait(pipelineId)).isEqualTo("COMPLETED");
    Long temp = tempIdOf(pipelineId);
    String table = tableNameOf(temp);

    DatasetService.PipelineTempRebuild rebuild =
        datasetService.rebuildPipelineTempTable(
            temp, List.of(new DatasetColumnRequest("x", "x", "TEXT", null, true, false, null)));
    // 다른 실행이 이 보관본을 지우고 자기 보관본을 둔 상황
    inTenantFixture(
        () ->
            dsl.execute(
                "DROP TABLE "
                    + DataSchema.qualify(DataTableService.rebuildBackupTableName(table))));
    plantBackup(table);

    assertThatThrownBy(() -> datasetService.restorePipelineTempTable(rebuild, userId))
        .hasMessageContaining("다른 실행의 것이라");
    assertThat(tempColumns(temp)).as("메타 그대로").containsExactly("x");
    assertThat(physicalColumns(temp)).as("현재 테이블 그대로").contains("x");
    assertThat(datasetService.discardPipelineTempBackup(rebuild)).as("남의 보관본은 버리지도 않는다").isFalse();
    assertThat(backupExists(temp)).isTrue();
  }

  private void updateStep(Long pipelineId, String name, PipelineStepRequest step) {
    pipelineService.updatePipeline(
        pipelineId, new UpdatePipelineRequest(name, null, null, List.of(step)), userId);
  }

  /** 컬럼 메타 행의 정체(id·이름·검색 대상) — 되돌리기가 행 그대로 되살렸는지 비교한다. */
  private List<String> columnIdentity(Long datasetId) {
    return inTenantFixture(
        () ->
            dsl.fetch(
                    "SELECT id || ':' || column_name || ':' || is_searchable FROM dataset_column"
                        + " WHERE dataset_id = ? ORDER BY column_order",
                    datasetId)
                .getValues(0, String.class));
  }

  private Long tableOid(Long datasetId) {
    String table = tableNameOf(datasetId);
    return inTenantFixture(
        () ->
            dsl.fetchOne("SELECT to_regclass(?)::oid::bigint", DataSchema.qualify(table))
                .get(0, Long.class));
  }

  private boolean backupExists(Long datasetId) {
    String backup = DataTableService.rebuildBackupTableName(tableNameOf(datasetId));
    return inTenantFixture(
        () ->
            Boolean.TRUE.equals(
                dsl.fetchValue("SELECT to_regclass(?) IS NOT NULL", DataSchema.qualify(backup))));
  }

  /** 테이블의 증분 인덱스(ix_<table>_upd)가 그 테이블에 붙어 있는지. */
  private boolean updatedAtIndexExists(Long datasetId) {
    String table = tableNameOf(datasetId);
    return inTenantFixture(
        () ->
            dsl.fetchCount(
                    dsl.selectOne()
                        .from("pg_indexes")
                        .where(
                            "schemaname = ? AND tablename = ? AND indexname = ?",
                            DataSchema.current(),
                            table,
                            "ix_" + table + "_upd"))
                > 0);
  }

  /** 서버가 도중에 멈춘 흔적처럼 보관본 이름의 테이블을 만든다. */
  private void plantBackup(String table) {
    inTenantFixture(
        () ->
            dsl.execute(
                "CREATE TABLE "
                    + DataSchema.qualify(DataTableService.rebuildBackupTableName(table))
                    + " (id BIGINT)"));
  }

  private void setLevel(Long datasetId, Long levelId) {
    inTenantFixture(
        () ->
            dsl.execute(
                "UPDATE dataset SET security_level_id = ? WHERE id = ?", levelId, datasetId));
  }

  private Long levelOf(Long datasetId) {
    return inTenantFixture(
        () ->
            dsl.fetchOne("SELECT security_level_id FROM dataset WHERE id = ?", datasetId)
                .get(0, Long.class));
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
    // WD-238: 성공한 재생성은 보관본을 남기지 않고, 새 테이블이 증분 인덱스를 갖는다.
    assertThat(backupExists(temp)).as("재생성 보관본 정리").isFalse();
    assertThat(updatedAtIndexExists(temp)).as("새 테이블의 _updated_at 인덱스").isTrue();
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
    return sqlStep(name, selectList, outputDatasetId, stepId, "REPLACE");
  }

  /** {@link #sqlStep(String, String, Long, Long)} 에 적재 전략을 지정한 버전(WD-238). */
  private PipelineStepRequest sqlStep(
      String name, String selectList, Long outputDatasetId, Long stepId, String loadStrategy) {
    return new PipelineStepRequest(
        name,
        null,
        "SQL",
        selectSql(selectList),
        outputDatasetId,
        List.of(srcDatasetId),
        null,
        loadStrategy,
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
