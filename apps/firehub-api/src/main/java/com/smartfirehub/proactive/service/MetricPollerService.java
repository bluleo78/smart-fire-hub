package com.smartfirehub.proactive.service;

import com.smartfirehub.proactive.util.ProactiveTime;
import static com.smartfirehub.jooq.Tables.DATASET;
import static com.smartfirehub.jooq.Tables.PIPELINE_EXECUTION;
import static com.smartfirehub.jooq.Tables.PROACTIVE_JOB;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataset.exception.SqlQueryException;
import com.smartfirehub.global.util.SqlValidationUtils;
import com.smartfirehub.pipeline.exception.UnsafeSqlException;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.proactive.dto.AnomalyEvent;
import com.smartfirehub.proactive.repository.MetricSnapshotRepository;
import com.smartfirehub.proactive.repository.MetricSnapshotRepository.MetricSnapshot;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Slf4j
@RequiredArgsConstructor
public class MetricPollerService {

  private final DSLContext dsl;
  private final MetricSnapshotRepository snapshotRepository;
  private final AnomalyDetector anomalyDetector;
  private final ApplicationEventPublisher eventPublisher;
  private final ObjectMapper objectMapper;
  private final TenantScopedRunner tenantScopedRunner;
  // dsl 직접 경로에 GUC 를 주입하기 위한 트랜잭션 경계 — 순회만으로는 GUC 가 비어 있다.
  private final TransactionTemplate transactionTemplate;
  // 데이터셋 메트릭 수집을 위한 SQL 실행 클라이언트
  private final com.smartfirehub.pipeline.service.executor.ExecutorClient executorClient;

  /**
   * 데이터셋 메트릭 SQL 검증기 — 애드혹 분석 쿼리({@code AnalyticsQueryExecutionService})와 같은 정책(현재 테넌트
   * 데이터 스키마만, 미한정 이름 허용)이다. 메트릭 SQL 은 사용자가 애드혹 쿼리처럼 직접 쓰는 SELECT 이므로 같은
   * 문을 통과해야 한다. 스프링 빈(파이프라인 정책)이 아니라 팩터리 인스턴스를 쓰는 이유는 그 팩터리 주석 참조.
   */
  private final SqlValidator metricSqlValidator = SqlValidator.forAdhocDataSchemaQueries();

  // Track last poll time per job+metric to respect pollingInterval
  private final Map<String, LocalDateTime> lastPollTime = new ConcurrentHashMap<>();

  private static final int HISTORY_DAYS = 30;

  /**
   * 이상탐지 메트릭 폴링.
   *
   * <p>원 HTTP 요청이 없어 승계할 테넌트가 없다 — ACTIVE 테넌트를 순회해 테넌트별로 돈다.
   * 순회하지 않으면 RLS 가 proactive_job·dataset·pipeline_execution 을 전부 차단해 이상탐지가
   * 예외도 로그도 없이 무동작이 된다.
   */
  @Scheduled(fixedDelay = 30000)
  public void poll() {
    tenantScopedRunner.forEachActiveTenant(
        tenantId -> {
          try {
            pollMetrics();
          } catch (Exception e) {
            log.error("MetricPollerService: polling failed (tenant={})", tenantId, e);
          }
        });
  }

