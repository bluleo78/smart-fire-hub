package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.analytics.service.AnalyticsQueryConcurrencyGate;
import com.smartfirehub.dataset.dto.DatasetResponse;
import com.smartfirehub.dataset.dto.SqlQueryRequest;
import com.smartfirehub.dataset.dto.SqlQueryResponse;
import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.dataset.exception.SqlQueryException;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.repository.QueryHistoryRepository;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.pipeline.service.PipelineDataSchema;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.sql.GuardedSqlExecutor;
import com.smartfirehub.support.TxRecordingInteractiveGate;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 데이터셋 SQL 편집기(POST /datasets/{id}/query)가 화면 쿼리 허가를 「실제로 쿼리를 실행한다」가 정해진 뒤에만 얻는지(WD-638, 차트·대시보드의
 * WD-625 와 같은 규칙).
 *
 * <p>게이트를 다른 사용자가 꽉 채운 상태(전체 상한 1 을 사용자 99 가 쥠)에서, 실행하지 않고 끝나는 요청 — 없는 데이터셋(404)·숨김 데이터셋을 참조해 거부되는
 * SQL(403)·정규화에 실패하는 SQL(400) — 은 허가를 얻으려 하지도 않고 곧바로 끝나야 한다. 진입부에서 허가를 얻던 코드는 대기 시간 동안 줄 서다 429 를
 * 받는다. 실행이 필요한 SQL 은 지금처럼 기다렸다 429 다.
 *
 * <p>스프링 없이 서비스를 직접 만든다(PG 경로, 가짜 트랜잭션 매니저). 트랜잭션 구간은 DatasetDataServiceTxTest 가 본다.
 */
class DatasetQueryPermitPlacementTest {

  /** 빠른 경로 테스트의 대기 시간 — 허가를 얻으려 했다면 결과 대신 이만큼 기다린 뒤 429 가 된다. 판정은 결과·acquire 호출 수로 한다. */
  private static final long LONG_WAIT_MS = 5_000;

  /** 실행이 필요한 요청이 여전히 기다렸다 429 인지 볼 때의 짧은 대기 시간. */
  private static final long SHORT_WAIT_MS = 300;

  /** 게이트를 채우고 있는 다른 사용자. */
  private static final long OTHER_USER = 99L;

  /** 요청 사용자. */
  private static final long USER = 7L;

  private static final SqlQueryRequest REQ = new SqlQueryRequest("SELECT 1", 100);

  private static final SqlQueryResponse OK =
      new SqlQueryResponse("SELECT", List.of(), List.of(), 0, 1L, null);

  /**
   * 테스트 대상과 검증에 쓰는 대역 묶음. judgment 는 허가 전 앞 판정이, rejudged 는 실행 트랜잭션 안 재판정이 돌려주는 토큰 — 실행에는 재판정 토큰이
   * 그대로 넘어가야 한다(판정 = 실행).
   */
  private record Fixture(
      DatasetDataService service,
      DatasetRepository datasets,
      GuardedSqlExecutor executor,
      QueryHistoryRepository history,
      GuardedSqlExecutor.DatasetQueryJudgment judgment,
      GuardedSqlExecutor.DatasetQueryJudgment rejudged) {}

  /** PG 테넌트·데이터셋 있음·판정 허용·실행 성공으로 기본 설정한 서비스. */
  private static Fixture fixture(TxRecordingInteractiveGate gate) {
    DatasetRepository datasets = mock(DatasetRepository.class);
    when(datasets.findById(anyLong())).thenReturn(Optional.of(mock(DatasetResponse.class)));
    ClearanceResolver resolver = mock(ClearanceResolver.class);
    when(resolver.current()).thenReturn(mock(Clearance.class));
    GuardedSqlExecutor executor = mock(GuardedSqlExecutor.class);
    GuardedSqlExecutor.DatasetQueryJudgment judgment =
        mock(GuardedSqlExecutor.DatasetQueryJudgment.class);
    GuardedSqlExecutor.DatasetQueryJudgment rejudged =
        mock(GuardedSqlExecutor.DatasetQueryJudgment.class);
    when(executor.judgeDatasetQuery(any(), anyString())).thenReturn(judgment, rejudged);
    when(executor.executeJudgedDatasetQuery(any(), anyInt(), any())).thenReturn(OK);
    QueryHistoryRepository history = mock(QueryHistoryRepository.class);
    PipelineDataSchema schema = mock(PipelineDataSchema.class);
    when(schema.isTibero()).thenReturn(false);
    DatasetDataService service =
        new DatasetDataService(
            datasets,
            mock(DatasetColumnRepository.class),
            mock(DataTableService.class),
            mock(DataTableRowService.class),
            executor,
            resolver,
            history,
            mock(DatasetDataWriteLock.class),
            // 가짜 트랜잭션 매니저 위의 쓰기 템플릿 — 구간 자체는 이 테스트의 관심사가 아니다.
            new TransactionTemplate(mock(PlatformTransactionManager.class)),
            schema,
            mock(DatasetAuditRecorder.class),
            gate);
    return new Fixture(service, datasets, executor, history, judgment, rejudged);
  }

