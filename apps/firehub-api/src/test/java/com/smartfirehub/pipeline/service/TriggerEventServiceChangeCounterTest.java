package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.storage.DataRowStore;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.notification.service.NotificationService;
import com.smartfirehub.pipeline.dto.TriggerResponse;
import com.smartfirehub.pipeline.repository.TriggerEventRepository;
import com.smartfirehub.pipeline.repository.TriggerRepository;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DATASET_CHANGE 폴러가 행 수와 함께 누적 변경 카운터를 비교하는 규칙(WD-399)을 Mockito 로 검증한다.
 *
 * <p>행 수가 그대로인 변경(업서트로 값 수정·행 편집·같은 주기의 삭제+추가)은 카운터로, REPLACE 맞바꿈은 테이블 실체(OID) 변화로, 카운터를 올리지 않는
 * TRUNCATE 교체는 행 수로 잡는다. 카운터가 줄면(통계 초기화) 기준선만 다시 잡고, 트리거 자신의 출력 데이터셋은 카운터로 판정하지 않는다. 카운터 기준선은 새 상태
 * 키 {@code lastChangeSnapshot} 에 두어, 옛 상태({@code lastSnapshot} 만 있음)로 도는 트리거가 배포 직후 첫 폴링에서 발화하지 않아야
 * 한다. 실제 {@code pg_stat_user_tables} 값은 비동기로 갱신돼 통합 테스트로 안정적으로 만들 수 없어 저장소를 모킹한다.
 */
class TriggerEventServiceChangeCounterTest {

  private static final long TRIGGER_ID = 10L;

  private TriggerRepository triggerRepository;
  private TriggerService triggerService;
  private DataRowStore rowStore;
  private TriggerEventService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    triggerRepository = mock(TriggerRepository.class);
    triggerService = mock(TriggerService.class);
    DatasetRepository datasetRepository = mock(DatasetRepository.class);
    when(datasetRepository.findTableNameById(1L)).thenReturn(Optional.of("t1"));
    rowStore = mock(DataRowStore.class);
    DataStoreResolver resolver = mock(DataStoreResolver.class);
    when(resolver.currentBackend()).thenReturn(DataStoreBackend.POSTGRESQL);
    when(resolver.rowStore()).thenReturn(rowStore);
    // 테넌트 순회는 테넌트 하나만 돌린다.
    TenantScopedRunner runner = mock(TenantScopedRunner.class);
    doAnswer(
            inv -> {
              ((Consumer<Long>) inv.getArgument(0)).accept(1L);
              return null;
            })
        .when(runner)
        .forEachActiveTenant(any());
    // 트랜잭션 템플릿은 콜백을 바로 실행한다(구간 검증은 TriggerEventServiceRowCountTxTest).
    TransactionTemplate tx = mock(TransactionTemplate.class);
    when(tx.execute(any()))
        .thenAnswer(inv -> ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
    service =
        new TriggerEventService(
            triggerRepository,
            mock(TriggerEventRepository.class),
            triggerService,
            datasetRepository,
            mock(NotificationService.class),
            runner,
            tx,
            resolver,
            new PipelineDataSchema(resolver, mock(SqlValidator.class)));
  }

  /** 폴링 간격·디바운스를 0 으로 둔 데이터셋 1 감시 트리거. state 는 DB(jsonb)에서 읽은 모양(문자열 키)이다. */
  private static TriggerResponse trigger(Map<String, Object> state) {
    return new TriggerResponse(
        TRIGGER_ID,
        20L,
        "DATASET_CHANGE",
        "변경 감지",
        null,
        true,
        Map.of("datasetIds", List.of(1), "pollingIntervalSeconds", 0, "debounceSeconds", 0),
        state,
        null,
        1L,
        LocalDateTime.now());
  }

  /** 테이블 실체(OID) — 맞바꿈 전후를 구분한다. */
  private static final long REL = 5000L;

  /** 직전 기준선 — 행 수 3, 실체 REL, 변경 카운터(null 이면 카운터·실체 키가 없는 옛 상태). */
  private static Map<String, Object> state(Integer lastCounter) {
    Map<String, Object> state = new HashMap<>();
    state.put("lastSnapshot", Map.of("1", 3));
    if (lastCounter != null) {
      state.put("lastChangeSnapshot", Map.of("1", lastCounter));
      state.put("lastRelationSnapshot", Map.of("1", (int) REL));
    }
    return state;
  }

  /** 한 회차를 돌리고 저장된 상태를 돌려준다. counter 가 null 이면 카운터를 못 읽은 회차다. */
  @SuppressWarnings("unchecked")
  private Map<String, Object> runRound(
      Map<String, Object> state, long rows, long relation, Long counter) {
    when(rowStore.rowCounts(any())).thenReturn(Map.of("t1", rows));
    when(rowStore.changeCounters(any()))
        .thenReturn(
            counter == null
                ? Map.of()
                : Map.of("t1", new DataRowStore.ChangeCounter(relation, counter)));
    when(triggerRepository.findEnabledByType("DATASET_CHANGE")).thenReturn(List.of(trigger(state)));
    service.pollDatasetChanges();
    ArgumentCaptor<Map<String, Object>> saved = ArgumentCaptor.forClass(Map.class);
    verify(triggerRepository).updateTriggerState(eq(TRIGGER_ID), saved.capture());
    return saved.getValue();
  }

