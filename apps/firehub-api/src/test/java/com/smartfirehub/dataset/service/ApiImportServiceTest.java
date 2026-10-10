package com.smartfirehub.dataset.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.ApiImportRequest;
import com.smartfirehub.dataset.dto.ApiImportResponse;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.pipeline.dto.PipelineDetailResponse;
import com.smartfirehub.pipeline.dto.PipelineStepResponse;
import com.smartfirehub.pipeline.dto.TriggerResponse;
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

@Transactional
class ApiImportServiceTest extends IntegrationTestBase {

  @Autowired private ApiImportService apiImportService;

  @Autowired private DatasetService datasetService;

  @Autowired private PipelineService pipelineService;

  @Autowired private TriggerService triggerService;

  @Autowired private DSLContext dsl;

  private Long testUserId;
  private Long testDatasetId;

  @BeforeEach
  void setUp() {
    testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "api_import_test_user")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "API Import Test User")
            .set(USER.EMAIL, "api_import_test@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
    // 보안 등급(코드리뷰 CR2): API_CALL 스텝 저장이 편집자의 지정 출력 VIEW 를 요구한다 — 실제 호출자(데이터셋 화면의 사용자)처럼 이
    // 테넌트 ACTIVE 멤버 + USER 역할(내부 열람)을 준다. 없으면 자격이 비어 거부된다(fail-closed).
    com.smartfirehub.support.TenantRlsTestSupport.insertActiveMembership(
        dsl, testUserId, DEFAULT_TEST_TENANT_ID);
    com.smartfirehub.support.TestUsers.grantRole(
        dsl, fixtureTransactionTemplate, testUserId, DEFAULT_TEST_TENANT_ID, "USER");

    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null));

    DatasetDetailResponse dataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "API Import Dataset",
                "api_import_dataset",
                "Test dataset for API import",
                null,
                "TABLE",
                "SOURCE",
                columns,
                null),
            testUserId);

    testDatasetId = dataset.id();
  }

  @Test
  void createApiImport_createsSuccessfully() {
    // Given
    Map<String, Object> apiConfig =
        Map.of(
            "url", "https://api.example.com/data",
            "method", "GET",
            "dataPath", "$.items");

    ApiImportRequest request =
        new ApiImportRequest(
            "My API Pipeline",
            "Fetches data from external API",
            apiConfig,
            null,
            "REPLACE",
            false,
            null);

    // When
    ApiImportResponse response =
        apiImportService.createApiImport(testDatasetId, request, testUserId);

    // Then
    assertThat(response.pipelineId()).isNotNull();
    assertThat(response.executionId()).isNull();
    assertThat(response.triggerId()).isNull();

    // Verify pipeline was created with correct name
    PipelineDetailResponse pipeline = pipelineService.getPipelineById(response.pipelineId());
    assertThat(pipeline.name()).isEqualTo("My API Pipeline");
    assertThat(pipeline.description()).isEqualTo("Fetches data from external API");

    // Verify step has correct type, output dataset, and apiConfig
    assertThat(pipeline.steps()).hasSize(1);
    PipelineStepResponse step = pipeline.steps().get(0);
    assertThat(step.scriptType()).isEqualTo("API_CALL");
    assertThat(step.outputDatasetId()).isEqualTo(testDatasetId);
    assertThat(step.apiConfig()).isNotNull();
    assertThat(step.apiConfig()).containsKey("url");
  }

  @Test
  void createApiImport_withSchedule_createsTrigger() {
    // Given
    Map<String, Object> apiConfig =
        Map.of(
            "url", "https://api.example.com/data",
            "method", "GET");

    ApiImportRequest.ScheduleConfig schedule =
        new ApiImportRequest.ScheduleConfig(
            "0 6 * * *", "Daily Morning Schedule", "Runs every day at 6 AM");

    ApiImportRequest request =
        new ApiImportRequest(
            "Scheduled API Pipeline", null, apiConfig, null, "REPLACE", false, schedule);

    // When
    ApiImportResponse response =
        apiImportService.createApiImport(testDatasetId, request, testUserId);

    // Then
    assertThat(response.pipelineId()).isNotNull();
    assertThat(response.triggerId()).isNotNull();
    assertThat(response.executionId()).isNull();

    // Verify trigger was created in DB
    Long triggerCount =
        dsl.selectCount()
            .from(PIPELINE_TRIGGER)
            .where(PIPELINE_TRIGGER.PIPELINE_ID.eq(response.pipelineId()))
            .fetchOne(0, Long.class);
    assertThat(triggerCount).isEqualTo(1);
  }

  @Test
  void createApiImport_withAutoName_usesDatasetIdNotName() {
    // Given - no pipelineName provided
    Map<String, Object> apiConfig =
        Map.of(
            "url", "https://api.example.com/data",
            "method", "GET");

    ApiImportRequest request =
        new ApiImportRequest(null, null, apiConfig, null, "APPEND", false, null);

    // When
    ApiImportResponse response =
        apiImportService.createApiImport(testDatasetId, request, testUserId);

    // Then
    PipelineDetailResponse pipeline = pipelineService.getPipelineById(response.pipelineId());
    // WD-31④: 기본 이름에 데이터셋 이름이 없다(파이프라인 목록은 데이터셋 등급 판정 대상이 아니다).
    assertThat(pipeline.name()).isEqualTo("API Import #" + testDatasetId);
    assertThat(pipeline.name()).doesNotContain("API Import Dataset");
  }

  /** 기본 트리거 이름에도 데이터셋 이름이 없다(WD-31④ — 파이프라인·트리거 목록에서 숨김 데이터셋 이름 노출). */
  @Test
  void createApiImport_withScheduleAndNoNames_triggerNameHasNoDatasetName() {
    Map<String, Object> apiConfig = Map.of("url", "https://api.example.com/data", "method", "GET");
    ApiImportRequest request =
        new ApiImportRequest(
            null,
            null,
            apiConfig,
            null,
            "APPEND",
            false,
            new ApiImportRequest.ScheduleConfig("0 6 * * *", null, null));

    ApiImportResponse response =
        apiImportService.createApiImport(testDatasetId, request, testUserId);

    TriggerResponse trigger = triggerService.getTriggers(response.pipelineId()).get(0);
    assertThat(trigger.name()).isEqualTo("API Import #" + testDatasetId + " Schedule");
    assertThat(trigger.name()).doesNotContain("API Import Dataset");
  }

  @Test
  void createApiImport_datasetNotFound_throws() {
    // Given
    Map<String, Object> apiConfig =
        Map.of(
            "url", "https://api.example.com/data",
            "method", "GET");

    ApiImportRequest request =
        new ApiImportRequest("Some Pipeline", null, apiConfig, null, "REPLACE", false, null);

    // When / Then
    assertThatThrownBy(() -> apiImportService.createApiImport(999999L, request, testUserId))
        .isInstanceOf(DatasetNotFoundException.class);
  }
}
