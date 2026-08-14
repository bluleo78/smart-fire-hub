package com.smartfirehub.pipeline.service;

import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.notification.service.NotificationService;
import com.smartfirehub.pipeline.dto.TriggerResponse;
import com.smartfirehub.pipeline.event.PipelineCompletedEvent;
import com.smartfirehub.pipeline.repository.TriggerRepository;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class TriggerEventService {

  private final TriggerRepository triggerRepository;
  private final TriggerService triggerService;
  private final DatasetRepository datasetRepository;
  private final DSLContext dsl;
  private final NotificationService notificationService;
  private final TenantScopedRunner tenantScopedRunner;

  public TriggerEventService(
      TriggerRepository triggerRepository,
      @Lazy TriggerService triggerService,
      DatasetRepository datasetRepository,
      DSLContext dsl,
      NotificationService notificationService,
      TenantScopedRunner tenantScopedRunner) {
    this.triggerRepository = triggerRepository;
    this.triggerService = triggerService;
    this.datasetRepository = datasetRepository;
    this.dsl = dsl;
    this.notificationService = notificationService;
    this.tenantScopedRunner = tenantScopedRunner;
  }

  /**
   * Handle pipeline completion events for chain triggers. @Async ensures chain trigger failures
   * don't affect upstream pipeline status.
   */
  // TODO(P2-b): 이 경로는 pipeline_trigger 를 읽는다. @Async 라 TenantContextTaskDecorator 가
  // 제출 스레드의 테넌트를 승계하지만, 이벤트 발행자가 배경 스레드면 승계할 테넌트가 없다.
  // pipeline_trigger 에 RLS 를 걸 때 발행 경로를 함께 점검해야 한다 — 안 하면 PIPELINE_CHAIN
  // 트리거가 하위 파이프라인을 조용히 실행하지 않는다.
  @Async
  @EventListener
  public void onPipelineCompleted(PipelineCompletedEvent event) {
    log.info(
        "Pipeline {} completed with status {}, checking chain triggers",
        event.pipelineId(),
        event.status());

    List<TriggerResponse> triggers =
        triggerRepository.findEnabledChainTriggersByUpstreamId(event.pipelineId());

    for (TriggerResponse trigger : triggers) {
      try {
        String condition = (String) trigger.config().getOrDefault("condition", "SUCCESS");
        if (matchesCondition(condition, event.status())) {
          log.info(
              "Chain trigger {} matches condition {} for status {}",
              trigger.id(),
              condition,
              event.status());
          triggerService.fireTrigger(
              trigger.id(), Map.of("upstreamExecutionId", event.executionId()));
        }
      } catch (Exception e) {
        log.error("Failed to process chain trigger {}: {}", trigger.id(), e.getMessage(), e);
      }
    }
  }

  /**
   * Poll dataset changes every 30 seconds.
   *
   * <p>원 HTTP 요청이 없는 경로라 승계할 테넌트가 없다 — ACTIVE 테넌트를 순회해 테넌트별로 돈다.
   * 순회하지 않으면 RLS 가 pipeline_trigger·dataset 을 전부 차단해 이 트리거가 예외도 로그도 없이
   * 영구히 발화하지 않는다.
   */
  @Scheduled(fixedDelay = 30000)
  public void pollDatasetChanges() {
    tenantScopedRunner.forEachActiveTenant(tenantId -> pollDatasetChangesForTenant());
  }

  /** 한 테넌트 범위의 DATASET_CHANGE 폴링 본문. 호출 시점에 TenantContext 가 설정돼 있어야 한다. */
  private void pollDatasetChangesForTenant() {
    List<TriggerResponse> triggers = triggerRepository.findEnabledByType("DATASET_CHANGE");

    for (TriggerResponse trigger : triggers) {
      try {
        processDatasetChangeTrigger(trigger);
      } catch (Exception e) {
        log.error(
            "Failed to process dataset change trigger {}: {}", trigger.id(), e.getMessage(), e);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private void processDatasetChangeTrigger(TriggerResponse trigger) {
    Map<String, Object> config = trigger.config();
    Map<String, Object> state = trigger.triggerState();

    // Check polling interval
    int pollingIntervalSeconds =
        config.containsKey("pollingIntervalSeconds")
            ? ((Number) config.get("pollingIntervalSeconds")).intValue()
            : 60;

    if (state.containsKey("lastCheckedAt")) {
      LocalDateTime lastChecked = LocalDateTime.parse(state.get("lastCheckedAt").toString());
      if (ChronoUnit.SECONDS.between(lastChecked, LocalDateTime.now()) < pollingIntervalSeconds) {
        return; // Not time to poll yet
      }
    }

    // Get dataset IDs
    List<Number> datasetIdNumbers = (List<Number>) config.get("datasetIds");
    if (datasetIdNumbers == null || datasetIdNumbers.isEmpty()) {
      return;
    }
    List<Long> datasetIds = datasetIdNumbers.stream().map(Number::longValue).toList();

    // Get current row count estimates
    Map<Long, Long> currentSnapshot = getRowCountEstimates(datasetIds);

    // Get last snapshot
    Map<Long, Long> lastSnapshot = getLastSnapshot(state);

    // Check for changes
    List<Long> changedIds = new ArrayList<>();
    for (Long datasetId : datasetIds) {
      Long current = currentSnapshot.getOrDefault(datasetId, 0L);
      Long last = lastSnapshot.getOrDefault(datasetId, -1L);
      if (!current.equals(last)) {
        changedIds.add(datasetId);
      }
    }

    // Update state regardless of changes
    Map<String, Object> updatedState = new HashMap<>(state);
    updatedState.put("lastCheckedAt", LocalDateTime.now().toString());
    updatedState.put("lastSnapshot", currentSnapshot);

    if (!changedIds.isEmpty()) {
      // Check debounce
      int debounceSeconds =
          config.containsKey("debounceSeconds")
              ? ((Number) config.get("debounceSeconds")).intValue()
              : 60;

      if (isDebounceElapsed(state, debounceSeconds)) {
        log.info("Dataset change detected for trigger {}, datasets: {}", trigger.id(), changedIds);
        triggerService.fireTrigger(trigger.id(), Map.of("changedDatasets", changedIds));
        updatedState.put("lastFiredAt", LocalDateTime.now().toString());

        for (Long changedDatasetId : changedIds) {
          String datasetName =
              datasetRepository
                  .findById(changedDatasetId)
                  .map(d -> d.name())
                  .orElse(String.valueOf(changedDatasetId));
          notificationService.notifyDatasetChanged(changedDatasetId, datasetName);
        }
      } else {
        log.debug("Dataset change detected for trigger {} but debounce not elapsed", trigger.id());
      }
    }

    triggerRepository.updateTriggerState(trigger.id(), updatedState);

    // If all monitored datasets are gone, disable trigger
    if (currentSnapshot.isEmpty() && !datasetIds.isEmpty()) {
      log.warn("All monitored datasets deleted for trigger {}, disabling", trigger.id());
      triggerRepository.updateEnabled(trigger.id(), false);
    }
  }

  private Map<Long, Long> getRowCountEstimates(List<Long> datasetIds) {
    Map<Long, Long> result = new HashMap<>();

    for (Long datasetId : datasetIds) {
      Optional<String> tableNameOpt = datasetRepository.findTableNameById(datasetId);
      if (tableNameOpt.isEmpty()) {
        continue; // Dataset deleted, skip
      }

      String tableName = tableNameOpt.get();
      try {
        Long rowCount =
            dsl.fetchOne(
                    "SELECT n_live_tup FROM pg_stat_user_tables WHERE schemaname = 'data' AND"
                        + " relname = ?",
                    tableName)
                .get(0, Long.class);
        result.put(datasetId, rowCount);
      } catch (Exception e) {
        log.debug(
            "Failed to get row count estimate for dataset {} (table: {}): {}",
            datasetId,
            tableName,
            e.getMessage());
      }
    }

    return result;
  }

  @SuppressWarnings("unchecked")
  private Map<Long, Long> getLastSnapshot(Map<String, Object> state) {
    if (!state.containsKey("lastSnapshot")) {
      return Map.of();
    }
    try {
      Map<String, Object> raw = (Map<String, Object>) state.get("lastSnapshot");
      Map<Long, Long> result = new HashMap<>();
      for (Map.Entry<String, Object> entry : raw.entrySet()) {
        result.put(Long.parseLong(entry.getKey()), ((Number) entry.getValue()).longValue());
      }
      return result;
    } catch (Exception e) {
      return Map.of();
    }
  }

  private boolean isDebounceElapsed(Map<String, Object> state, int debounceSeconds) {
    if (!state.containsKey("lastFiredAt")) {
      return true;
    }
    try {
      LocalDateTime lastFired = LocalDateTime.parse(state.get("lastFiredAt").toString());
      return ChronoUnit.SECONDS.between(lastFired, LocalDateTime.now()) >= debounceSeconds;
    } catch (Exception e) {
      return true;
    }
  }

  private boolean matchesCondition(String condition, String status) {
    return switch (condition) {
      case "SUCCESS" -> "COMPLETED".equals(status);
      case "FAILURE" -> "FAILED".equals(status);
      case "ANY" -> true;
      default -> false;
    };
  }
}
