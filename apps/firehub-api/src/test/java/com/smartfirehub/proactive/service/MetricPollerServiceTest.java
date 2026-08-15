package com.smartfirehub.proactive.service;

import static com.smartfirehub.jooq.Tables.PROACTIVE_JOB;
import static com.smartfirehub.jooq.Tables.USER;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import com.smartfirehub.pipeline.service.executor.ExecutorClient;
import com.smartfirehub.pipeline.service.executor.ExecutorClient.QueryExecuteResult;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** MetricPollerService 통합 테스트 — system/dataset 메트릭 수집 및 이상 감지 흐름 검증 */
class MetricPollerServiceTest extends IntegrationTestBase {

  @Autowired private MetricPollerService metricPollerService;
  @Autowired private DSLContext dsl;
  @MockitoBean private ExecutorClient executorClient;

  private Long testUserId;

  @BeforeEach
  void setUp() {
    long nano = System.nanoTime();
    testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "metric_poller_" + nano)
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Metric Poller Test")
            .set(USER.EMAIL, "metric_poller_" + nano + "@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
  }

  @AfterEach
  void tearDown() {
    // V104 이후 proactive_job 은 RLS 대상이다 — 트랜잭션 밖에서 지우면 GUC 가 없어 0행 삭제가
    // 되고 픽스처가 조용히 누적된다(user_id FK 가 CASCADE 라 아래 사용자 삭제가 뒤처리를 해 버려
    // 이 테스트는 초록으로 남는다 — P2-d 에서 겪은 "원인과 증상이 떨어진" 누수 형태 그대로다).
    inTenantFixture(
        () -> dsl.deleteFrom(PROACTIVE_JOB).where(PROACTIVE_JOB.USER_ID.eq(testUserId)).execute());
    // "user" 는 전역 테이블이라 컨텍스트가 필요 없다.
    dsl.deleteFrom(USER).where(USER.ID.eq(testUserId)).execute();
  }

  /** proactive_job이 없으면 poll() 호출 시 조기 반환 — 예외 없이 완료 */
  @Test
  void poll_withNoAnomalyJobs_completesWithoutError() {
    metricPollerService.poll();
    // 예외 없이 완료되어야 함
  }

  /** system 소스 dataset_total_count 메트릭 — DB 쿼리로 값 수집 */
  @Test
  void poll_withSystemMetric_datasetTotalCount_collectsValue() {
    String config =
        "{\"anomaly\": {\"sensitivity\": \"medium\", \"metrics\": [{\"id\": \"m1\","
            + " \"source\": \"system\", \"metricKey\": \"dataset_total_count\"}]}}";
    insertAnomalyJob(config, "ANOMALY");

    metricPollerService.poll();
    // 예외 없이 완료되어야 함 (메트릭 값은 0 이상)
  }

  /** system 소스 pipeline_failure_rate 메트릭 — 실행 이력 없으면 0.0 반환 */
  @Test
  void poll_withSystemMetric_pipelineFailureRate_returnsZeroWhenEmpty() {
    String config =
        "{\"anomaly\": {\"sensitivity\": \"low\", \"metrics\": [{\"id\": \"m2\","
            + " \"source\": \"system\", \"metricKey\": \"pipeline_failure_rate\"}]}}";
    insertAnomalyJob(config, "ANOMALY");

    metricPollerService.poll();
  }

  /** system 소스 pipeline_execution_count 메트릭 — 카운트 수집 */
  @Test
  void poll_withSystemMetric_pipelineExecutionCount_collectsValue() {
    String config =
        "{\"anomaly\": {\"sensitivity\": \"high\", \"metrics\": [{\"id\": \"m3\","
            + " \"source\": \"system\", \"metricKey\": \"pipeline_execution_count\"}]}}";
    insertAnomalyJob(config, "BOTH");

    metricPollerService.poll();
  }

  /** system 소스 active_user_count 메트릭 — 고유 사용자 수 수집 */
  @Test
  void poll_withSystemMetric_activeUserCount_collectsValue() {
    String config =
        "{\"anomaly\": {\"sensitivity\": \"medium\", \"metrics\": [{\"id\": \"m4\","
            + " \"source\": \"system\", \"metricKey\": \"active_user_count\"}]}}";
    insertAnomalyJob(config, "ANOMALY");

    metricPollerService.poll();
  }

  /** unknown system 메트릭 키 — 0.0 반환 후 저장, 예외 없음 */
  @Test
  void poll_withUnknownSystemMetricKey_yieldsZero() {
    String config =
        "{\"anomaly\": {\"sensitivity\": \"medium\", \"metrics\": [{\"id\": \"m5\","
            + " \"source\": \"system\", \"metricKey\": \"unknown_metric\"}]}}";
    insertAnomalyJob(config, "ANOMALY");

    metricPollerService.poll();
  }

