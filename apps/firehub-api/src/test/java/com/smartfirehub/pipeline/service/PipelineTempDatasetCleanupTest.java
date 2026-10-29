package com.smartfirehub.pipeline.service;

import static com.smartfirehub.jooq.Tables.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.pipeline.dto.CreatePipelineRequest;
import com.smartfirehub.pipeline.dto.PipelineDetailResponse;
import com.smartfirehub.pipeline.dto.PipelineStepRequest;
import com.smartfirehub.pipeline.dto.UpdatePipelineRequest;
import com.smartfirehub.pipeline.exception.PipelineNotFoundException;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * 스텝이 만든 자동 생성 임시 데이터셋(TEMP)의 정리(WD-170) — 파이프라인 삭제·스텝 제거 저장 때 그 스텝의 TEMP 를 메타데이터와 물리 테이블까지 함께
 * 지우는지, 다른 파이프라인 스텝이 출력으로 쓰는 TEMP 는 남기는지, 삭제 영향 조회 개수가 실제 삭제와 같은지 본다.
 *
 * <p>TEMP 는 러너와 같은 진입점({@link TempDatasetService#createTempDataset})으로 만든다 — 실제 실행까지 관통하는 경우(같은 이름
 * 재생성 후 실행 성공)는 {@link PipelineTempDatasetRecreateIntegrationTest} 가 맡는다. 물리 테이블 존재는 {@code
 * to_regclass} 로 삭제 전·후를 모두 단언해, 처음부터 테이블이 없어서 통과하는 공허한 테스트가 되지 않게 한다. 테스트 트랜잭션 롤백으로 정리된다(PG DDL 도
 * 트랜잭션 안).
 */
@Transactional
class PipelineTempDatasetCleanupTest extends IntegrationTestBase {

  @Autowired private PipelineService pipelineService;
  @Autowired private DatasetService datasetService;
  @Autowired private TempDatasetService tempDatasetService;
  @Autowired private DSLContext dsl;

  private Long userId;

  @BeforeEach
  void setUp() {
    userId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "wd170user")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "WD170 User")
            .set(USER.EMAIL, "wd170@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
    // SQL 스텝 저장은 편집자 자격으로 판정된다 — ACTIVE 멤버십 + USER 역할(기본 '내부' 자격)이 있어야 기본 등급 데이터셋을 참조할 수 있다.
    TenantRlsTestSupport.insertActiveMembership(dsl, userId, DEFAULT_TEST_TENANT_ID);
    dsl.execute(
        "insert into user_role (user_id, role_id) select ?, id from role where name = 'USER'",
        userId);
    // 스텝 SQL 이 참조하는 원천 테이블 — 데이터셋에 매핑되지 않은 테이블은 저장 판정이 거부한다.
    datasetService.createDataset(
        new CreateDatasetRequest(
            "wd170 src",
            "wd170_src",
            null,
            null,
            "TABLE",
            "SOURCE",
            List.of(new DatasetColumnRequest("code", "Code", "TEXT", null, true, false, null)),
            null),
        userId);
  }

  // ------------------------------------------------------------------ //
  // (1) 파이프라인 삭제 → TEMP 메타·물리 테이블 삭제
  // ------------------------------------------------------------------ //

  @Test
  void deletePipeline_deletesAutoTempMetadataAndPhysicalTable() {
    PipelineDetailResponse p = createPipeline("wd170 삭제", "추출");
    Long stepId = p.steps().get(0).id();
    Long tempId = createTemp(p, stepId, "추출");
    String table = tableNameOf(tempId);
    assertThat(tableExists(table)).as("삭제 전 물리 테이블").isTrue();

    pipelineService.deletePipelineUnchecked(p.id());

    assertThat(datasetExists(tempId)).as("TEMP 메타데이터").isFalse();
    assertThat(columnCount(tempId)).as("TEMP 컬럼 메타").isZero();
    assertThat(tableExists(table)).as("TEMP 물리 테이블").isFalse();
  }

  @Test
  void deletePipeline_tempRoundTrippedAsOwnStepOutput_isStillDeleted() {
    // 웹 편집기는 해석된 출력(TEMP 폴백)을 되돌려 저장한다 — 스텝 자신이 TEMP 를 output_dataset_id 로 들고 있어도 "다른 스텝의 출력 참조"가
    // 아니다.
    PipelineDetailResponse p = createPipeline("wd170 왕복", "추출");
    Long stepId = p.steps().get(0).id();
    Long tempId = createTemp(p, stepId, "추출");
    pipelineService.updatePipeline(
        p.id(),
        new UpdatePipelineRequest(p.name(), null, null, List.of(sqlStep("추출", tempId))),
        userId);
    // WD-222: 저장은 자기 TEMP id 를 출력 미지정(null)으로 정규화한다 — TEMP 를 명시 출력으로 고정하지 않는다.
    assertThat(outputOf(stepId)).as("왕복 저장된 출력(정규화)").isNull();
    // 정규화 이전에 이미 TEMP id 로 고정된 기존 스텝(운영 데이터)도 같은 결과여야 한다 — DB 를 직접 고정해 재현한다.
    dsl.execute("UPDATE pipeline_step SET output_dataset_id = ? WHERE id = ?", tempId, stepId);
    assertThat(outputOf(stepId)).as("고정된 출력").isEqualTo(tempId);
    String table = tableNameOf(tempId);

    pipelineService.deletePipelineUnchecked(p.id());

    assertThat(datasetExists(tempId)).isFalse();
    assertThat(tableExists(table)).isFalse();
  }

  // ------------------------------------------------------------------ //
  // (2) 스텝 제거 저장 → 그 스텝 TEMP 삭제, 남은 스텝 TEMP 유지
  // ------------------------------------------------------------------ //

  @Test
  void updatePipeline_removedStepTempDeleted_keptStepTempRemains() {
    PipelineDetailResponse p =
        createPipeline("wd170 스텝제거", List.of(sqlStep("빼는스텝", null), sqlStep("남는스텝", null)));
    Long removedStepId = stepIdByName(p, "빼는스텝");
    Long keptStepId = stepIdByName(p, "남는스텝");
    Long removedTemp = createTemp(p, removedStepId, "빼는스텝");
    Long keptTemp = createTemp(p, keptStepId, "남는스텝");
    String removedTable = tableNameOf(removedTemp);
    String keptTable = tableNameOf(keptTemp);
    assertThat(tableExists(removedTable)).isTrue();

    pipelineService.updatePipeline(
        p.id(),
        new UpdatePipelineRequest(p.name(), null, null, List.of(sqlStep("남는스텝", null))),
        userId);

    assertThat(datasetExists(removedTemp)).as("뺀 스텝 TEMP 메타").isFalse();
    assertThat(tableExists(removedTable)).as("뺀 스텝 TEMP 물리 테이블").isFalse();
    assertThat(datasetExists(keptTemp)).as("남은 스텝 TEMP 메타").isTrue();
    assertThat(tableExists(keptTable)).as("남은 스텝 TEMP 물리 테이블").isTrue();
    assertThat(sourceStepOf(keptTemp)).as("남은 스텝 TEMP 연결").isEqualTo(keptStepId);
  }

  @Test
  void updatePipeline_removedStepTempTakenOverAsOutputInSameSave_isKept() {
    // 같은 저장에서 남는 스텝이 뺀 스텝의 TEMP 를 출력으로 넘겨받으면(왕복 보존 출력) 지우지 않는다 — 저장 후 상태로 출력 참조를 본다.
    PipelineDetailResponse p =
        createPipeline("wd170 넘겨받기", List.of(sqlStep("빼는스텝", null), sqlStep("남는스텝", null)));
    Long removedStepId = stepIdByName(p, "빼는스텝");
    Long removedTemp = createTemp(p, removedStepId, "빼는스텝");
    pipelineService.updatePipeline(
        p.id(),
        new UpdatePipelineRequest(
            p.name(), null, null, List.of(sqlStep("빼는스텝", removedTemp), sqlStep("남는스텝", null))),
        userId);

    pipelineService.updatePipeline(
        p.id(),
        new UpdatePipelineRequest(p.name(), null, null, List.of(sqlStep("남는스텝", removedTemp))),
        userId);

    assertThat(datasetExists(removedTemp)).isTrue();
    assertThat(tableExists(tableNameOf(removedTemp))).isTrue();
  }

  // ------------------------------------------------------------------ //
  // (3) 다른 파이프라인 스텝이 출력으로 쓰는 TEMP 는 유지
  // ------------------------------------------------------------------ //

  @Test
  void deletePipeline_tempUsedAsOutputByOtherPipeline_isKept() {
    PipelineDetailResponse a = createPipeline("wd170 원래", "추출");
    Long tempId = createTemp(a, a.steps().get(0).id(), "추출");
    // 다른 파이프라인 스텝이 그 TEMP 를 출력으로 지정한 상태. WD-224 이후 저장 검증은 이를 거부하므로, 그 전에 저장돼 운영에 남아 있는
    // 스텝(레거시 데이터)을 DB 를 직접 고쳐 재현한다 — 삭제가 그 출력을 지키는 안전장치는 레거시 데이터 때문에 여전히 필요하다.
    PipelineDetailResponse b =
        pipelineService.createPipeline(
            new CreatePipelineRequest("wd170 남의출력", null, List.of(sqlStep("쓰기", null))), userId);
    dsl.execute(
        "UPDATE pipeline_step SET output_dataset_id = ? WHERE id = ?",
        tempId,
        b.steps().get(0).id());
    assertThat(outputOf(b.steps().get(0).id())).isEqualTo(tempId);
    assertThat(pipelineService.getDeleteImpact(a.id(), userId).tempDatasetCount())
        .as("보호되는 TEMP 는 영향 개수에서 빠진다")
        .isZero();
    String table = tableNameOf(tempId);

    pipelineService.deletePipelineUnchecked(a.id());

    assertThat(datasetExists(tempId)).as("다른 파이프라인 출력 TEMP 메타").isTrue();
    assertThat(tableExists(table)).as("다른 파이프라인 출력 TEMP 물리 테이블").isTrue();
    assertThat(sourceStepOf(tempId)).as("연결만 끊긴다(SET NULL)").isNull();
  }

  @Test
  void deletePipeline_tempUsedOnlyAsInputByOtherPipeline_isDeleted() {
    // 입력으로만 쓰는 다른 스텝은 막지 않는다 — DatasetService.deleteDataset 과 같은 규칙(pipeline_step_input 은 ON DELETE
    // CASCADE).
    PipelineDetailResponse a = createPipeline("wd170 입력원", "추출");
    Long tempId = createTemp(a, a.steps().get(0).id(), "추출");
    PipelineDetailResponse b =
        pipelineService.createPipeline(
            new CreatePipelineRequest(
                "wd170 입력쓰기",
                null,
                List.of(
                    new PipelineStepRequest(
                        "읽기",
                        null,
                        "SQL",
                        "SELECT code FROM data.wd170_src",
                        null,
                        List.of(tempId),
                        null))),
            userId);
    Long readerStepId = b.steps().get(0).id();
    assertThat(inputCount(readerStepId, tempId)).isEqualTo(1);

    pipelineService.deletePipelineUnchecked(a.id());

    assertThat(datasetExists(tempId)).isFalse();
    assertThat(inputCount(readerStepId, tempId)).as("입력 연결은 CASCADE 로 함께 사라진다").isZero();
  }

  // ------------------------------------------------------------------ //
  // (5) 삭제 영향 조회 개수
  // ------------------------------------------------------------------ //

  @Test
  void getDeleteImpact_countsTempsThatWouldBeDeleted() {
    PipelineDetailResponse p =
        createPipeline("wd170 영향", List.of(sqlStep("하나", null), sqlStep("둘", null)));
    assertThat(pipelineService.getDeleteImpact(p.id(), userId).tempDatasetCount())
        .as("TEMP 없음")
        .isZero();

    createTemp(p, stepIdByName(p, "하나"), "하나");
    createTemp(p, stepIdByName(p, "둘"), "둘");

    assertThat(pipelineService.getDeleteImpact(p.id(), userId).tempDatasetCount()).isEqualTo(2);
  }

  @Test
  void getDeleteImpact_unknownPipeline_throwsNotFound() {
    assertThatThrownBy(() -> pipelineService.getDeleteImpact(-1L, userId))
        .isInstanceOf(PipelineNotFoundException.class);
  }

  // ------------------------------------------------------------------ //
  // helpers
  // ------------------------------------------------------------------ //

  private PipelineDetailResponse createPipeline(String name, String stepName) {
    return createPipeline(name, List.of(sqlStep(stepName, null)));
  }

  private PipelineDetailResponse createPipeline(String name, List<PipelineStepRequest> steps) {
    return pipelineService.createPipeline(new CreatePipelineRequest(name, null, steps), userId);
  }

  private PipelineStepRequest sqlStep(String name, Long outputDatasetId) {
    return new PipelineStepRequest(
        name, null, "SQL", "SELECT code FROM data.wd170_src", outputDatasetId, null, null);
  }

  /** 러너와 같은 진입점으로 스텝의 TEMP 를 만든다. */
  private Long createTemp(PipelineDetailResponse p, Long stepId, String stepName) {
    return tempDatasetService.createTempDataset(
        List.of(new ColumnInfo("code", "TEXT")), p.id(), p.name(), stepId, stepName, userId);
  }

  private static Long stepIdByName(PipelineDetailResponse p, String name) {
    return p.steps().stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow().id();
  }

  private String tableNameOf(Long datasetId) {
    return dsl.fetchValue("SELECT table_name FROM dataset WHERE id = ?", datasetId).toString();
  }

  private boolean tableExists(String table) {
    return dsl.fetchValue("SELECT to_regclass(?) IS NOT NULL", DataSchema.qualify(table))
        .equals(Boolean.TRUE);
  }

  private boolean datasetExists(Long datasetId) {
    return dsl.fetchCount(dsl.selectOne().from("dataset").where("id = ?", datasetId)) > 0;
  }

  private int columnCount(Long datasetId) {
    return dsl.fetchCount(
        dsl.selectOne().from("dataset_column").where("dataset_id = ?", datasetId));
  }

  private Long sourceStepOf(Long datasetId) {
    return dsl.fetchOne("SELECT source_pipeline_step_id FROM dataset WHERE id = ?", datasetId)
        .get(0, Long.class);
  }

  private Long outputOf(Long stepId) {
    return dsl.fetchOne("SELECT output_dataset_id FROM pipeline_step WHERE id = ?", stepId)
        .get(0, Long.class);
  }

  private int inputCount(Long stepId, Long datasetId) {
    return dsl.fetchCount(
        dsl.selectOne()
            .from("pipeline_step_input")
            .where("step_id = ? AND dataset_id = ?", stepId, datasetId));
  }
}