  /** 전체 상한 1·사용자 상한 3 의 기록 게이트. */
  private static TxRecordingInteractiveGate gate(long waitMs) {
    return new TxRecordingInteractiveGate(1, 3, waitMs);
  }

  /**
   * 자리가 비었는지 — 게이트의 진단용 메서드는 패키지 가시라 다른 패키지에서 부를 수 없어, 다른 사용자로 허가를 하나 얻었다 돌려준다(전체 상한 1 이라 누가 허가를 쥐고
   * 있으면 대기 뒤 429 로 실패한다). acquire 기록이 하나 늘므로 테스트 마지막에만 부른다.
   */
  private static void assertSlotFree(TxRecordingInteractiveGate gate) {
    try (AnalyticsQueryConcurrencyGate.Permit probe = gate.acquire(OTHER_USER)) {
      assertThat(probe).isNotNull();
    }
  }

  /** 빠른 경로 확인 — before 이후 허가를 얻으려 하지 않았다(acquire 호출 수 불변). */
  private static void assertNoPermitAttempt(TxRecordingInteractiveGate gate, int before) {
    assertThat(gate.acquireTx).as("허가를 얻으려 하지 않는다").hasSize(before);
  }

  /** 실행·이력 저장이 한 번도 일어나지 않았다. */
  private static void assertNotExecuted(Fixture f) {
    verify(f.executor(), never()).executeJudgedDatasetQuery(any(), anyInt(), any());
    verify(f.history(), never())
        .save(anyLong(), anyLong(), anyString(), any(), anyInt(), anyLong(), anyBoolean(), any());
  }

  /** 없는 데이터셋 — 게이트가 차 있어도 허가 없이 곧바로 404, 판정도 하지 않는다. */
  @Test
  void missingDataset_gateFull_404WithoutPermit() {
    TxRecordingInteractiveGate gate = gate(LONG_WAIT_MS);
    Fixture f = fixture(gate);
    when(f.datasets().findById(anyLong())).thenReturn(Optional.empty());
    try (AnalyticsQueryConcurrencyGate.Permit held = gate.acquire(OTHER_USER)) {
      int before = gate.acquireTx.size();

      Throwable e = catchThrowable(() -> f.service().executeQuery(404L, REQ, USER));

      assertThat(e).isInstanceOf(DatasetNotFoundException.class);
      assertNoPermitAttempt(gate, before);
    }
    verify(f.executor(), never()).judgeDatasetQuery(any(), anyString());
    assertNotExecuted(f);
  }

  /** 숨김 데이터셋을 참조해 거부되는 SQL — 허가 없이 곧바로 403, 실행·이력 없음. */
  @Test
  void deniedSql_gateFull_403WithoutPermit() {
    TxRecordingInteractiveGate gate = gate(LONG_WAIT_MS);
    Fixture f = fixture(gate);
    when(f.executor().judgeDatasetQuery(any(), anyString()))
        .thenThrow(new CodedApiException(HttpStatus.FORBIDDEN, "DATASET_NOT_VISIBLE", "거부"));
    try (AnalyticsQueryConcurrencyGate.Permit held = gate.acquire(OTHER_USER)) {
      int before = gate.acquireTx.size();

      Throwable e = catchThrowable(() -> f.service().executeQuery(1L, REQ, USER));

      assertThat(e).isInstanceOf(CodedApiException.class);
      assertThat(((CodedApiException) e).status()).isEqualTo(HttpStatus.FORBIDDEN);
      assertNoPermitAttempt(gate, before);
    }
    assertNotExecuted(f);
  }

  /** 정규화·파싱에 실패하는 SQL — 실행할 것이 없으므로 허가 없이 곧바로 400. */
  @Test
  void malformedSql_gateFull_400WithoutPermit() {
    TxRecordingInteractiveGate gate = gate(LONG_WAIT_MS);
    Fixture f = fixture(gate);
    when(f.executor().judgeDatasetQuery(any(), anyString()))
        .thenThrow(new SqlQueryException("잘못된 SQL"));
    try (AnalyticsQueryConcurrencyGate.Permit held = gate.acquire(OTHER_USER)) {
      int before = gate.acquireTx.size();

      Throwable e = catchThrowable(() -> f.service().executeQuery(1L, REQ, USER));

      assertThat(e).isInstanceOf(SqlQueryException.class);
      assertNoPermitAttempt(gate, before);
    }
    assertNotExecuted(f);
  }