  /** 업서트·행 편집 — 행 수는 그대로지만 카운터가 올랐으면 발화하고 새 카운터를 기준선으로 남긴다. */
  @Test
  void sameRowCount_counterIncreased_fires() {
    Map<String, Object> saved = runRound(state(100), 3L, REL, 105L);

    verify(triggerService, times(1)).fireTrigger(eq(TRIGGER_ID), anyMap());
    assertThat(saved.get("lastSnapshot")).isEqualTo(Map.of(1L, 3L));
    assertThat(saved.get("lastChangeSnapshot")).isEqualTo(Map.of(1L, 105L));
    assertThat(saved.get("lastRelationSnapshot")).isEqualTo(Map.of(1L, REL));
  }

  /** 아무 변경도 없으면(행 수·실체·카운터 모두 같음) 발화하지 않는다. */
  @Test
  void nothingChanged_doesNotFire() {
    Map<String, Object> saved = runRound(state(100), 3L, REL, 100L);

    verify(triggerService, never()).fireTrigger(anyLong(), anyMap());
    assertThat(saved.get("lastChangeSnapshot")).isEqualTo(Map.of(1L, 100L));
  }

  /** REPLACE 맞바꿈 — 새 테이블이라 카운터가 작게 새로 세어져도 실체가 바뀌었으므로 발화한다. */
  @Test
  void relationSwapped_fires() {
    Map<String, Object> saved = runRound(state(100), 3L, REL + 1, 3L);

    verify(triggerService, times(1)).fireTrigger(eq(TRIGGER_ID), anyMap());
    assertThat(saved.get("lastRelationSnapshot")).isEqualTo(Map.of(1L, REL + 1));
  }

  /** TRUNCATE 교체처럼 카운터는 그대로인데 행 수가 바뀌면 행 수 비교로 발화한다. */
  @Test
  void counterSame_rowCountChanged_fires() {
    runRound(state(100), 0L, REL, 100L);

    verify(triggerService, times(1)).fireTrigger(eq(TRIGGER_ID), anyMap());
  }

  /** 같은 실체에서 카운터가 줄면 통계 초기화·크래시 복구다 — 발화하지 않고 기준선만 새로 잡는다(모든 트리거 동시 발화 방지). */
  @Test
  void counterDropped_sameRelation_onlyRebaselines() {
    Map<String, Object> saved = runRound(state(100), 3L, REL, 0L);

    verify(triggerService, never()).fireTrigger(anyLong(), anyMap());
    assertThat(saved.get("lastChangeSnapshot")).isEqualTo(Map.of(1L, 0L));
  }

  /** 배포 직후 — 옛 상태(카운터·실체 기준선 없음)는 첫 폴링에 기준선만 잡고 발화하지 않는다. */
  @Test
  void legacyStateWithoutCounterBaseline_onlyRecordsBaseline() {
    Map<String, Object> saved = runRound(state(null), 3L, REL, 100L);

    verify(triggerService, never()).fireTrigger(anyLong(), anyMap());
    assertThat(saved.get("lastChangeSnapshot")).isEqualTo(Map.of(1L, 100L));
    assertThat(saved.get("lastRelationSnapshot")).isEqualTo(Map.of(1L, REL));
  }

  /** 카운터를 못 읽은 회차(카운터 없는 백엔드·조회 실패)는 변경이 아니고 직전 기준선을 이어 받는다. */
  @Test
  void counterUnreadable_doesNotFire_andCarriesBaseline() {
    Map<String, Object> saved = runRound(state(100), 3L, REL, null);

    verify(triggerService, never()).fireTrigger(anyLong(), anyMap());
    assertThat(saved.get("lastChangeSnapshot")).isEqualTo(Map.of(1L, 100L));
    assertThat(saved.get("lastRelationSnapshot")).isEqualTo(Map.of(1L, REL));
  }

  /**
   * 자기 출력을 감시하는 트리거 — 파이프라인이 같은 행을 다시 써 카운터·실체만 바뀐 것은 발화하지 않는다(자기 발화 반복 방지). 기준선은 갱신해 다음 판정이 밀리지 않게
   * 하고, 행 수가 바뀌면 예전처럼 발화한다.
   */
  @Test
  void ownOutputDataset_counterChangeIgnored_rowCountStillCounts() {
    when(triggerRepository.findOutputDatasetIds(20L)).thenReturn(Set.of(1L));

    Map<String, Object> saved = runRound(state(100), 3L, REL + 1, 200L);
    verify(triggerService, never()).fireTrigger(anyLong(), anyMap());
    assertThat(saved.get("lastChangeSnapshot")).isEqualTo(Map.of(1L, 200L));
    assertThat(saved.get("lastRelationSnapshot")).isEqualTo(Map.of(1L, REL + 1));
  }

  /** 자기 출력 데이터셋이라도 행 수가 바뀌면 예전처럼 발화한다. */
  @Test
  void ownOutputDataset_rowCountChange_fires() {
    when(triggerRepository.findOutputDatasetIds(20L)).thenReturn(Set.of(1L));

    runRound(state(100), 4L, REL, 200L);
    verify(triggerService, times(1)).fireTrigger(eq(TRIGGER_ID), anyMap());
  }
}
