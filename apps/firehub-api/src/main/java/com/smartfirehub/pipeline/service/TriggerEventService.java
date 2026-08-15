package com.smartfirehub.pipeline.service;

import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.global.tenant.TenantContext;
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
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
public class TriggerEventService {

  private final TriggerRepository triggerRepository;
  private final TriggerService triggerService;
  private final DatasetRepository datasetRepository;
  private final DSLContext dsl;
  private final NotificationService notificationService;
  private final TenantScopedRunner tenantScopedRunner;
  // dsl 직접 조회 구간에 GUC 를 주입하기 위한 트랜잭션 경계.
  private final TransactionTemplate transactionTemplate;

  public TriggerEventService(
      TriggerRepository triggerRepository,
      @Lazy TriggerService triggerService,
      DatasetRepository datasetRepository,
      DSLContext dsl,
      NotificationService notificationService,
      TenantScopedRunner tenantScopedRunner,
      TransactionTemplate transactionTemplate) {
    this.triggerRepository = triggerRepository;
    this.triggerService = triggerService;
    this.datasetRepository = datasetRepository;
    this.dsl = dsl;
    this.notificationService = notificationService;
    this.tenantScopedRunner = tenantScopedRunner;
    this.transactionTemplate = transactionTemplate;
  }

  /**
   * PIPELINE_CHAIN 트리거 처리 — 상위 파이프라인 완료 이벤트를 받아 하위 파이프라인을 발화한다.
   * {@code @Async} 라 체인 트리거 실패가 상위 파이프라인 상태에 영향을 주지 않는다.
   *
   * <p><b>테넌트 승계(P2-b)</b>: 이 경로는 RLS 가 걸린 {@code pipeline_trigger} 를 읽는다. 한정자를
   * {@code "taskExecutor"} 로 <b>명시</b>하는 이유는, 그 이름의 빈이 {@code AsyncConfig} 에
   * {@link com.smartfirehub.global.tenant.TenantContextTaskDecorator} 와 함께 등록돼 있기
   * 때문이다. 한정자가 없어도 Spring 은 같은 빈을 찾지만, 그 빈이 사라지면 조용히 데코레이터 없는
   * {@code SimpleAsyncTaskExecutor} 로 폴백해 테넌트가 승계되지 않고 — 예외도 로그도 없이 —
   * 체인 트리거가 영구히 발화하지 않는다. 명시해 두면 그때 기동 시점에 빈 해석이 실패한다.
   *
   * <p>발행자는 {@code PipelineAsyncRunner.executeAsync}(=`pipelineExecutor` 풀, 데코레이터 있음)
   * 하나뿐이며 그 스레드에는 이미 테넌트가 있으므로, 여기까지 제출 시점 캡처로 승계된다. 이벤트
   * 페이로드에 tenantId 를 싣지 않는 근거다.
   */
  @Async("taskExecutor")
  @EventListener
  public void onPipelineCompleted(PipelineCompletedEvent event) {
    // 승계가 끊기면 아래 조회가 RLS 로 0행이 되어 원인 없는 무동작이 된다 — 진단 가능하게 남긴다.
    if (TenantContext.get() == null) {
      log.error(
          "PIPELINE_CHAIN: 테넌트 컨텍스트 없이 완료 이벤트를 처리한다 (pipeline={}). "
              + "체인 트리거가 조회되지 않아 하위 파이프라인이 실행되지 않는다.",
          event.pipelineId());
    }

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

  /**
   * 모니터링 대상 데이터셋의 행 수 추정치를 모은다.
   *
   * <p><b>트랜잭션 경계(P2-b)</b>: 이 구간을 감싸는 이유는 GUC 가 아니라 <b>커넥션 왕복 절감</b>이다.
   * RLS 대상인 dataset 조회는 {@code datasetRepository}(클래스 레벨 {@code @Transactional}) 를 거치므로
   * 이미 호출마다 GUC 가 주입되고, 여기서 {@code dsl} 로 직접 읽는 것은 {@code pg_stat_user_tables}
   * (시스템 뷰 — RLS 무관, tenant_id 없음) 뿐이다. 감싸면 데이터셋 N 개에 대한 리포지토리 호출이
   * 트랜잭션 하나에 합류한다("GUC 때문에 반드시 필요한 래핑" 은 아니라는 점을 분명히 남긴다).
   *
   * <p>트리거 발화({@code triggerService.fireTrigger})와 알림은 <b>이 트랜잭션 밖</b>에 남긴다 —
   * 파이프라인 실행을 시작하는 작업이라 트랜잭션에 넣으면 그 동안 커넥션을 점유하고 실패 의미도 바뀐다.
   */
  private Map<Long, Long> getRowCountEstimates(List<Long> datasetIds) {
    Map<Long, Long> result =
        transactionTemplate.execute(status -> collectRowCountEstimates(datasetIds));
    return result == null ? Map.of() : result;
  }

  private Map<Long, Long> collectRowCountEstimates(List<Long> datasetIds) {
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
