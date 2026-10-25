package com.smartfirehub.pipeline.service;

import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.notification.service.NotificationService;
import com.smartfirehub.pipeline.dto.TriggerResponse;
import com.smartfirehub.pipeline.event.PipelineCompletedEvent;
import com.smartfirehub.pipeline.repository.TriggerEventRepository;
import com.smartfirehub.pipeline.repository.TriggerRepository;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
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
  // 연쇄 깊이(WD-106) — 완료된 실행이 연쇄의 몇 번째 단계였는지 그 실행의 FIRED 이벤트에서 읽는다.
  private final TriggerEventRepository triggerEventRepository;
  private final TriggerService triggerService;
  private final DatasetRepository datasetRepository;
  private final NotificationService notificationService;
  private final TenantScopedRunner tenantScopedRunner;
  // 테이블 이름 조회(와 PG 행 수)를 한 트랜잭션에 묶는 경계 — probeDatasets 참고.
  private final TransactionTemplate transactionTemplate;
  // 테넌트 저장소 백엔드 해석(WD-80) — 행 수를 그 백엔드의 저장소로 센다.
  private final DataStoreResolver dataStoreResolver;
  // 테넌트 저장소가 Tibero 인지 판정하는 단일 지점(WD-80) — Tibero 면 행 수를 PG 트랜잭션 밖에서 센다.
  private final PipelineDataSchema pipelineDataSchema;

  public TriggerEventService(
      TriggerRepository triggerRepository,
      TriggerEventRepository triggerEventRepository,
      @Lazy TriggerService triggerService,
      DatasetRepository datasetRepository,
      NotificationService notificationService,
      TenantScopedRunner tenantScopedRunner,
      TransactionTemplate transactionTemplate,
      DataStoreResolver dataStoreResolver,
      PipelineDataSchema pipelineDataSchema) {
    this.triggerRepository = triggerRepository;
    this.triggerEventRepository = triggerEventRepository;
    this.triggerService = triggerService;
    this.datasetRepository = datasetRepository;
    this.notificationService = notificationService;
    this.tenantScopedRunner = tenantScopedRunner;
    this.transactionTemplate = transactionTemplate;
    this.dataStoreResolver = dataStoreResolver;
    this.pipelineDataSchema = pipelineDataSchema;
  }

  /**
   * PIPELINE_CHAIN 트리거 처리 — 상위 파이프라인 완료 이벤트를 받아 하위 파이프라인을 발화한다. 단 중단된 실행(WD-230, {@code
   * event.interrupted()})은 조건과 무관하게 발화하지 않는다. {@code @Async} 라 체인 트리거 실패가 상위 파이프라인 상태에 영향을 주지 않는다.
   *
   * <p><b>테넌트 승계(P2-b)</b>: 이 경로는 RLS 가 걸린 {@code pipeline_trigger} 를 읽는다. 한정자를 {@code
   * "taskExecutor"} 로 <b>명시</b>하는 이유는, 그 이름의 빈이 {@code AsyncConfig} 에 {@link
   * com.smartfirehub.global.tenant.TenantContextTaskDecorator} 와 함께 등록돼 있기 때문이다. 한정자가 없어도 Spring 은
   * 같은 빈을 찾지만, 그 빈이 사라지면 조용히 데코레이터 없는 {@code SimpleAsyncTaskExecutor} 로 폴백해 테넌트가 승계되지 않고 — 예외도 로그도
   * 없이 — 체인 트리거가 영구히 발화하지 않는다. 명시해 두면 그때 기동 시점에 빈 해석이 실패한다.
   *
   * <p>발행자는 러너({@code PipelineAsyncRunner.executeAsync}, `pipelineExecutor` 풀, 데코레이터 있음)와 정리기
   * ({@code PipelineExecutionSweeper}, 같은 테넌트 컨텍스트에서 발행)다. 두 곳 모두 발행 스레드에 이미 테넌트가 있으므로, 여기까지 제출 시점
   * 캡처로 승계된다. 이벤트 페이로드에 tenantId 를 싣지 않는 근거다.
   */
  @Async("taskExecutor")
  @EventListener
  public void onPipelineCompleted(PipelineCompletedEvent event) {
    // WD-230: api 재시작으로 중단된 실행은 조건(SUCCESS/FAILURE/ANY)과 무관하게 체인을 발화하지 않는다 — 중간에 끊긴 상위 결과로 하위가 이어
    // 돌면 안 된다.
    if (event.interrupted()) {
      log.info(
          "PIPELINE_CHAIN: api 재시작으로 중단된 실행이라 체인 트리거를 발화하지 않음 (pipeline={}, execution={})",
          event.pipelineId(),
          event.executionId());
      return;
    }
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
    if (triggers.isEmpty()) {
      return;
    }

    // 실행 시 연쇄 깊이 방어(WD-106): 완료된 실행이 연쇄의 d 번째 단계였다면 이번 발화는 d+1 단계다. 수동·스케줄·API 로
    // 시작된 실행은 0 단계다. 이 값을 params 로 넘기면 fireTrigger 가 상한을 넘는 발화를 건너뛰고, 넘지 않으면 FIRED 이벤트
    // detail 에 남겨 다음 단계가 이어 받는다 — 저장 시 검사를 빠져나간 순환(이미 DB 에 있는 것 포함)이 끝없이 돌지 않게 한다.
    int chainDepth =
        triggerEventRepository
                .findChainDepthOfExecution(event.pipelineId(), event.executionId())
                .orElse(0)
            + 1;

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
              trigger.id(),
              Map.of(
                  "upstreamExecutionId",
                  event.executionId(),
                  TriggerService.CHAIN_DEPTH_PARAM,
                  chainDepth));
        }
      } catch (Exception e) {
        log.error("Failed to process chain trigger {}: {}", trigger.id(), e.getMessage(), e);
      }
    }
  }

  /**
   * Poll dataset changes every 30 seconds.
   *
   * <p>원 HTTP 요청이 없는 경로라 승계할 테넌트가 없다 — ACTIVE 테넌트를 순회해 테넌트별로 돈다. 순회하지 않으면 RLS 가
   * pipeline_trigger·dataset 을 전부 차단해 이 트리거가 예외도 로그도 없이 영구히 발화하지 않는다.
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
    // 저장 검증과 같은 해석 규칙으로 읽는다(WD-225) — 검증 이전에 저장된 읽을 수 없는 옛 값(문자열·int 넘침)은 기본값 60 으로 본다.
    int pollingIntervalSeconds =
        DatasetChangeTriggerConfig.secondsOrDefault(
            config.get(DatasetChangeTriggerConfig.POLLING_INTERVAL_KEY), 60);

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

    // 감시 대상의 존재 여부와 행 수를 함께 모은다. 존재는 dataset 행으로 따로 판정한다 — 행 수 조회 실패(통계 행이 아직 없는 새
    // 테이블, Tibero 네트워크 실패 등)를 삭제로 오인해 감시 목록에서 빼지 않기 위함이다(WD-221). 행 수는 테넌트 저장소 백엔드로 센다(WD-80).
    DatasetProbe probe = probeDatasets(datasetIds);

    // Get last snapshot
    Map<Long, Long> lastSnapshot = getLastSnapshot(state);

    SnapshotDiff diff =
        diffSnapshots(datasetIds, probe.existing(), probe.rowCounts(), lastSnapshot);
    List<Long> changedIds = diff.changedIds();

    // Update state regardless of changes
    Map<String, Object> updatedState = new HashMap<>(state);
    updatedState.put("lastCheckedAt", LocalDateTime.now().toString());
    updatedState.put("lastSnapshot", diff.nextSnapshot());

    if (!changedIds.isEmpty()) {
      // Check debounce
      // 폴링 주기와 같은 해석 규칙(WD-225). 폴러의 디바운스 기본값은 60 으로 저장 검증의 기본값(0)과 다르다 — 기존 동작을 유지한다.
      int debounceSeconds =
          DatasetChangeTriggerConfig.secondsOrDefault(
              config.get(DatasetChangeTriggerConfig.DEBOUNCE_KEY), 60);

      if (isDebounceElapsed(state, debounceSeconds)) {
        log.info("Dataset change detected for trigger {}, datasets: {}", trigger.id(), changedIds);
        triggerService.fireTrigger(trigger.id(), Map.of("changedDatasets", changedIds));
        updatedState.put("lastFiredAt", LocalDateTime.now().toString());

        // 이번 발화의 알림들이 수신자 자격을 공유한다(같은 테넌트·같은 회차 — 리뷰 M2).
        Map<Long, com.smartfirehub.securitylevel.access.Clearance> clearanceCache = new HashMap<>();
        for (Long changedDatasetId : changedIds) {
          String datasetName =
              datasetRepository
                  .findById(changedDatasetId)
                  .map(d -> d.name())
                  .orElse(String.valueOf(changedDatasetId));
          notificationService.notifyDatasetChanged(changedDatasetId, datasetName, clearanceCache);
        }
      } else {
        log.debug("Dataset change detected for trigger {} but debounce not elapsed", trigger.id());
      }
    }

    triggerRepository.updateTriggerState(trigger.id(), updatedState);

    // 이미 없는 감시 대상은 감시 목록·스냅숏에서 뺀다(WD-221). 데이터셋 삭제 경로가 같은 정리를 하므로 여기 걸리는 것은 그 수정 이전에 지워진
    // 데이터셋(운영에 남은 옛 행)과 편집 창이 지워진 id 를 그대로 되돌려 저장한 경우다. 하나도 남지 않으면 같은 문장이 트리거를 끄고,
    // 꺼진 이유는 조회 응답의 watchedDatasetsMissing 으로 보인다. 위 상태 저장 뒤에 해야 지운 스냅숏 키가 되살아나지 않는다.
    for (Long missingId : diff.missingIds()) {
      triggerRepository.removeWatchedDataset(missingId);
    }
    if (!diff.missingIds().isEmpty()) {
      log.warn(
          "Removed deleted datasets {} from DATASET_CHANGE trigger {}",
          diff.missingIds(),
          trigger.id());
    }
  }

  /** 감시 대상 조회 결과 — 지금 있는 데이터셋 id 와 그중 행 수를 읽은 것. */
  record DatasetProbe(Set<Long> existing, Map<Long, Long> rowCounts) {}

  /**
   * 스냅숏 비교 결과.
   *
   * @param changedIds 행 수가 직전 기준선과 달라진(=발화할) 데이터셋
   * @param nextSnapshot 다음 폴링이 비교할 기준선(있는 데이터셋만)
   * @param missingIds 더 이상 없는(삭제된) 감시 대상
   */
  record SnapshotDiff(List<Long> changedIds, Map<Long, Long> nextSnapshot, List<Long> missingIds) {}

  /**
   * 감시 대상별로 직전 기준선과 지금 행 수를 비교한다(WD-221). 순수 함수라 단위 테스트로 판정 규칙을 고정한다.
   *
   * <ul>
   *   <li>없는(삭제된) 데이터셋: 변경 판정에서 빼고 기준선에서도 지운다 — 예전엔 행 수 0 과 직전값을 비교해 매 주기 「변경」이 되어 같은 삭제 데이터셋으로
   *       파이프라인이 계속 실행됐다.
   *   <li>직전 기준선이 없는 데이터셋(새 트리거·새로 추가된 감시 대상·다시 켠 트리거): 기준선만 잡고 변경으로 보지 않는다 — 예전엔 직전값을 -1 로 두어
   *       만들자마자(빈 데이터셋이어도) 첫 폴링에서 발화했다.
   *   <li>있지만 행 수를 못 읽은 데이터셋: 변경으로 보지 않고 직전 기준선을 그대로 둔다 — 일시적 조회 실패(Tibero 는 네트워크를 거쳐 더 잦다, WD-80)가
   *       다음 폴링의 거짓 「변경」이 되지 않게.
   * </ul>
   */
  static SnapshotDiff diffSnapshots(
      List<Long> watched, Set<Long> existing, Map<Long, Long> current, Map<Long, Long> last) {
    List<Long> changed = new ArrayList<>();
    List<Long> missing = new ArrayList<>();
    Map<Long, Long> next = new HashMap<>();
    for (Long id : new LinkedHashSet<>(watched)) {
      if (!existing.contains(id)) {
        missing.add(id);
        continue;
      }
      Long now = current.get(id);
      Long before = last.get(id);
      if (now == null) {
        if (before != null) {
          next.put(id, before);
        }
        continue;
      }
      next.put(id, now);
      if (before != null && !now.equals(before)) {
        changed.add(id);
      }
    }
    return new SnapshotDiff(changed, next, missing);
  }

  /**
   * 모니터링 대상 데이터셋의 존재 여부와 행 수를 테넌트 저장소 백엔드로 모은다(WD-80). PG 는 통계 추정치, Tibero 는 정확한 {@code COUNT(*)}
   * 다.
   *
   * <p><b>트랜잭션 경계(P2-b)</b>: 테이블 이름 조회(RLS 대상인 dataset)는 {@code transactionTemplate} 안에서 한다. 감싸는
   * 이유는 GUC 가 아니라 <b>커넥션 왕복 절감</b>이다 — {@code datasetRepository} 는 클래스 레벨 {@code @Transactional} 이라
   * 호출마다 GUC 가 주입되지만, 감싸면 데이터셋 N 개에 대한 호출이 트랜잭션 하나에 합류한다. PG 는 지금처럼 행 수도 같은 트랜잭션에서 센다({@code
   * pg_stat_user_tables} 는 RLS 무관 시스템 뷰). Tibero 는 네트워크 너머의 질의라 PG 커넥션을 쥔 채 세지 않도록 트랜잭션 밖에서 센다.
   *
   * <p>트리거 발화({@code triggerService.fireTrigger})와 알림은 <b>이 트랜잭션 밖</b>에 남긴다 — 파이프라인 실행을 시작하는 작업이라
   * 트랜잭션에 넣으면 그 동안 커넥션을 점유하고 실패 의미도 바뀐다.
   */
  private DatasetProbe probeDatasets(List<Long> datasetIds) {
    DatasetProbe probe;
    if (pipelineDataSchema.isTibero()) {
      // Tibero: 이름만 트랜잭션 안에서 읽고, Tibero 질의(행 수)는 PG 트랜잭션 밖에서 실행한다.
      Map<Long, String> tableNames =
          transactionTemplate.execute(status -> readTableNames(datasetIds));
      probe = tableNames == null ? null : countRows(tableNames);
    } else {
      // PG: 이름과 행 수를 같은 트랜잭션에서 센다.
      probe = transactionTemplate.execute(status -> countRows(readTableNames(datasetIds)));
    }
    // 판정할 수 없는 회차(null)는 모두 "있지만 못 읽음"으로 둔다 — 존재를 비우면 diffSnapshots 가 전부 삭제로 보고 감시 목록에서 빼 트리거를 끈다.
    return probe == null ? new DatasetProbe(new HashSet<>(datasetIds), Map.of()) : probe;
  }

  /** 테이블 이름으로 행 수를 세고 데이터셋별로 되돌린다. 트랜잭션 구간은 호출자(probeDatasets)가 정한다. */
  private DatasetProbe countRows(Map<Long, String> tableNames) {
    Map<String, Long> countsByTable = dataStoreResolver.rowStore().rowCounts(tableNames.values());
    Map<Long, Long> counts = new HashMap<>();
    for (Map.Entry<Long, String> e : tableNames.entrySet()) {
      Long count = countsByTable.get(e.getValue());
      if (count != null) {
        counts.put(e.getKey(), count);
      }
    }
    // 이름이 있는 데이터셋이 "있는" 데이터셋이다 — 행 수를 못 센 것은 counts 에서만 빠진다.
    return new DatasetProbe(new HashSet<>(tableNames.keySet()), counts);
  }

  /** 데이터셋별 테이블 이름. 지워진 데이터셋(이름 없음)은 빠진다. 트랜잭션 안에서 부른다. */
  private Map<Long, String> readTableNames(List<Long> datasetIds) {
    Map<Long, String> tableNames = new LinkedHashMap<>();
    for (Long datasetId : datasetIds) {
      datasetRepository.findTableNameById(datasetId).ifPresent(t -> tableNames.put(datasetId, t));
    }
    return tableNames;
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
    // 사용자가 취소한 실행(CANCELLED)은 어떤 조건(ANY 포함)으로도 체인을 발화하지 않는다(WD-153) — 멈춘 흐름의 하위 파이프라인이 이어서 돌면
    // "멈췄다"는 의도와 반대다. 러너는 취소 시 완료 이벤트 자체를 발행하지 않으며, 이 분기는 그 계약이 깨졌을 때의 방어다.
    if ("CANCELLED".equals(status)) {
      return false;
    }
    return switch (condition) {
      case "SUCCESS" -> "COMPLETED".equals(status);
      case "FAILURE" -> "FAILED".equals(status);
      case "ANY" -> true;
      default -> false;
    };
  }
}