  /**
   * 한 테넌트 범위의 메트릭 폴링 본문.
   *
   * <p><b>트랜잭션 경계(P2-b)</b>: 이 경로는 리포지토리를 거치지 않고 {@code DSLContext} 를 직접 쓴다 —
   * {@link TenantScopedRunner} 는 ThreadLocal 만 세우므로 GUC(app.tenant_id)는 여기서 트랜잭션을 열어야
   * 주입된다. 다만 트랜잭션은 <b>DB 를 만지는 구간에만</b> 둔다: 데이터셋 메트릭은 {@code
   * executorClient} HTTP 호출로 값을 수집하고 이상탐지 이벤트도 발행하므로, 전체를 한 트랜잭션으로
   * 감싸면 외부 호출 동안 커넥션을 점유해 풀이 고갈된다.
   */
  @SuppressWarnings("unchecked")
  private void pollMetrics() {
    // 1. Query enabled proactive jobs with anomaly trigger type — DB 구간이므로 트랜잭션 안에서 읽는다.
    var jobs =
        transactionTemplate.execute(
            status ->
                dsl.select(
                        PROACTIVE_JOB.ID,
                        PROACTIVE_JOB.USER_ID,
                        PROACTIVE_JOB.CONFIG,
                        PROACTIVE_JOB.TRIGGER_TYPE)
                    .from(PROACTIVE_JOB)
                    .where(
                        PROACTIVE_JOB
                            .ENABLED
                            .isTrue()
                            .and(PROACTIVE_JOB.TRIGGER_TYPE.in("ANOMALY", "BOTH")))
                    .fetch());

    if (jobs == null || jobs.isEmpty()) {
      return;
    }

    log.debug("MetricPollerService: found {} anomaly-enabled jobs", jobs.size());

    for (var job : jobs) {
      Long jobId = job.get(PROACTIVE_JOB.ID);
      Long userId = job.get(PROACTIVE_JOB.USER_ID);
      JSONB configJsonb = job.get(PROACTIVE_JOB.CONFIG);

      try {
        Map<String, Object> config =
            configJsonb != null
                ? objectMapper.readValue(configJsonb.data(), new TypeReference<>() {})
                : Map.of();

        // Extract anomaly config from the config JSONB
        Map<String, Object> anomalyConfig =
            config.containsKey("anomaly") ? (Map<String, Object>) config.get("anomaly") : null;

        if (anomalyConfig == null) {
          continue;
        }

        String sensitivity =
            anomalyConfig.containsKey("sensitivity")
                ? (String) anomalyConfig.get("sensitivity")
                : "medium";

        List<Map<String, Object>> metrics =
            anomalyConfig.containsKey("metrics")
                ? (List<Map<String, Object>>) anomalyConfig.get("metrics")
                : List.of();

        for (Map<String, Object> metric : metrics) {
          processMetric(jobId, userId, metric, sensitivity);
        }
      } catch (Exception e) {
        log.error("MetricPollerService: failed to process job {}", jobId, e);
      }
    }
  }

  private void processMetric(
      Long jobId, Long userId, Map<String, Object> metric, String sensitivity) {
    String metricId = (String) metric.get("id");
    String metricName = (String) metric.getOrDefault("name", metricId);
    String source = (String) metric.getOrDefault("source", "system");
    int pollingIntervalSeconds =
        metric.containsKey("pollingInterval")
            ? ((Number) metric.get("pollingInterval")).intValue()
            : 300; // default 5 minutes

    if (metricId == null) {
      return;
    }

    // Check if pollingInterval has elapsed since last collection
    String pollKey = jobId + ":" + metricId;
    LocalDateTime lastPoll = lastPollTime.get(pollKey);
    LocalDateTime now = ProactiveTime.nowUtc();

    if (lastPoll != null && lastPoll.plusSeconds(pollingIntervalSeconds).isAfter(now)) {
      return; // Not yet time to poll
    }

    double value;
    if ("system".equals(source)) {
      String metricKey = (String) metric.getOrDefault("metricKey", metricId);
      // 시스템 메트릭은 dataset(V88)·pipeline_execution(V96) 등 RLS 테이블을 dsl 로 직접 집계한다 —
      // 트랜잭션 안에서 실행해야 GUC 가 주입되고, 그러지 않으면 언제나 0 이 수집된다.
      Double collected = transactionTemplate.execute(status -> collectSystemMetric(metricKey));
      value = collected == null ? 0.0 : collected;
    } else if ("dataset".equals(source)) {
      // 데이터셋 메트릭: 사용자 정의 SQL을 executor를 통해 실행하여 숫자 1개를 수집한다
      String query = (String) metric.get("query");
      if (query == null || query.isBlank()) {
        log.warn("MetricPollerService: dataset metric '{}' has no query, skipping", metricId);
        return;
      }
      // 애드혹 쿼리 경로와 같은 정규화·검증을 거친다(#745) — 원문을 그대로 보내면 끝의 "-- 주석"이
      // executor 가 붙이는 LIMIT 1 을 삼켜 결과 전체를 가져오고(수백만 행·수십 MB), 응답이 WebClient 버퍼
      // 한도를 넘어 수집이 매번 실패했다. 주석을 걷어내고 끝 세미콜론을 지운 뒤 스키마/함수 검증까지 한다.
      String cleanSql;
      try {
        cleanSql =
            SqlValidationUtils.removeTrailingSemicolon(SqlValidationUtils.stripAndValidate(query))
                .strip();
        metricSqlValidator.validate(cleanSql);
      } catch (SqlQueryException | UnsafeSqlException e) {
        log.warn(
            "MetricPollerService: dataset metric '{}' has invalid query, skipping: {}",
            metricId,
            e.getMessage());
        return;
      }
      try {
        // readOnly=true로 SELECT 쿼리만 허용하고, 결과 행 수를 1로 제한한다
        var result = executorClient.executeQuery(cleanSql, 1, true);
        if (result.rows() != null
            && !result.rows().isEmpty()
            && result.rows().get(0) != null
            && !result.rows().get(0).isEmpty()) {
          // 첫 번째 행의 첫 번째 컬럼 값을 double로 변환하여 메트릭 값으로 사용한다
          Object firstCell = result.rows().get(0).values().iterator().next();
          value =
              firstCell instanceof Number n
                  ? n.doubleValue()
                  : Double.parseDouble(String.valueOf(firstCell));
        } else {
          log.warn("MetricPollerService: dataset metric '{}' returned no data", metricId);
          return;
        }
      } catch (Exception e) {
        log.error("MetricPollerService: failed to collect dataset metric '{}'", metricId, e);
        return;
      }
    } else {
      log.warn("MetricPollerService: unknown metric source '{}' for metric '{}'", source, metricId);
      return;
    }

    // Save collected value to metric_snapshot
    snapshotRepository.save(jobId, metricId, value, now);
    lastPollTime.put(pollKey, now);

    // Run anomaly detection on the collected value
    List<MetricSnapshot> history = snapshotRepository.findRecent(jobId, metricId, HISTORY_DAYS);
    Optional<AnomalyEvent> anomaly =
        anomalyDetector.detect(history, value, sensitivity, jobId, userId, metricId, metricName);

    if (anomaly.isPresent()) {
      log.info(
          "MetricPollerService: anomaly detected for job={}, metric={}, value={}, deviation={}",
          jobId,
          metricId,
          value,
          anomaly.get().deviation());
      eventPublisher.publishEvent(anomaly.get());
    }
  }

