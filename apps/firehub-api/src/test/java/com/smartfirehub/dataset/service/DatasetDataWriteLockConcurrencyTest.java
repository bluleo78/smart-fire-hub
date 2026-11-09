package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import com.smartfirehub.dataset.dto.AddColumnRequest;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.dto.SqlQueryRequest;
import com.smartfirehub.dataset.dto.SqlQueryResponse;
import com.smartfirehub.dataset.dto.UpdateDatasetRequest;
import com.smartfirehub.dataset.exception.ColumnModificationException;
import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import com.smartfirehub.pipeline.service.LocalPythonOutputLoader;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TestUsers;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * WD-666 — 데이터 탭 밖의 쓰기(파이프라인 적재·사용자 SQL DML)도 공용 쓰기 잠금({@link DatasetDataWriteLock})을 거쳐 구조 변경(NOT
 * NULL 필드 추가·삭제·truncate)과 줄 서는지, 그리고 그 잠금이 데이터셋 정보 수정(이름·설명)을 막지 않는지 확인한다.
 *
 * <p>타이밍에 기대지 않도록 쓰기 쪽을 {@link DataTableRowService} 스파이로 쓰기 직전·직후에 세워 두고(래치), 그동안 상대 작업을 보낸다. 클래스
 * 레벨 {@code @Transactional} 을 쓰지 않는다 — 각 작업이 실제처럼 자기 트랜잭션으로 커밋해야 서로를 기다린다.
 */
class DatasetDataWriteLockConcurrencyTest extends IntegrationTestBase {

  @Autowired private DatasetService datasetService;
  @Autowired private DatasetDataService datasetDataService;
  @Autowired private LocalPythonOutputLoader loader;
  @Autowired private DatasetDataWriteLock writeLock;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;

  /** 쓰기를 세워 두는 스파이 — 테스트가 고른 테이블의 insertBatch 만 세운다. */
  @MockitoSpyBean private DataTableRowService dataTableRowService;

