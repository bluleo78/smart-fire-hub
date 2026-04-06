package com.smartfirehub.proactive.service;

import static com.smartfirehub.jooq.Tables.DATASET;
import static com.smartfirehub.jooq.Tables.PIPELINE_EXECUTION;
import static com.smartfirehub.jooq.Tables.PROACTIVE_JOB;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@Service
@Slf4j
@RequiredArgsConstructor
public class MetricPollerService {

  private final DSLContext dsl;
  private final MetricSnapshotRepository snapshotRepository;
  private final AnomalyDetector anomalyDetector;
  private final ApplicationEventPublisher eventPublisher;
  private final ObjectMapper objectMapper;
  // 데이터셋 메트릭 수집을 위한 SQL 실행 클라이언트
  private final com.smartfirehub.pipeline.service.executor.ExecutorClient executorClient;

  // Track last poll time per job+metric to respect pollingInterval
  private final Map<String, LocalDateTime> lastPollTime = new ConcurrentHashMap<>();

  private static final int HISTORY_DAYS = 30;

  @Scheduled(fixedDelay = 30000)
  public void poll() {
    try {
      pollMetrics();
    } catch (Exception e) {
      log.error("MetricPollerService: polling failed", e);
    }
  }

  @SuppressWarnings("unchecked")
  private void pollMetrics() {
    // 1. Query enabled proactive jobs with anomaly trigger type
    var jobs =
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
            .fetch();

    if (jobs.isEmpty()) {
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
    LocalDateTime now = LocalDateTime.now();

    if (lastPoll != null && lastPoll.plusSeconds(pollingIntervalSeconds).isAfter(now)) {
      return; // Not yet time to poll
    }

    double value;
    if ("system".equals(source)) {
      String metricKey = (String) metric.getOrDefault("metricKey", metricId);
      value = collectSystemMetric(metricKey);
    } else if ("dataset".equals(source)) {
      // 데이터셋 메트릭: 사용자 정의 SQL을 executor를 통해 실행하여 숫자 1개를 수집한다
      String query = (String) metric.get("query");
      if (query == null || query.isBlank()) {
        log.warn("MetricPollerService: dataset metric '{}' has no query, skipping", metricId);
        return;
      }
      try {
        // readOnly=true로 SELECT 쿼리만 허용하고, 결과 행 수를 1로 제한한다
        var result = executorClient.executeQuery(query, 1, true);
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
        LocalDateTime since = LocalDateTime.now().minusHours(24);
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
        LocalDateTime since = LocalDateTime.now().minusHours(24);
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
        LocalDateTime since = LocalDateTime.now().minusHours(24);
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
