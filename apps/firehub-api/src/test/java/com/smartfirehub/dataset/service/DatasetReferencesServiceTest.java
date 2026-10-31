package com.smartfirehub.dataset.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.analytics.dto.AddWidgetRequest;
import com.smartfirehub.analytics.dto.CreateChartRequest;
import com.smartfirehub.analytics.dto.CreateDashboardRequest;
import com.smartfirehub.analytics.dto.CreateSavedQueryRequest;
import com.smartfirehub.analytics.dto.DashboardResponse;
import com.smartfirehub.analytics.dto.SavedQueryResponse;
import com.smartfirehub.analytics.repository.SavedQueryRepository;
import com.smartfirehub.analytics.service.AnalyticsDashboardService;
import com.smartfirehub.analytics.service.ChartService;
import com.smartfirehub.analytics.service.SavedQueryService;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.dto.DatasetReferencesResponse;
import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.pipeline.dto.CreatePipelineRequest;
import com.smartfirehub.pipeline.dto.CreateTriggerRequest;
import com.smartfirehub.pipeline.dto.PipelineDetailResponse;
import com.smartfirehub.pipeline.dto.PipelineStepRequest;
import com.smartfirehub.pipeline.dto.TriggerResponse;
import com.smartfirehub.pipeline.dto.TriggerType;
import com.smartfirehub.pipeline.service.PipelineService;
import com.smartfirehub.pipeline.service.TriggerService;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link DatasetService#getReferences(Long, Long)} 통합 테스트.
 *
 * <p>데이터셋을 참조하는 파이프라인/대시보드/Proactive Job 집계 동작을 검증한다. Proactive Job 은 현재 스키마상 datasetId 연결이 없으므로 항상
 * 빈 리스트를 반환하는 것이 정상이다.
 */
@Transactional
class DatasetReferencesServiceTest extends IntegrationTestBase {

  @Autowired private DatasetService datasetService;
  @Autowired private PipelineService pipelineService;
  @Autowired private TriggerService triggerService;
  @Autowired private SavedQueryService savedQueryService;
  @Autowired private SavedQueryRepository savedQueryRepository;
  @Autowired private ChartService chartService;
  @Autowired private AnalyticsDashboardService dashboardService;
  @Autowired private DSLContext dsl;

  private Long testUserId;
  private Long targetDatasetId;
  private Long otherDatasetId;