  private double collectSystemMetric(String metricKey) {
    return switch (metricKey) {
      case "pipeline_failure_rate" -> {
        // Failed / total pipeline executions in last 24 hours
        LocalDateTime since = ProactiveTime.nowUtc().minusHours(24);
        int total =
            dsl.selectCount()
                .from(PIPELINE_EXECUTION)
                .where(PIPELINE_EXECUTION.CREATED_AT.ge(since))
                .fetchOne(0, int.class);
        if (total == 0) {
          yield 0.0;
        }
        int failed =
            dsl.selectCount()
                .from(PIPELINE_EXECUTION)
                .where(
                    PIPELINE_EXECUTION
                        .CREATED_AT
                        .ge(since)
                        .and(PIPELINE_EXECUTION.STATUS.eq("FAILED")))
                .fetchOne(0, int.class);
        yield (double) failed / total * 100.0; // percentage
      }
      case "pipeline_execution_count" -> {
        // Count pipeline executions in last 24 hours
        LocalDateTime since = ProactiveTime.nowUtc().minusHours(24);
        yield (double)
            dsl.selectCount()
                .from(PIPELINE_EXECUTION)
                .where(PIPELINE_EXECUTION.CREATED_AT.ge(since))
                .fetchOne(0, int.class);
      }
      case "dataset_total_count" -> {
        // Count total datasets
        yield (double) dsl.selectCount().from(DATASET).fetchOne(0, int.class);
      }
      case "active_user_count" -> {
        // Count distinct users who executed pipelines in last 24 hours
        LocalDateTime since = ProactiveTime.nowUtc().minusHours(24);
        yield (double)
            dsl.selectCount()
                .from(
                    dsl.selectDistinct(PIPELINE_EXECUTION.EXECUTED_BY)
                        .from(PIPELINE_EXECUTION)
                        .where(PIPELINE_EXECUTION.CREATED_AT.ge(since)))
                .fetchOne(0, int.class);
      }
      default -> {
        log.warn("MetricPollerService: unknown system metric '{}'", metricKey);
        yield 0.0;
      }
    };
  }
}
