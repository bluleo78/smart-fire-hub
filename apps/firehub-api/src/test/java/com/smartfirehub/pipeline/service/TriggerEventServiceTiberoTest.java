package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
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
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DATASET_CHANGE 폴러의 행 수 판정 규칙(WD-80)을 Mockito 로 검증한다.
 *
 * <p>세기에 실패한 데이터셋은 "변경"도 "삭제"도 아니다 — 트리거를 발동하지 않고, 직전 값을 이어 받으며, 트리거를 끄지 않는다. 자동 비활성은 데이터셋이 실제로
 * 지워졌을 때만이다. 폴러는 예외를 삼키고 로그만 남기므로(한 트리거 실패가 다른 트리거를 막지 않게), 모든 경우에 저장된 상태까지 확인해 "예외로 중단돼서 발동하지 않은
 * 것"과 구분한다. 트랜잭션 구간(Tibero 는 밖에서 센다)은 실제 트랜잭션 매니저가 필요해 {@code TriggerEventServiceRowCountTxTest} 가
 * 따로 본다.
 */
class TriggerEventServiceTiberoTest {

  private static final long TRIGGER_ID = 10L;

  private TriggerRepository triggerRepository;
  private TriggerService triggerService;
  private DatasetRepository datasetRepository;
  private DataRowStore rowStore;
  private TransactionTemplate tx;
  private TriggerEventService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    triggerRepository = mock(TriggerRepository.class);
    triggerService = mock(TriggerService.class);
    datasetRepository = mock(DatasetRepository.class);
    rowStore = mock(DataRowStore.class);
    DataStoreResolver resolver = mock(DataStoreResolver.class);
    when(resolver.currentBackend()).thenReturn(DataStoreBackend.TIBERO);
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
    // 트랜잭션 템플릿은 콜백을 바로 실행한다(구간 검증은 별도 클래스).
    tx = mock(TransactionTemplate.class);
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

  /** 폴링 간격·디바운스를 0 으로 둔 DATASET_CHANGE 트리거. state 는 DB(jsonb)에서 읽은 모양(문자열 키)이다. */
  private static TriggerResponse trigger(List<Integer> datasetIds, Map<String, Object> state) {
    return new TriggerResponse(
        TRIGGER_ID,
        20L,
        "DATASET_CHANGE",
        "변경 감지",
        null,
        true,
        Map.of("datasetIds", datasetIds, "pollingIntervalSeconds", 0, "debounceSeconds", 0),
        state,
        null,
        1L,
        LocalDateTime.now());
  }

  /** jsonb 에서 읽은 것처럼 lastSnapshot 만 담은 상태(키는 문자열). */
  private static Map<String, Object> stateWithLast(Map<String, Object> lastSnapshot) {
    Map<String, Object> state = new HashMap<>();
    state.put("lastSnapshot", lastSnapshot);
    return state;
  }

  /** 한 회차를 돌리고 저장된 상태를 돌려준다. 상태 저장이 없으면(예외로 중단) 단언이 실패한다. */
  @SuppressWarnings("unchecked")
  private Map<String, Object> runRound(TriggerResponse trigger) {
    when(triggerRepository.findEnabledByType("DATASET_CHANGE")).thenReturn(List.of(trigger));
    service.pollDatasetChanges();
    ArgumentCaptor<Map<String, Object>> saved = ArgumentCaptor.forClass(Map.class);
    verify(triggerRepository).updateTriggerState(eq(TRIGGER_ID), saved.capture());
    Map<String, Object> state = saved.getValue();
    assertThat(state).containsKey("lastCheckedAt");
    return state;
  }

  /** 저장된 상태를 jsonb 를 거친 모양(문자열 키·정수 값)으로 바꾼다 — 다음 회차 입력용. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> asStored(Map<String, Object> saved) {
    Map<String, Object> last = new HashMap<>();
    ((Map<Object, Object>) saved.get("lastSnapshot"))
        .forEach((k, v) -> last.put(String.valueOf(k), ((Number) v).intValue()));
    Map<String, Object> state = stateWithLast(last);
    return state;
  }

  @Test
  void tibero_rowAdded_fires() {
    when(datasetRepository.findTableNameById(1L)).thenReturn(Optional.of("t1"));
    when(rowStore.rowCounts(any())).thenReturn(Map.of("t1", 4L));

    Map<String, Object> saved = runRound(trigger(List.of(1), stateWithLast(Map.of("1", 3))));

    verify(triggerService, times(1)).fireTrigger(eq(TRIGGER_ID), anyMap());
    assertThat(saved.get("lastSnapshot")).isEqualTo(Map.of(1L, 4L));
    verify(triggerRepository, never()).updateEnabled(anyLong(), anyBoolean());
  }

  @Test
  void tibero_unchanged_doesNotFire() {
    when(datasetRepository.findTableNameById(1L)).thenReturn(Optional.of("t1"));
    when(rowStore.rowCounts(any())).thenReturn(Map.of("t1", 3L));

    Map<String, Object> saved = runRound(trigger(List.of(1), stateWithLast(Map.of("1", 3))));

    verify(triggerService, never()).fireTrigger(anyLong(), anyMap());
    assertThat(saved.get("lastSnapshot")).isEqualTo(Map.of(1L, 3L));
  }

  /** 세기 실패는 변경이 아니다 — 발동하지 않고 직전 값을 이어 받으며 트리거를 끄지 않는다. */
  @Test
  void countFailure_doesNotFire_andCarriesLastValue() {
    when(datasetRepository.findTableNameById(1L)).thenReturn(Optional.of("t1"));
    when(rowStore.rowCounts(any())).thenReturn(Map.of());

    Map<String, Object> saved = runRound(trigger(List.of(1), stateWithLast(Map.of("1", 3))));

    verify(triggerService, never()).fireTrigger(anyLong(), anyMap());
    assertThat(saved.get("lastSnapshot")).isEqualTo(Map.of(1L, 3L));
    verify(triggerRepository, never()).updateEnabled(anyLong(), anyBoolean());
  }

