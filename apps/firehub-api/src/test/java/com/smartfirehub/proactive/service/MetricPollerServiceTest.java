package com.smartfirehub.proactive.service;

import static com.smartfirehub.jooq.Tables.PROACTIVE_JOB;
import static com.smartfirehub.jooq.Tables.USER;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.pipeline.service.executor.ExecutorClient;
import com.smartfirehub.proactive.repository.AnomalyEventRepository;
import com.smartfirehub.proactive.repository.MetricSnapshotRepository;
import com.smartfirehub.support.IntegrationTestBase;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * MetricPollerService의 메트릭 수집 로직을 검증하는 통합 테스트.
 *
 * <p>dataset 소스 메트릭의 경우 ExecutorClient.executeQuery() 를 통해 쿼리를 실행하고 결과를 snapshot 에 저장하는 흐름을 검증한다.
 * system 소스 메트릭의 경우 DB 조회 결과를 직접 수집하는 흐름을 검증한다.
 */
@Transactional
class MetricPollerServiceTest extends IntegrationTestBase {

  @Autowired private MetricPollerService metricPollerService;
  @Autowired private DSLContext dsl;

  // 스냅샷 저장 여부를 검증하기 위해 spy 등록 (실제 저장도 수행)
  @MockitoSpyBean private MetricSnapshotRepository snapshotRepository;

  // 데이터셋 쿼리 실행 클라이언트 — 외부 의존성이므로 mock으로 대체
  @MockitoBean private ExecutorClient executorClient;

  /** 각 테스트에서 사용할 proactive_job ID */
  private Long testJobId;
  private Long testUserId;

  /**
   * 각 테스트 전에 FK 제약을 충족하는 user → proactive_job 선행 데이터를 삽입한다.
   * ANOMALY trigger_type 과 dataset 소스 메트릭 설정을 config JSONB 에 포함한다.
   */
  @BeforeEach
  void setUp() {
    testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "poller_test_user")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Poller Test User")
            .set(USER.EMAIL, "poller_test@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();

    // dataset 소스 메트릭 설정을 담은 config JSONB 생성
    // pollingInterval=1 로 설정하여 쿨다운 없이 즉시 수집되도록 한다
    String configJson =
        """
        {
          "anomaly": {
            "sensitivity": "medium",
            "metrics": [
              {
                "id": "dataset_metric_1",
                "name": "데이터셋 메트릭",
                "source": "dataset",
                "query": "SELECT COUNT(*) FROM data.test_table",
                "pollingInterval": 1
              }
            ]
          }
        }
        """;

    testJobId =
        dsl.insertInto(PROACTIVE_JOB)
            .set(PROACTIVE_JOB.USER_ID, testUserId)
            .set(PROACTIVE_JOB.NAME, "Poller Test Job")
            .set(PROACTIVE_JOB.PROMPT, "Test prompt")
            .set(PROACTIVE_JOB.CRON_EXPRESSION, "0 9 * * *")
            .set(PROACTIVE_JOB.TIMEZONE, "Asia/Seoul")
            .set(PROACTIVE_JOB.ENABLED, true)
            .set(PROACTIVE_JOB.TRIGGER_TYPE, "ANOMALY")
            .set(PROACTIVE_JOB.CONFIG, JSONB.valueOf(configJson))
            .returning(PROACTIVE_JOB.ID)
            .fetchOne()
            .getId();
  }

  /**
   * dataset 소스 메트릭 수집 시 ExecutorClient.executeQuery() 가 호출되고 snapshot 이 저장되는지 검증한다.
   *
   * <p>ExecutorClient 가 숫자 1개를 담은 결과를 반환하면 MetricSnapshotRepository.save() 가 1회 호출되어야 한다.
   */
  @Test
  void poll_dataset_source_metric_executes_query_and_saves_snapshot() {
    // Given: executorClient 가 COUNT(*) = 42 를 반환하도록 stub
    var mockResult =
        new ExecutorClient.QueryExecuteResult(
            true,
            "SELECT",
            List.of("count"),
            List.of(Map.of("count", 42)),
            1,
            0,
            10L,
            false,
            null);
    when(executorClient.executeQuery(anyString(), anyInt(), anyBoolean())).thenReturn(mockResult);

    // When: poll() 호출 → 내부에서 pollMetrics() → processMetric() 실행
    metricPollerService.poll();

    // Then 1: executorClient.executeQuery 가 dataset 쿼리로 호출되어야 한다
    verify(executorClient).executeQuery(eq("SELECT COUNT(*) FROM data.test_table"), eq(1), eq(true));

    // Then 2: 수집된 값(42.0)이 snapshot 으로 저장되어야 한다
    verify(snapshotRepository)
        .save(
            eq(testJobId),
            eq("dataset_metric_1"),
            eq(42.0),
            any(LocalDateTime.class));
  }

  /**
   * dataset 소스 메트릭의 query 가 빈 문자열이면 ExecutorClient 호출 없이 건너뛰는지 검증한다.
   *
   * <p>query 필드가 없거나 빈 경우에는 잘못된 설정으로 간주하여 스냅샷 저장을 하지 않아야 한다.
   */
  @Test
  void poll_dataset_source_metric_skips_when_query_is_blank() {
    // Given: query 가 없는 dataset 메트릭으로 job config 교체
    String configWithoutQuery =
        """
        {
          "anomaly": {
            "sensitivity": "medium",
            "metrics": [
              {
                "id": "bad_metric",
                "name": "쿼리 없는 메트릭",
                "source": "dataset",
                "pollingInterval": 1
              }
            ]
          }
        }
        """;
    dsl.update(PROACTIVE_JOB)
        .set(PROACTIVE_JOB.CONFIG, JSONB.valueOf(configWithoutQuery))
        .where(PROACTIVE_JOB.ID.eq(testJobId))
        .execute();

    // When
    metricPollerService.poll();

    // Then: query 가 없으면 executorClient 도, snapshotRepository.save() 도 호출되지 않아야 한다
    verify(executorClient, never()).executeQuery(anyString(), anyInt(), anyBoolean());
    verify(snapshotRepository, never()).save(any(), anyString(), any(Double.class), any());
  }

  /**
   * executorClient 가 빈 결과를 반환하면 스냅샷을 저장하지 않고 건너뛰는지 검증한다.
   *
   * <p>데이터가 없는 경우에는 anomaly 탐지도 불필요하므로 저장하지 않는 것이 올바른 동작이다.
   */
  @Test
  void poll_dataset_source_metric_skips_snapshot_when_query_returns_no_rows() {
    // Given: executorClient 가 빈 rows 를 반환하도록 stub
    var emptyResult =
        new ExecutorClient.QueryExecuteResult(
            true, "SELECT", List.of("count"), List.of(), 0, 0, 5L, false, null);
    when(executorClient.executeQuery(anyString(), anyInt(), anyBoolean())).thenReturn(emptyResult);

    // When
    metricPollerService.poll();

    // Then: 스냅샷 저장은 호출되지 않아야 한다
    verify(snapshotRepository, never()).save(any(), anyString(), any(Double.class), any());
  }
}