  /** 실행이 필요한 SQL — 지금처럼 차례를 기다렸다 429, 실행·이력 저장은 하지 않는다. */
  @Test
  void executableSql_gateFull_stillWaitsThen429() {
    TxRecordingInteractiveGate gate = gate(SHORT_WAIT_MS);
    Fixture f = fixture(gate);
    try (AnalyticsQueryConcurrencyGate.Permit held = gate.acquire(OTHER_USER)) {
      Throwable e = catchThrowable(() -> f.service().executeQuery(1L, REQ, USER));

      assertThat(e).isInstanceOf(CodedApiException.class);
      CodedApiException c = (CodedApiException) e;
      assertThat(c.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
      assertThat(c.code()).isEqualTo(AnalyticsQueryConcurrencyGate.LIMIT_CODE);
    }
    assertNotExecuted(f);
    assertSlotFree(gate);
  }

  /** 실행이 필요한 SQL·자리 있음 — 트랜잭션 밖에서 허가 하나를 얻고, 판정이 돌려준 바로 그 토큰을 실행하며(판정 = 실행), 이력을 남기고 허가를 반납한다. */
  @Test
  void executableSql_gateFree_executesJudgedTokenAndReleases() {
    TxRecordingInteractiveGate gate = gate(LONG_WAIT_MS);
    Fixture f = fixture(gate);

    SqlQueryResponse r = f.service().executeQuery(1L, REQ, USER);

    assertThat(r).isEqualTo(OK);
    assertThat(gate.acquireTx).containsExactly(false);
    // 허가 전 앞 판정 + 실행 트랜잭션 안 재판정(WD-638 리뷰) — 실행되는 것은 재판정 토큰이다.
    verify(f.executor(), times(2)).judgeDatasetQuery(any(), eq("SELECT 1"));
    verify(f.executor(), times(1)).executeJudgedDatasetQuery(same(f.rejudged()), eq(100), any());
    verify(f.executor(), never()).executeJudgedDatasetQuery(same(f.judgment()), anyInt(), any());
    verify(f.history(), times(1))
        .save(anyLong(), anyLong(), anyString(), any(), anyInt(), anyLong(), anyBoolean(), any());
    assertSlotFree(gate);
  }

  /**
   * 허가를 기다리는 동안 보안 등급이 올라가거나 테이블 매핑이 바뀌어 판정이 거부로 바뀌었다 — 앞 판정(허용)으로 실행하지 않고, 실행 직전 재판정이 403 으로 끝내며
   * 실행·이력 저장이 없다(WD-638 리뷰: 대기 전 판정으로 실행하면 낡은 판정이 된다).
   */
  @Test
  void verdictTurnsDeniedWhileWaiting_403AndNotExecuted() throws Exception {
    TxRecordingInteractiveGate gate = gate(LONG_WAIT_MS);
    Fixture f = fixture(gate);
    // 앞 판정은 허용, 허가를 얻은 뒤의 재판정은 거부(대기 중 관리자 변경을 흉내 낸다).
    when(f.executor().judgeDatasetQuery(any(), anyString()))
        .thenReturn(f.judgment())
        .thenThrow(new CodedApiException(HttpStatus.FORBIDDEN, "DATASET_NOT_VISIBLE", "거부"));
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<Throwable> result;
      try (AnalyticsQueryConcurrencyGate.Permit held = gate.acquire(OTHER_USER)) {
        int before = gate.acquireTx.size();
        result = pool.submit(() -> catchThrowable(() -> f.service().executeQuery(1L, REQ, USER)));
        // 요청이 앞 판정을 마치고 허가 대기에 들어갈 때까지 기다린 뒤, 쥐고 있던 허가를 돌려준다.
        await().atMost(Duration.ofSeconds(3)).until(() -> gate.acquireTx.size() > before);
      }
      Throwable e = result.get(LONG_WAIT_MS * 2, TimeUnit.MILLISECONDS);

      assertThat(e).isInstanceOf(CodedApiException.class);
      assertThat(((CodedApiException) e).status()).isEqualTo(HttpStatus.FORBIDDEN);
    } finally {
      pool.shutdownNow();
    }
    verify(f.executor(), times(2)).judgeDatasetQuery(any(), anyString());
    assertNotExecuted(f);
    assertSlotFree(gate);
  }
}