  /** 모두 세기에 실패해 스냅샷이 비어도 데이터셋은 남아 있으므로 트리거를 끄지 않는다. */
  @Test
  void allCountsFail_doesNotDisable() {
    when(datasetRepository.findTableNameById(1L)).thenReturn(Optional.of("t1"));
    when(datasetRepository.findTableNameById(2L)).thenReturn(Optional.of("t2"));
    when(rowStore.rowCounts(any())).thenReturn(Map.of());

    Map<String, Object> saved = runRound(trigger(List.of(1, 2), new HashMap<>()));

    verify(triggerRepository, never()).updateEnabled(anyLong(), anyBoolean());
    // 세기 실패는 삭제가 아니다 — 감시 목록에서 빼지 않는다(WD-221 존재 판정은 dataset 행 기준).
    verify(triggerRepository, never()).removeWatchedDataset(anyLong());
    verify(triggerService, never()).fireTrigger(anyLong(), anyMap());
    assertThat(saved.get("lastSnapshot")).isEqualTo(Map.of());
  }

  /**
   * 감시 대상 데이터셋이 지워졌으면 감시 목록에서 뺀다(WD-221 — 하나도 남지 않으면 같은 리포지토리 문장이 트리거를 끈다). 삭제는 변경이 아니라 발동하지
   * 않는다.
   */
  @Test
  void allDeleted_removesFromWatchList() {
    when(datasetRepository.findTableNameById(anyLong())).thenReturn(Optional.empty());
    when(rowStore.rowCounts(any())).thenReturn(Map.of());

    Map<String, Object> saved = runRound(trigger(List.of(1, 2), stateWithLast(Map.of("1", 3))));

    verify(triggerRepository).removeWatchedDataset(1L);
    verify(triggerRepository).removeWatchedDataset(2L);
    verify(triggerService, never()).fireTrigger(anyLong(), anyMap());
    assertThat(saved.get("lastSnapshot")).isEqualTo(Map.of());
  }

  /**
   * 테이블 이름 조회 결과가 없으면(null) 판정할 수 없는 회차다 — PG 경로처럼 빈 회차로 보고, "모두 지워짐"으로 오판해 트리거를 끄지 않는다. 행 수도 세지
   * 않는다.
   */
  @Test
  void nullNameMap_isUndecidable_doesNotDisable() {
    // when(tx.execute(...)) 로 다시 스텁하면 기존 답이 null 콜백으로 불리므로 doReturn 을 쓴다.
    doReturn(null).when(tx).execute(any());

    Map<String, Object> saved = runRound(trigger(List.of(1, 2), stateWithLast(Map.of("1", 3))));

    verify(triggerRepository, never()).updateEnabled(anyLong(), anyBoolean());
    // 판정할 수 없는 회차를 "모두 삭제"로 보면 감시 목록이 비워져 트리거가 꺼진다 — 빼지 않아야 한다.
    verify(triggerRepository, never()).removeWatchedDataset(anyLong());
    verify(triggerService, never()).fireTrigger(anyLong(), anyMap());
    verify(rowStore, never()).rowCounts(any());
    assertThat(saved.get("lastSnapshot")).isEqualTo(Map.of(1L, 3L));
  }

  /** 실패 회차에 직전 값을 이어 받았으므로, 회복한 다음 회차에 같은 값이면 -1 과 비교돼 오발동하지 않는다. */
  @Test
  void recoveryAfterFailure_doesNotFire() {
    when(datasetRepository.findTableNameById(1L)).thenReturn(Optional.of("t1"));
    when(rowStore.rowCounts(any())).thenReturn(Map.of());
    Map<String, Object> first = runRound(trigger(List.of(1), stateWithLast(Map.of("1", 3))));
    assertThat(first.get("lastSnapshot")).isEqualTo(Map.of(1L, 3L));

    reset(triggerRepository);
    when(rowStore.rowCounts(any())).thenReturn(Map.of("t1", 3L));
    Map<String, Object> second = runRound(trigger(List.of(1), asStored(first)));

    verify(triggerService, never()).fireTrigger(anyLong(), anyMap());
    assertThat(second.get("lastSnapshot")).isEqualTo(Map.of(1L, 3L));
  }
}