  @BeforeEach
  void setUp() {
    // 테스트용 유저 생성
    testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "refs_testuser")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Refs Test User")
            .set(USER.EMAIL, "refs_test@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
    // 보안 등급(Task 3 B2·D1): 파이프라인 출력·트리거 감시 대상으로 새로 지정하는 데이터셋은 저장자가 볼 수 있어야 한다 — 테스트
    // 사용자를 기본 테넌트 USER(기본=내부) 멤버로 만든다. 자격이 없으면 저장이 DATASET_SQL_ACCESS_DENIED 로 거부된다.
    com.smartfirehub.support.TenantRlsTestSupport.insertActiveMembership(
        dsl, testUserId, DEFAULT_TEST_TENANT_ID);
    dsl.execute(
        "insert into user_role (user_id, role_id) select ?, id from role where name = 'USER'",
        testUserId);

    // 참조 대상 데이터셋 + 무관한 비교 데이터셋 생성
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null));

    DatasetDetailResponse target =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Refs Target", "refs_target", null, null, "TABLE", "SOURCE", columns, null),
            testUserId);
    targetDatasetId = target.id();

    DatasetDetailResponse other =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Refs Other", "refs_other", null, null, "TABLE", "SOURCE", columns, null),
            testUserId);
    otherDatasetId = other.id();
  }

  @Test
  void getReferences_noReferences_returnsEmptyCounts() {
    DatasetReferencesResponse response = datasetService.getReferences(targetDatasetId, testUserId);

    assertThat(response.datasetId()).isEqualTo(targetDatasetId);
    assertThat(response.pipelines()).isEmpty();
    assertThat(response.mergeOutputPipelines()).isEmpty();
    assertThat(response.dashboards()).isEmpty();
    assertThat(response.proactiveJobs()).isEmpty();
    assertThat(response.triggers()).isEmpty();
    assertThat(response.totalCount()).isZero();
  }

  /**
   * WD-441: 기본 키를 해제하면 매 실행 실패하는 대상은 이 데이터셋을 「병합(MERGE) 적재 출력」으로 쓰는 파이프라인뿐이다. 같은 데이터셋을 REPLACE 로 쓰는
   * 파이프라인·입력으로만 읽는 파이프라인은 mergeOutputPipelines 에 넣지 않는다(pipelines 에는 들어간다).
   */
  @Test
  void getReferences_mergeOutputPipelines_listsOnlyMergeLoadsIntoTarget() {
    PipelineDetailResponse mergePipeline =
        pipelineService.createPipeline(
            new CreatePipelineRequest(
                "Merge Into Target",
                "merge",
                List.of(
                    new PipelineStepRequest(
                        "step1",
                        "merge",
                        "SQL",
                        "SELECT 1",
                        targetDatasetId,
                        List.of(otherDatasetId),
                        null))),
            testUserId);
    // 병합 저장은 출력 데이터셋에 기본 키가 있어야 통과하므로, 저장한 뒤 적재 방식만 MERGE 로 바꿔 상황을 만든다.
    dsl.execute(
        "update pipeline_step set load_strategy = 'MERGE' where pipeline_id = ?",
        mergePipeline.id());

    pipelineService.createPipeline(
        new CreatePipelineRequest(
            "Replace Into Target",
            "replace",
            List.of(
                new PipelineStepRequest(
                    "step1",
                    "replace",
                    "SQL",
                    "SELECT 1",
                    targetDatasetId,
                    List.of(otherDatasetId),
                    null))),
        testUserId);
    pipelineService.createPipeline(
        new CreatePipelineRequest(
            "Reads Target",
            "input only",
            List.of(
                new PipelineStepRequest(
                    "step1",
                    "read",
                    "SQL",
                    "SELECT 1",
                    otherDatasetId,
                    List.of(targetDatasetId),
                    null))),
        testUserId);

    DatasetReferencesResponse response = datasetService.getReferences(targetDatasetId, testUserId);

    assertThat(response.mergeOutputPipelines())
        .extracting(DatasetReferencesResponse.ReferenceItem::name)
        .containsExactly("Merge Into Target");
    // 부분집합이라 pipelines·totalCount 는 세 파이프라인을 그대로 센다.
    assertThat(response.pipelines()).hasSize(3);
    assertThat(response.totalCount()).isEqualTo(3);
  }

  @Test
  void getReferences_withPipelineReference_returnsPipelineList() {
    // Given: targetDatasetId 를 output 으로 쓰는 파이프라인 + input 으로 쓰는 파이프라인
    PipelineDetailResponse outPipeline =
        pipelineService.createPipeline(
            new CreatePipelineRequest(
                "Output Ref Pipeline",
                "uses target as output",
                List.of(
                    new PipelineStepRequest(
                        "step1",
                        "write target",
                        "SQL",
                        "SELECT 1",
                        targetDatasetId,
                        List.of(otherDatasetId),
                        null))),
            testUserId);

    PipelineDetailResponse inPipeline =
        pipelineService.createPipeline(
            new CreatePipelineRequest(
                "Input Ref Pipeline",
                "uses target as input",
                List.of(
                    new PipelineStepRequest(
                        "step1",
                        "read target",
                        "SQL",
                        "SELECT 1",
                        otherDatasetId,
                        List.of(targetDatasetId),
                        null))),
            testUserId);

    // 무관한 파이프라인 (targetDatasetId 를 참조하지 않음) — 결과에 포함되면 안 됨
    pipelineService.createPipeline(
        new CreatePipelineRequest(
            "Unrelated Pipeline",
            "does not reference target",
            List.of(
                new PipelineStepRequest(
                    "step1", "unrelated", "SQL", "SELECT 1", otherDatasetId, List.of(), null))),
        testUserId);

    // When
    DatasetReferencesResponse response = datasetService.getReferences(targetDatasetId, testUserId);

    // Then
    assertThat(response.pipelines())
        .extracting(DatasetReferencesResponse.ReferenceItem::id)
        .containsExactlyInAnyOrder(outPipeline.id(), inPipeline.id());
    assertThat(response.pipelines())
        .extracting(DatasetReferencesResponse.ReferenceItem::name)
        .containsExactlyInAnyOrder("Output Ref Pipeline", "Input Ref Pipeline");
    assertThat(response.dashboards()).isEmpty();
    assertThat(response.proactiveJobs()).isEmpty();
    assertThat(response.triggers()).isEmpty();
    assertThat(response.totalCount()).isEqualTo(2);
  }

  @Test
  void getReferences_withPipelineReference_deduplicatesWhenBothInputAndOutput() {
    // 같은 파이프라인이 output 과 input 양쪽에서 참조하면 한 번만 반환되어야 함
    PipelineDetailResponse pipeline =
        pipelineService.createPipeline(
            new CreatePipelineRequest(
                "Self Ref Pipeline",
                "uses target as both input and output",
                List.of(
                    new PipelineStepRequest(
                        "step1",
                        "output",
                        "SQL",
                        "SELECT 1",
                        targetDatasetId,
                        List.of(otherDatasetId),
                        null),
                    new PipelineStepRequest(
                        "step2",
                        "input",
                        "SQL",
                        "SELECT 1",
                        otherDatasetId,
                        List.of(targetDatasetId),
                        List.of("step1")))),
            testUserId);

    DatasetReferencesResponse response = datasetService.getReferences(targetDatasetId, testUserId);

    assertThat(response.pipelines()).hasSize(1);
    assertThat(response.pipelines().get(0).id()).isEqualTo(pipeline.id());
  }

  @Test
  void getReferences_withDatasetChangeTriggerReference_returnsTriggerList() {
    // Given: targetDatasetId 를 감시하는 DATASET_CHANGE 트리거를 가진 파이프라인. 이 참조는
    // pipeline_step 처럼 FK 컬럼이 아니라 pipeline_trigger.config JSONB 의 datasetIds 배열로만
    // 연결되므로, getReferences() 가 이를 놓치면 삭제 시 트리거가 고아로 남는다(#600).
    PipelineDetailResponse pipeline =
        pipelineService.createPipeline(
            new CreatePipelineRequest(
                "Trigger Ref Pipeline",
                "watched by a DATASET_CHANGE trigger",
                List.of(
                    new PipelineStepRequest(
                        "step1", "noop", "SQL", "SELECT 1", otherDatasetId, List.of(), null))),
            testUserId);

    TriggerResponse trigger =
        triggerService.createTrigger(
            pipeline.id(),
            new CreateTriggerRequest(
                "Refs Trigger",
                TriggerType.DATASET_CHANGE,
                "watches target dataset",
                Map.of("datasetIds", List.of(targetDatasetId))),
            testUserId);

    // 무관한 트리거: otherDatasetId 만 감시 — 결과에 포함되면 안 됨
    triggerService.createTrigger(
        pipeline.id(),
        new CreateTriggerRequest(
            "Unrelated Trigger",
            TriggerType.DATASET_CHANGE,
            "watches other dataset",
            Map.of("datasetIds", List.of(otherDatasetId))),
        testUserId);

    // When
    DatasetReferencesResponse response = datasetService.getReferences(targetDatasetId, testUserId);

    // Then
    assertThat(response.triggers())
        .extracting(DatasetReferencesResponse.TriggerReferenceItem::id)
        .containsExactly(trigger.id());
    assertThat(response.triggers())
        .extracting(DatasetReferencesResponse.TriggerReferenceItem::name)
        .containsExactly("Refs Trigger");
    assertThat(response.triggers())
        .extracting(DatasetReferencesResponse.TriggerReferenceItem::pipelineId)
        .containsExactly(pipeline.id());
    assertThat(response.triggers())
        .extracting(DatasetReferencesResponse.TriggerReferenceItem::pipelineName)
        .containsExactly("Trigger Ref Pipeline");
    assertThat(response.pipelines()).isEmpty();
    assertThat(response.dashboards()).isEmpty();
    assertThat(response.totalCount()).isEqualTo(1);
  }

  @Test
  void getReferences_withDashboardReference_returnsDashboardList() {
    // Given: target 데이터셋에 묶인 saved_query → chart → widget → dashboard
    SavedQueryResponse savedQuery =
        savedQueryService.create(
            new CreateSavedQueryRequest(
                "Refs SavedQuery", null, "SELECT 1", targetDatasetId, "test", false),
            testUserId);

    var chart =
        chartService.create(
            new CreateChartRequest("Refs Chart", null, savedQuery.id(), "BAR", Map.of(), false),
            testUserId);

    DashboardResponse dashboard =
        dashboardService.create(
            new CreateDashboardRequest("Refs Dashboard", "desc", false, null), testUserId);

    dashboardService.addWidget(
        dashboard.id(), new AddWidgetRequest(chart.id(), 0, 0, 6, 4), testUserId);

    // 무관한 대시보드 (다른 데이터셋에 묶인 saved_query)
    SavedQueryResponse otherSavedQuery =
        savedQueryService.create(
            new CreateSavedQueryRequest(
                "Other SavedQuery", null, "SELECT 1", otherDatasetId, "test", false),
            testUserId);
    var otherChart =
        chartService.create(
            new CreateChartRequest(
                "Other Chart", null, otherSavedQuery.id(), "BAR", Map.of(), false),
            testUserId);
    DashboardResponse otherDashboard =
        dashboardService.create(
            new CreateDashboardRequest("Other Dashboard", "desc", false, null), testUserId);
    dashboardService.addWidget(
        otherDashboard.id(), new AddWidgetRequest(otherChart.id(), 0, 0, 6, 4), testUserId);

    // When
    DatasetReferencesResponse response = datasetService.getReferences(targetDatasetId, testUserId);

    // Then
    assertThat(response.dashboards())
        .extracting(DatasetReferencesResponse.ReferenceItem::id)
        .containsExactly(dashboard.id());
    assertThat(response.dashboards())
        .extracting(DatasetReferencesResponse.ReferenceItem::name)
        .containsExactly("Refs Dashboard");
    assertThat(response.pipelines()).isEmpty();
    assertThat(response.proactiveJobs()).isEmpty();
    assertThat(response.totalCount()).isEqualTo(1);
  }

  // ── WD-143: FK 없이 SQL 본문만으로 쓰는 곳 ──────────────────────────────────────────────

  /** 저장 쿼리(SQL 본문·연결 데이터셋) → 차트 → 위젯 → 대시보드를 만들고 대시보드를 돌려준다(테스트 사용자 소유·비공개). */
  private DashboardResponse dashboardWithQuery(String dashboardName, String sql, Long datasetId) {
    return dashboardWithQueryBy(testUserId, false, dashboardName, sql, datasetId);
  }

  /** {@link #dashboardWithQuery} 의 소유자·공유 여부 지정판 — 대시보드 가시성(본인 소유 또는 공유) 검증용. */
  private DashboardResponse dashboardWithQueryBy(
      Long ownerId, boolean shared, String dashboardName, String sql, Long datasetId) {
    // 참조 탐지는 저장 시점 판정(WD-409)과 무관하다 — 판정이 막는 SQL(데이터셋이 아닌 표·다른 스키마)도 판정 이전에 저장된 행으로
    // 남아 있을 수 있으므로 리포지토리로 직접 넣는다.
    Long queryId =
        savedQueryRepository.insert(
            new CreateSavedQueryRequest(dashboardName + " Q", null, sql, datasetId, "test", false),
            ownerId);
    var chart =
        chartService.create(
            new CreateChartRequest(dashboardName + " C", null, queryId, "BAR", Map.of(), false),
            ownerId);
    DashboardResponse dashboard =
        dashboardService.create(
            new CreateDashboardRequest(dashboardName, "desc", shared, null), ownerId);
    dashboardService.addWidget(
        dashboard.id(), new AddWidgetRequest(chart.id(), 0, 0, 6, 4), ownerId);
    return dashboard;
  }

  /** 같은 테넌트의 다른 사용자 — setUp 의 테스트 사용자와 같은 멤버십·역할. */
  private Long createOtherMember(String username) {
    Long id =
        dsl.insertInto(USER)
            .set(USER.USERNAME, username)
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, username)
            .set(USER.EMAIL, username + "@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
    com.smartfirehub.support.TenantRlsTestSupport.insertActiveMembership(
        dsl, id, DEFAULT_TEST_TENANT_ID);
    dsl.execute(
        "insert into user_role (user_id, role_id) select ?, id from role where name = 'USER'", id);
    return id;
  }

  /**
   * 파이프라인 SQL 스텝은 데이터 스키마 한정 이름만 허용한다(SqlValidator) — 현재 테넌트 데이터 스키마로 인용 한정한 이름. 인용된 식별자도 PG 폴딩 뒤
   * 같은 테이블로 잡혀야 한다는 것을 함께 검증한다.
   */
  private static String qualified(String table) {
    return com.smartfirehub.global.tenant.DataSchema.qualify(table);
  }

  /** SQL 스텝 1개짜리 파이프라인 — 출력은 otherDatasetId, 선언 입력은 inputs. */
  private PipelineDetailResponse pipelineWithSql(
      String pipelineName, String sql, List<Long> inputs) {
    return pipelineService.createPipeline(
        new CreatePipelineRequest(
            pipelineName,
            "sql body reference",
            List.of(
                new PipelineStepRequest("step1", "s", "SQL", sql, otherDatasetId, inputs, null))),
        testUserId);
  }

  @Test
  void getReferences_dashboardBySqlTextOnly_withoutDatasetId_isFound() {
    // 화면 쿼리 편집기처럼 dataset_id 없이 SQL 본문만으로 target 을 읽는 저장 쿼리 → 차트 → 대시보드
    DashboardResponse dashboard =
        dashboardWithQuery(
            "Sql Only Dashboard", "SELECT col1, count(*) FROM refs_target GROUP BY col1", null);
    // 스키마 한정 표기도 같은 데이터셋(현재 테넌트 데이터 스키마)으로 본다
    DashboardResponse qualified =
        dashboardWithQuery(
            "Qualified Dashboard",
            "SELECT * FROM " + com.smartfirehub.global.tenant.DataSchema.current() + ".refs_target",
            null);
    // 끝 세미콜론 + 따옴표 없는 대문자 이름 — PG 폴딩으로 같은 테이블로 본다
    DashboardResponse semicolon =
        dashboardWithQuery("Semicolon Dashboard", "select col1 from REFS_TARGET;", null);
    // 같은 이름의 비재귀 CTE 정의 안쪽 참조는 실제 테이블이다(PG 스코프) — CTE 제외가 지나쳐 이 참조까지 버리면 안 된다
    DashboardResponse selfShadowCte =
        dashboardWithQuery(
            "Cte Shadow Dashboard",
            "WITH refs_target AS (SELECT col1 FROM refs_target) SELECT col1 FROM refs_target",
            null);

    DatasetReferencesResponse response = datasetService.getReferences(targetDatasetId, testUserId);

    assertThat(response.dashboards())
        .extracting(DatasetReferencesResponse.ReferenceItem::id)
        .containsExactly(dashboard.id(), qualified.id(), semicolon.id(), selfShadowCte.id());
    assertThat(response.dashboards())
        .extracting(DatasetReferencesResponse.ReferenceItem::name)
        .containsExactly(
            "Sql Only Dashboard",
            "Qualified Dashboard",
            "Semicolon Dashboard",
            "Cte Shadow Dashboard");
    assertThat(response.hiddenDashboardCount()).isZero();
    assertThat(response.totalCount()).isEqualTo(4);
  }

  @Test
  void getReferences_pipelineBySqlTextOnly_withoutDeclaredInput_isFound() {
    // 선언 입력(inputDatasetIds) 없이 SQL 본문으로만 target 을 읽는 SQL 스텝
    PipelineDetailResponse plain =
        pipelineWithSql(
            "Sql Only Pipeline", "SELECT col1 FROM " + qualified("refs_target"), List.of());
    // {{#N}} 스텝 참조가 든 스텝 — 저장 검증과 같은 치환을 거쳐야 파싱되고, 그래야 같은 본문의 target 참조가 잡힌다
    PipelineDetailResponse stepRef =
        pipelineService.createPipeline(
            new CreatePipelineRequest(
                "Step Ref Pipeline",
                "step reference + sql body",
                List.of(
                    new PipelineStepRequest(
                        "step1",
                        "s1",
                        "SQL",
                        "SELECT col1 FROM " + qualified("refs_other"),
                        otherDatasetId,
                        List.of(),
                        null),
                    new PipelineStepRequest(
                        "step2",
                        "s2",
                        "SQL",
                        "SELECT a.col1 FROM {{#1}} a JOIN "
                            + qualified("refs_target")
                            + " b ON a.col1 = b.col1",
                        otherDatasetId,
                        List.of(),
                        List.of("step1")))),
            testUserId);

    DatasetReferencesResponse response = datasetService.getReferences(targetDatasetId, testUserId);

    assertThat(response.pipelines())
        .extracting(DatasetReferencesResponse.ReferenceItem::id)
        .containsExactly(plain.id(), stepRef.id());
    assertThat(response.pipelines())
        .extracting(DatasetReferencesResponse.ReferenceItem::name)
        .containsExactly("Sql Only Pipeline", "Step Ref Pipeline");
  }

  @Test
  void getReferences_similarNamesLiteralsCommentsCteAndOtherSchema_areNotFound() {
    // 1차 거르기(ILIKE '%refs_target%')에는 모두 걸리지만 실제 테이블 참조가 아니다 — 파서 확정 단계가 걸러야 한다.
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null));
    datasetService.createDataset(
        new CreateDatasetRequest(
            "Refs Target 2024", "refs_target_2024", null, null, "TABLE", "SOURCE", columns, null),
        testUserId);
    // 이름이 더 긴 다른 테이블 + 문자열 리터럴 + 주석
    dashboardWithQuery(
        "Similar Name Dashboard",
        "SELECT 'refs_target' AS label, col1 FROM refs_target_2024 /* refs_target */ -- refs_target",
        null);
    // 같은 이름의 CTE — 실제 테이블이 아니다
    dashboardWithQuery(
        "Cte Dashboard",
        "WITH refs_target AS (SELECT 1 AS col1) SELECT col1 FROM refs_target",
        null);
    // 데이터 스키마가 아닌 다른 스키마의 같은 이름
    dashboardWithQuery("Other Schema Dashboard", "SELECT * FROM public.refs_target", null);
    // 파싱할 수 없는 SQL — 예외 없이 건너뛴다
    dashboardWithQuery("Broken Dashboard", "SELEC refs_target FROM", null);
    pipelineWithSql(
        "Similar Name Pipeline",
        "SELECT 'refs_target' AS label, col1 FROM "
            + qualified("refs_target_2024")
            + " -- refs_target",
        List.of());

    DatasetReferencesResponse response = datasetService.getReferences(targetDatasetId, testUserId);

    assertThat(response.dashboards()).isEmpty();
    assertThat(response.pipelines()).isEmpty();
    assertThat(response.totalCount()).isZero();
  }

  @Test
  void getReferences_dashboardsTheViewerCannotSee_areCountedWithoutNames() {
    // 남의 비공개 대시보드 — FK 경로 1개 + SQL 본문 경로 1개. 이름은 싣지 않고 개수만 센다(대시보드 목록과 같은 가시성 규칙).
    Long other = createOtherMember("refs_other_user");
    dashboardWithQueryBy(
        other, false, "Other Private Fk", "SELECT col1 FROM refs_target", targetDatasetId);
    dashboardWithQueryBy(other, false, "Other Private Sql", "SELECT col1 FROM refs_target", null);
    // 남의 공유 대시보드와 내 비공개 대시보드는 이름이 보인다
    DashboardResponse otherShared =
        dashboardWithQueryBy(other, true, "Other Shared", "SELECT col1 FROM refs_target", null);
    DashboardResponse mine = dashboardWithQuery("Mine", "SELECT col1 FROM refs_target", null);

    DatasetReferencesResponse response = datasetService.getReferences(targetDatasetId, testUserId);

    assertThat(response.dashboards())
        .extracting(DatasetReferencesResponse.ReferenceItem::id)
        .containsExactly(otherShared.id(), mine.id());
    assertThat(response.dashboards())
        .extracting(DatasetReferencesResponse.ReferenceItem::name)
        .doesNotContain("Other Private Fk", "Other Private Sql");
    assertThat(response.hiddenDashboardCount()).isEqualTo(2);
    // 삭제 영향은 가시성과 무관하다 — 볼 수 없는 대시보드도 총계에 든다
    assertThat(response.totalCount()).isEqualTo(4);

    // 소유자 본인에게는 자기 비공개 대시보드 이름이 보인다(가림이 조회자 기준인지)
    DatasetReferencesResponse asOwner = datasetService.getReferences(targetDatasetId, other);
    assertThat(asOwner.dashboards())
        .extracting(DatasetReferencesResponse.ReferenceItem::name)
        .containsExactly("Other Private Fk", "Other Private Sql", "Other Shared");
    assertThat(asOwner.hiddenDashboardCount()).isEqualTo(1);
  }

  @Test
  void getReferences_fkAndSqlTextBothMatch_listedOnce() {
    // dataset_id 로도, SQL 본문으로도 target 을 가리키는 저장 쿼리 → 대시보드는 한 번만
    DashboardResponse dashboard =
        dashboardWithQuery("Both Dashboard", "SELECT col1 FROM refs_target", targetDatasetId);
    // 선언 입력으로도, SQL 본문으로도 target 을 읽는 파이프라인 → 한 번만
    PipelineDetailResponse pipeline =
        pipelineWithSql(
            "Both Pipeline",
            "SELECT col1 FROM " + qualified("refs_target"),
            List.of(targetDatasetId));

    DatasetReferencesResponse response = datasetService.getReferences(targetDatasetId, testUserId);

    assertThat(response.dashboards())
        .extracting(DatasetReferencesResponse.ReferenceItem::id)
        .containsExactly(dashboard.id());
    assertThat(response.pipelines())
        .extracting(DatasetReferencesResponse.ReferenceItem::id)
        .containsExactly(pipeline.id());
    assertThat(response.totalCount()).isEqualTo(2);
  }

  @Test
  void getReferences_withProactiveJobReference_returnsJobList() {
    // 현재 스키마상 proactive_job 은 datasetId 를 저장하지 않음. 따라서 어떤 job 을 생성해도 참조로 잡히지 않는다.
    // 이 테스트는 "proactive_job 집계는 언제나 빈 리스트" 라는 현재 계약을 고정한다.
    DatasetReferencesResponse response = datasetService.getReferences(targetDatasetId, testUserId);

    assertThat(response.proactiveJobs()).isEmpty();
  }

  @Test
  void getReferences_nonexistentDataset_throws() {
    assertThatThrownBy(() -> datasetService.getReferences(999_999_999L, testUserId))
        .isInstanceOf(DatasetNotFoundException.class);
  }
}