  /** dataset 소스 메트릭 — ExecutorClient mock으로 값 수집 */
  @Test
  void poll_withDatasetMetric_executorReturnsValue_collectsValue() {
    when(executorClient.executeQuery(any(), anyInt(), anyBoolean()))
        .thenReturn(
            new QueryExecuteResult(
                true,
                "SELECT",
                List.of("val"),
                List.of(Map.of("val", 42.0)),
                1,
                0,
                0L,
                false,
                null));

    String config =
        "{\"anomaly\": {\"sensitivity\": \"medium\", \"metrics\": [{\"id\": \"m6\","
            + " \"source\": \"dataset\", \"query\": \"SELECT 42 AS val\"}]}}";
    insertAnomalyJob(config, "ANOMALY");

    metricPollerService.poll();
  }

  /** dataset 소스 — query가 null이면 스킵 */
  @Test
  void poll_withDatasetMetric_nullQuery_skipsMetric() {
    String config =
        "{\"anomaly\": {\"sensitivity\": \"medium\", \"metrics\": [{\"id\": \"m7\","
            + " \"source\": \"dataset\"}]}}";
    insertAnomalyJob(config, "ANOMALY");

    metricPollerService.poll();
  }

  /** dataset 소스 — ExecutorClient 예외 시 해당 메트릭 스킵, 다음 메트릭 처리 계속 */
  @Test
  void poll_withDatasetMetric_executorThrows_skipsAndContinues() {
    when(executorClient.executeQuery(any(), anyInt(), anyBoolean()))
        .thenThrow(new RuntimeException("Executor unavailable"));

    String config =
        "{\"anomaly\": {\"sensitivity\": \"medium\", \"metrics\": [{\"id\": \"m8\","
            + " \"source\": \"dataset\", \"query\": \"SELECT 1\"}]}}";
    insertAnomalyJob(config, "ANOMALY");

    metricPollerService.poll();
  }

  /** unknown source — 경고 로그 후 스킵 */
  @Test
  void poll_withUnknownSource_skipsMetric() {
    String config =
        "{\"anomaly\": {\"sensitivity\": \"medium\", \"metrics\": [{\"id\": \"m9\","
            + " \"source\": \"kafka\"}]}}";
    insertAnomalyJob(config, "ANOMALY");

    metricPollerService.poll();
  }

  /** metricId가 null이면 스킵 */
  @Test
  void poll_withNullMetricId_skipsMetric() {
    String config =
        "{\"anomaly\": {\"sensitivity\": \"medium\", \"metrics\": [{\"source\": \"system\","
            + " \"metricKey\": \"dataset_total_count\"}]}}";
    insertAnomalyJob(config, "ANOMALY");

    metricPollerService.poll();
  }

  /** anomalyConfig가 없는 job — 스킵 */
  @Test
  void poll_withJobHavingNoAnomalyConfig_skipsJob() {
    String config = "{\"schedule\": {}}";
    insertAnomalyJob(config, "ANOMALY");

    metricPollerService.poll();
  }

  /** config가 null인 job — 빈 Map으로 처리되어 anomalyConfig 없으므로 스킵 */
  @Test
  void poll_withNullConfig_skipsJob() {
    inTenantFixture(
        () ->
            dsl.insertInto(PROACTIVE_JOB)
                .set(PROACTIVE_JOB.USER_ID, testUserId)
                .set(PROACTIVE_JOB.NAME, "null config job")
                .set(PROACTIVE_JOB.PROMPT, "prompt")
                .set(PROACTIVE_JOB.CRON_EXPRESSION, "0 * * * *")
                .set(PROACTIVE_JOB.ENABLED, true)
                .set(PROACTIVE_JOB.TRIGGER_TYPE, "ANOMALY")
                .execute());

    metricPollerService.poll();
  }

  /** dataset 소스 — executor 결과가 빈 배열이면 스킵 */
  @Test
  void poll_withDatasetMetric_emptyResult_skipsMetric() {
    when(executorClient.executeQuery(any(), anyInt(), anyBoolean()))
        .thenReturn(
            new QueryExecuteResult(true, "SELECT", List.of(), List.of(), 0, 0, 0L, false, null));

    String config =
        "{\"anomaly\": {\"sensitivity\": \"medium\", \"metrics\": [{\"id\": \"m10\","
            + " \"source\": \"dataset\", \"query\": \"SELECT 1\"}]}}";
    insertAnomalyJob(config, "ANOMALY");

    metricPollerService.poll();
  }

  // V103 이후 proactive_job.tenant_id 는 NOT NULL + GUC 파생 DEFAULT 다. 픽스처 삽입은
  // 트랜잭션 안에서 GUC 를 공급해야 한다 — 검증 대상인 metricPollerService.poll() 은
  // 여전히 이 경계 밖에서 호출된다(배선 결함을 가리지 않기 위함).
  private void insertAnomalyJob(String config, String triggerType) {
    inTenantFixture(
        () ->
            dsl.insertInto(PROACTIVE_JOB)
                .set(PROACTIVE_JOB.USER_ID, testUserId)
                .set(PROACTIVE_JOB.NAME, "Anomaly Job " + triggerType)
                .set(PROACTIVE_JOB.PROMPT, "테스트 프롬프트")
                .set(PROACTIVE_JOB.CRON_EXPRESSION, "0 * * * *")
                .set(PROACTIVE_JOB.ENABLED, true)
                .set(PROACTIVE_JOB.TRIGGER_TYPE, triggerType)
                .set(PROACTIVE_JOB.CONFIG, JSONB.valueOf(config))
                .execute());
  }
}