  private Long userId;
  private final List<Long> createdDatasetIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    // 트랜잭션 롤백이 없어 테스트마다 사용자가 남는다 — 이름이 겹치지 않게 시각을 붙인다.
    String suffix = Long.toString(System.nanoTime());
    userId =
        TestUsers.createMember(
                dsl,
                fixtureTransactionTemplate,
                encoder,
                "wd666_" + suffix,
                "wd666_" + suffix + "@example.com",
                "Password123",
                "WD666",
                DEFAULT_TEST_TENANT_ID)
            .id();
  }

  @AfterEach
  void tearDown() {
    for (Long id : createdDatasetIds) {
      try {
        inTenantFixture(() -> datasetService.deleteDataset(id));
      } catch (DatasetNotFoundException ignored) {
        // 삭제 시나리오에서 이미 지워진 데이터셋
      }
    }
  }

  /** NULL 허용 TEXT 필드 a 하나를 가진 빈 데이터셋을 만들고 테이블 이름을 돌려준다. */
  private DatasetDetailResponse createDataset() {
    String table = "wd666_" + System.nanoTime();
    DatasetDetailResponse ds =
        inTenantFixture(
            () ->
                datasetService.createDataset(
                    new CreateDatasetRequest(
                        "WD666 " + table,
                        table,
                        null,
                        null,
                        "TABLE",
                        "SOURCE",
                        List.of(
                            new DatasetColumnRequest("a", "A", "TEXT", null, true, false, null)),
                        null),
                    userId));
    createdDatasetIds.add(ds.id());
    return ds;
  }

  /**
   * 그 테이블의 insertBatch(4인자 — 파이프라인 Python 적재 경로)를 세운다. {@code afterWrite} 면 실제 삽입 뒤(커밋 전)에, 아니면 삽입
   * 전에 {@code reached} 를 내리고 {@code release} 를 기다린다.
   */
  private void pauseInsertBatch(
      String table, boolean afterWrite, CountDownLatch reached, CountDownLatch release) {
    doAnswer(
            inv -> {
              if (!afterWrite) {
                reached.countDown();
                release.await(20, TimeUnit.SECONDS);
              }
              Object result = inv.callRealMethod();
              if (afterWrite) {
                reached.countDown();
                release.await(20, TimeUnit.SECONDS);
              }
              return result;
            })
        .when(dataTableRowService)
        .insertBatch(eq(table), anyList(), anyList(), any(Map.class));
  }

  /** 테넌트·인증 컨텍스트를 세운 채 다른 스레드에서 실행한다(프로덕션 호출은 스스로 트랜잭션을 연다). */
  private <T> CompletableFuture<T> async(Supplier<T> work) {
    return CompletableFuture.supplyAsync(
        () -> {
          SecurityContextHolder.getContext()
              .setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
          try {
            return TenantContext.runScopedGet(DEFAULT_TEST_TENANT_ID, work);
          } finally {
            SecurityContextHolder.clearContext();
          }
        });
  }

  /** 예외를 값으로 받는다(성공이면 null). */
  private static Throwable failureOf(CompletableFuture<?> f) throws Exception {
    try {
      f.get(60, TimeUnit.SECONDS);
      return null;
    } catch (java.util.concurrent.ExecutionException e) {
      return e.getCause();
    }
  }

  /** 예외 사슬 전체 문구 — 원인(SQL 오류)이 감싼 예외 안에 들어 있어도 보이게 한다. */
  private static String chain(Throwable t) {
    StringBuilder sb = new StringBuilder();
    for (Throwable c = t; c != null; c = c.getCause()) {
      sb.append(c.getClass().getSimpleName()).append(": ").append(c.getMessage()).append(" | ");
    }
    return sb.toString();
  }

  /**
   * 파이프라인 적재(삽입했지만 아직 커밋 전)와 NOT NULL 필드 추가가 겹치면, 필드 추가는 적재가 끝날 때까지 기다렸다가 「데이터가 있는 데이터셋에는 NULL 허용을
   * 끈 필드…」(400)로 거절돼야 한다. 예전에는 필드 추가가 커밋 전 행을 못 보고 「행 없음」으로 통과한 뒤 ALTER 가 무결성 오류로 깨졌다.
   */
  @Test
  void 파이프라인_적재_중_NOT_NULL_필드_추가는_무결성_오류가_아니라_400_으로_끝난다() throws Exception {
    DatasetDetailResponse ds = createDataset();
    CountDownLatch reached = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    pauseInsertBatch(ds.tableName(), true, reached, release);

    CompletableFuture<Long> load =
        async(() -> loader.load(ds.tableName(), "[{\"a\":\"x\"}]", Map.of("a", "TEXT")));
    assertThat(reached.await(20, TimeUnit.SECONDS)).as("적재가 삽입 지점에 도달").isTrue();

    CompletableFuture<Object> addColumn =
        async(
            () ->
                datasetService.addColumn(
                    ds.id(),
                    new AddColumnRequest("n", "N", "TEXT", null, false, false, null),
                    userId));
    // 필드 추가가 행 수 검사·ALTER 까지 나아갈 시간을 준 뒤 적재를 놓아준다.
    Thread.sleep(1500);
    release.countDown();

    assertThat(failureOf(load)).as("적재는 성공").isNull();
    Throwable columnFailure = failureOf(addColumn);
    assertThat(columnFailure)
        .as("필드 추가 결과: %s", chain(columnFailure))
        .isInstanceOf(ColumnModificationException.class)
        .hasMessageStartingWith("데이터가 있는 데이터셋에는 NULL 허용을 끈 필드");
  }

  /**
   * 파이프라인 적재가 출력 테이블에 쓰기 직전에 데이터셋 삭제가 끼어들어도 「테이블 없음」으로 깨지지 않아야 한다 — 삭제가 적재 뒤로 줄 서서 둘 다 성공한다. 예전에는
   * 삭제가 테이블을 DROP 하고 커밋한 뒤 적재가 사라진 테이블에 INSERT 했다.
   */
  @Test
  void 파이프라인_적재_중_데이터셋_삭제가_겹쳐도_테이블_없음으로_깨지지_않는다() throws Exception {
    DatasetDetailResponse ds = createDataset();
    CountDownLatch reached = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    pauseInsertBatch(ds.tableName(), false, reached, release);

    CompletableFuture<Long> load =
        async(() -> loader.load(ds.tableName(), "[{\"a\":\"x\"}]", Map.of("a", "TEXT")));
    assertThat(reached.await(20, TimeUnit.SECONDS)).as("적재가 삽입 직전에 도달").isTrue();

    CompletableFuture<Boolean> delete =
        async(
            () -> {
              datasetService.deleteDataset(ds.id());
              return true;
            });
    // 삭제가 끝나거나(예전 동작) 잠금에서 기다리는(고친 동작) 동안 적재를 놓아준다.
    try {
      delete.get(1500, TimeUnit.MILLISECONDS);
    } catch (TimeoutException expectedAfterFix) {
      // 고친 동작: 삭제는 적재가 쥔 잠금 뒤에서 기다린다.
    }
    release.countDown();

    Throwable loadFailure = failureOf(load);
    assertThat(loadFailure).as("적재 결과: %s", chain(loadFailure)).isNull();
    assertThat(failureOf(delete)).as("삭제는 성공").isNull();
  }

  /** 데이터 쓰기(파이프라인 적재)가 쓰기 잠금을 쥔 동안에도 같은 데이터셋의 이름·설명 수정은 기다리지 않고 끝나야 한다 — 긴 적재가 정보 수정을 붙잡으면 안 된다. */
  @Test
  void 데이터_쓰기가_진행_중이어도_데이터셋_정보_수정은_막히지_않는다() throws Exception {
    DatasetDetailResponse ds = createDataset();
    CountDownLatch reached = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    pauseInsertBatch(ds.tableName(), true, reached, release);

    CompletableFuture<Long> load =
        async(() -> loader.load(ds.tableName(), "[{\"a\":\"x\"}]", Map.of("a", "TEXT")));
    assertThat(reached.await(20, TimeUnit.SECONDS)).as("적재가 쓰기 잠금을 쥔 채 멈춤").isTrue();

    CompletableFuture<Boolean> rename =
        async(
            () -> {
              datasetService.updateDataset(
                  ds.id(), new UpdateDatasetRequest("WD666 새 이름 " + ds.id(), "새 설명", null), userId);
              return true;
            });
    boolean finishedWhileWriting;
    try {
      rename.get(10, TimeUnit.SECONDS);
      finishedWhileWriting = true;
    } catch (TimeoutException e) {
      finishedWhileWriting = false;
    } finally {
      release.countDown();
    }

    assertThat(failureOf(load)).isNull();
    assertThat(failureOf(rename)).isNull();
    assertThat(finishedWhileWriting).as("정보 수정이 데이터 쓰기 잠금에 막히지 않아야 한다").isTrue();
  }

  /**
   * 데이터 탭 행 추가가 쓰기 잠금을 쥔 동안에도 같은 데이터셋의 이름·설명 수정은 기다리지 않는다. 예전에는 행 쓰기가 데이터셋 행을 FOR SHARE 로 쥐어, 정보
   * 수정의 FOR UPDATE·UPDATE 가 행 쓰기 커밋까지 기다렸다.
   */
  @Test
  void 데이터_탭_행_추가가_진행_중이어도_데이터셋_정보_수정은_막히지_않는다() throws Exception {
    DatasetDetailResponse ds = createDataset();
    CountDownLatch reached = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(
            inv -> {
              Object result = inv.callRealMethod();
              reached.countDown();
              release.await(20, TimeUnit.SECONDS);
              return result;
            })
        .when(dataTableRowService)
        .insertRow(eq(ds.tableName()), anyList(), any(Map.class), any());

    CompletableFuture<Object> addRow =
        async(
            () ->
                datasetDataService.addRow(
                    ds.id(),
                    new com.smartfirehub.dataset.dto.RowDataRequest(Map.of("a", "x")),
                    userId));
    assertThat(reached.await(20, TimeUnit.SECONDS)).as("행 추가가 쓰기 잠금을 쥔 채 멈춤").isTrue();

    CompletableFuture<Boolean> rename =
        async(
            () -> {
              datasetService.updateDataset(
                  ds.id(), new UpdateDatasetRequest("WD666 행추가중 " + ds.id(), "설명", null), userId);
              return true;
            });
    boolean finishedWhileWriting;
    try {
      rename.get(10, TimeUnit.SECONDS);
      finishedWhileWriting = true;
    } catch (TimeoutException e) {
      finishedWhileWriting = false;
    } finally {
      release.countDown();
    }

    assertThat(failureOf(addRow)).isNull();
    assertThat(failureOf(rename)).isNull();
    assertThat(finishedWhileWriting).as("정보 수정이 행 쓰기 잠금에 막히지 않아야 한다").isTrue();
  }

  /**
   * 사용자 SQL DML(/query 의 UPDATE)도 쓰기 잠금을 거친다 — 구조 변경 잠금이 잡혀 있으면 그 커밋까지 기다린다. 예전에는 잠금 없이 바로 실행돼
   * truncate·NOT NULL 필드 추가와 겹칠 수 있었다.
   */
  @Test
  void 사용자_SQL_DML_은_구조_변경_잠금이_풀릴_때까지_기다린다() throws Exception {
    DatasetDetailResponse ds = createDataset();
    CountDownLatch held = new CountDownLatch(1);

    // 구조 변경 쪽: 구조 변경 잠금을 쥔 트랜잭션 안에서, 이 세션에 막혀 데이터셋 행 쓰기 잠금 문장(FOR KEY SHARE)에서 기다리는 세션이
    // 생기는지 직접 본다. 시간 초과가 아니라 실제 잠금 대기(pg_blocking_pids)를 보고, 문장까지 맞춘다 — 잠금이 없으면 DML 은
    // UPDATE 를 먼저 실행하고 질의 이력 INSERT 의 FK 확인에서야 막히므로 대기는 생겨도 이 문장이 아니다.
    // 같은 연결로 확인해 작은 테스트 풀에서 연결을 더 쓰지 않는다.
    CompletableFuture<Boolean> structure =
        async(
            () ->
                transactionTemplate.execute(
                    status -> {
                      writeLock.lockForStructureChange(ds.id());
                      held.countDown();
                      return awaitWaiterBlockedByThisSession();
                    }));
    assertThat(held.await(20, TimeUnit.SECONDS)).isTrue();

    CompletableFuture<SqlQueryResponse> dml =
        async(
            () ->
                datasetDataService.executeQuery(
                    ds.id(),
                    new SqlQueryRequest(
                        "UPDATE data." + ds.tableName() + " SET a = 'y' WHERE a = 'none'", 100),
                    userId));

    assertThat(failureOf(structure)).isNull();
    Throwable dmlFailure = failureOf(dml);
    assertThat(dmlFailure).as("DML 결과: %s", chain(dmlFailure)).isNull();
    assertThat(dml.get().error()).isNull();
    assertThat(structure.get()).as("DML 은 구조 변경 잠금 뒤로 줄 서야 한다").isTrue();
  }

  /** 현재 세션이 쥔 잠금에 막혀 FOR KEY SHARE 문장에서 기다리는 세션이 생길 때까지(최대 10초) 기다린다 — 생기면 true. */
  private boolean awaitWaiterBlockedByThisSession() {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      // 세션 상태는 트랜잭션 동안 캐시되므로 매번 비워 실시간 상태를 읽는다.
      dsl.execute("SELECT pg_stat_clear_snapshot()");
      Object waiters =
          dsl.fetchValue(
              "SELECT count(*)::int FROM pg_stat_activity WHERE query ILIKE '%for key share%'"
                  + " AND pg_backend_pid() = ANY(pg_blocking_pids(pid))");
      if (waiters instanceof Number n && n.intValue() > 0) {
        return true;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return false;
  }

  /** 출력 데이터셋이 이미 지워졌으면 파이프라인 적재는 원문 SQL 오류가 아니라 「출력 데이터셋 없음」 안내로 실패한다. */
  @Test
  void 지워진_출력_데이터셋에_적재하면_데이터셋_없음_안내로_실패한다() throws Exception {
    DatasetDetailResponse ds = createDataset();
    inTenantFixture(() -> datasetService.deleteDataset(ds.id()));

    Throwable failure =
        failureOf(async(() -> loader.load(ds.tableName(), "[{\"a\":\"x\"}]", Map.of("a", "TEXT"))));

    assertThat(failure)
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageContaining("출력 데이터셋");
  }
}
