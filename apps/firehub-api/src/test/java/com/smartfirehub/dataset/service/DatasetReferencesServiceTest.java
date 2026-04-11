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
import com.smartfirehub.analytics.service.AnalyticsDashboardService;
import com.smartfirehub.analytics.service.ChartService;
import com.smartfirehub.analytics.service.SavedQueryService;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.dto.DatasetReferencesResponse;
import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.pipeline.dto.CreatePipelineRequest;
import com.smartfirehub.pipeline.dto.PipelineDetailResponse;
import com.smartfirehub.pipeline.dto.PipelineStepRequest;
import com.smartfirehub.pipeline.service.PipelineService;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link DatasetService#getReferences(Long)} 통합 테스트.
 *
 * <p>데이터셋을 참조하는 파이프라인/대시보드/Proactive Job 집계 동작을 검증한다. Proactive Job 은 현재 스키마상 datasetId 연결이 없으므로
 * 항상 빈 리스트를 반환하는 것이 정상이다.
 */
@Transactional
class DatasetReferencesServiceTest extends IntegrationTestBase {

  @Autowired private DatasetService datasetService;
  @Autowired private PipelineService pipelineService;
  @Autowired private SavedQueryService savedQueryService;
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

    // 참조 대상 데이터셋 + 무관한 비교 데이터셋 생성
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null));

    DatasetDetailResponse target =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Refs Target", "refs_target", null, null, "SOURCE", columns, null),
            testUserId);
    targetDatasetId = target.id();

    DatasetDetailResponse other =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Refs Other", "refs_other", null, null, "SOURCE", columns, null),
            testUserId);
    otherDatasetId = other.id();
  }

  @Test
  void getReferences_noReferences_returnsEmptyCounts() {
    DatasetReferencesResponse response = datasetService.getReferences(targetDatasetId);

    assertThat(response.datasetId()).isEqualTo(targetDatasetId);
    assertThat(response.pipelines()).isEmpty();
    assertThat(response.dashboards()).isEmpty();
    assertThat(response.proactiveJobs()).isEmpty();
    assertThat(response.totalCount()).isZero();
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
                    "step1",
                    "unrelated",
                    "SQL",
                    "SELECT 1",
                    otherDatasetId,
                    List.of(),
                    null))),
        testUserId);

    // When
    DatasetReferencesResponse response = datasetService.getReferences(targetDatasetId);

    // Then
    assertThat(response.pipelines())
        .extracting(DatasetReferencesResponse.ReferenceItem::id)
        .containsExactlyInAnyOrder(outPipeline.id(), inPipeline.id());
    assertThat(response.pipelines())
        .extracting(DatasetReferencesResponse.ReferenceItem::name)
        .containsExactlyInAnyOrder("Output Ref Pipeline", "Input Ref Pipeline");
    assertThat(response.dashboards()).isEmpty();
    assertThat(response.proactiveJobs()).isEmpty();
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

    DatasetReferencesResponse response = datasetService.getReferences(targetDatasetId);

    assertThat(response.pipelines()).hasSize(1);
    assertThat(response.pipelines().get(0).id()).isEqualTo(pipeline.id());
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
            new CreateChartRequest(
                "Refs Chart", null, savedQuery.id(), "BAR", Map.of(), false),
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
    DatasetReferencesResponse response = datasetService.getReferences(targetDatasetId);

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

  @Test
  void getReferences_withProactiveJobReference_returnsJobList() {
    // 현재 스키마상 proactive_job 은 datasetId 를 저장하지 않음. 따라서 어떤 job 을 생성해도 참조로 잡히지 않는다.
    // 이 테스트는 "proactive_job 집계는 언제나 빈 리스트" 라는 현재 계약을 고정한다.
    DatasetReferencesResponse response = datasetService.getReferences(targetDatasetId);

    assertThat(response.proactiveJobs()).isEmpty();
  }

  @Test
  void getReferences_nonexistentDataset_throws() {
    assertThatThrownBy(() -> datasetService.getReferences(999_999_999L))
        .isInstanceOf(DatasetNotFoundException.class);
  }
}
