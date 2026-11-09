package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.dataset.service.DatasetDataWriteLock;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.datastore.DataStoreDb;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TestUsers;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 출력 잠금을 맞바꿈 트랜잭션 안으로 옮긴 {@link OutputSwapLock}(WD-690)의 계약 — 실제 DB 로 확인한다.
 *
 * <p>배경은 「데이터 저장소 풀 고갈」이다. 예전에는 로컬 PYTHON REPLACE 가 세션 잠금 커넥션 하나를 쥔 채로 맞바꿈 트랜잭션이 커넥션을 하나 더 빌렸다. 풀
 * 고갈 테스트가 증명하는 범위는 다음과 같다. <b>별칭 모드</b>(기본 {@code test}, 데이터 저장소 풀이 플랫폼 풀과 같아 상한 2, 스레드 4)에서는 옛 이중
 * 점유였다면 풀이 바닥나 막혔을 것이므로 그 회귀를 잡는다. <b>분리 모드</b>({@code separateDataStoreTest})에서는 플랫폼 풀(2)이 동시 실행을
 * 먼저 조절해 데이터 저장소 풀(상한 10)을 압박하지 못하므로 이 테스트는 풀 고갈을 증명하지 않는다 — 분리 모드의 데이터 저장소 연결 묶임은 {@code
 * SeparateDataStoreTransactionTest} 가 확인한다.
 *
 * <p>클래스 레벨 {@code @Transactional} 은 쓰지 않는다 — 다른 커넥션이 픽스처와 잠금 상태를 봐야 한다.
 */
class OutputSwapLockTest extends IntegrationTestBase {

  @Autowired private OutputSwapLock outputSwapLock;
  @Autowired private DatasetDataWriteLock datasetWriteLock;
  @Autowired private DataTableService dataTableService;
  @Autowired private DatasetService datasetService;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired @DataStoreDb private DataSource dataStoreDataSource;

  private Long userId;
  private final List<Long> createdDatasetIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    String suffix = Long.toString(System.nanoTime());
    userId =
        TestUsers.createMember(
                dsl,
                fixtureTransactionTemplate,
                encoder,
                "wd690_" + suffix,
                "wd690_" + suffix + "@example.com",
                "Password123",
                "WD690",
                DEFAULT_TEST_TENANT_ID)
            .id();
  }

  @AfterEach
  void tearDown() {
    for (Long id : createdDatasetIds) {
      try {
        inTenantFixture(() -> datasetService.deleteDataset(id));
      } catch (RuntimeException ignored) {
        // 이미 지워졌거나 정리 실패 — 다음 테스트에 영향이 없는 이름이라 무시한다.
      }
    }
  }

  /** 출력 데이터셋(행 + 물리 테이블)을 만들고 테이블 이름을 돌려준다. */
  private String createOutput() {
    String table = "wd690_" + System.nanoTime() + "_" + createdDatasetIds.size();
    var ds =
        inTenantFixture(
            () ->
                datasetService.createDataset(
                    new CreateDatasetRequest(
                        "WD690 " + table,
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
    return table;
  }

  private void inTenant(Runnable action) {
    TenantContext.runScoped(DEFAULT_TEST_TENANT_ID, action);
  }

  private <T> T inTenant(Supplier<T> action) {
    return TenantContext.runScopedGet(DEFAULT_TEST_TENANT_ID, action::get);
  }

  /** 출력의 잠금 키 — 프로덕션과 같은 식(SQL 경로와 같은 키)이며 테넌트 스키마를 포함하므로 테넌트 컨텍스트 안에서 만든다. */
  private String lockKey(String table) {
    return inTenant(() -> SqlScriptExecutor.outputLockKeyFor(table));
  }

  /**
   * 프로덕션과 같은 순서로 — 테넌트 컨텍스트에서 {@code writeToOutput} 트랜잭션을 열고 출력 잠금을 잡은 뒤 {@code inside} 를 실행한다.
   * {@code inside} 가 던지면 트랜잭션이 롤백된다.
   */
  private void swapUnderLock(String table, Runnable inside) {
    inTenant(
        () ->
            datasetWriteLock.writeToOutput(
                table,
                () -> {
                  outputSwapLock.lockInCurrentTransaction(table);
                  inside.run();
                }));
  }

  /** 다른 데이터 저장소 연결의 새 트랜잭션에서 출력 잠금을 시도한다 — 얻으면 true(트랜잭션은 바로 롤백해 푼다), 누가 쥐고 있으면 false. */
  private boolean tryLockFromOtherConnection(String key) throws SQLException {
    try (Connection c = dataStoreDataSource.getConnection()) {
      c.setAutoCommit(false);
      // 운영 잠금 문장에서 파생한다 — 손으로 쓴 복사본이 운영 문장과 어긋나 틀린 통과가 나지 않도록.
      String trySql =
          SqlScriptExecutor.OUTPUT_LOCK_SQL.replace(
              "pg_advisory_xact_lock", "pg_try_advisory_xact_lock");
      try (var ps = c.prepareStatement(trySql)) {
        ps.setString(1, key);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          return rs.getBoolean(1);
        }
      } finally {
        c.rollback();
      }
    }
  }

  /**
   * 풀 상한보다 2 많은 스레드가 서로 다른 출력의 맞바꿈에 동시에 들어가도 모두 끝난다. 잠금이 맞바꿈과 같은 트랜잭션·같은 커넥션이라 스레드당 커넥션 하나만 쥐므로 풀이
   * 모자라도 줄만 설 뿐이다. 별칭 모드(풀 2, 스레드 4)에서는 옛 이중 점유를 잡아내지만, 분리 모드에서는 플랫폼 풀이 동시성을 조절해 데이터 저장소 풀을 압박하지
   * 못한다(클래스 Javadoc 참조).
   */
  @Test
  void 풀_상한보다_많은_맞바꿈이_동시에_들어와도_서로_막지_않는다() throws Exception {
    int poolMax = ((HikariDataSource) dataStoreDataSource).getMaximumPoolSize();
    int threads = poolMax + 2;
    List<String> outputs = new ArrayList<>();
    List<String> temps = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      String out = createOutput();
      outputs.add(out);
      temps.add(inTenant(() -> dataTableService.createTempTable(out)));
    }

    CyclicBarrier together = new CyclicBarrier(threads);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        String out = outputs.get(i);
        String temp = temps.get(i);
        futures.add(
            pool.submit(
                () -> {
                  together.await(30, TimeUnit.SECONDS);
                  swapUnderLock(out, () -> dataTableService.finishReplace(out, temp, 1));
                  return null;
                }));
      }
      for (Future<?> f : futures) {
        f.get(60, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void 같은_출력의_SQL_스텝_잠금은_맞바꿈_트랜잭션이_끝날_때까지_기다린다() throws Exception {
    String x = createOutput();
    String y = createOutput();
    String xKey = lockKey(x);
    String yKey = lockKey(y);
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<?> holder =
          pool.submit(
              () ->
                  swapUnderLock(
                      x,
                      () -> {
                        locked.countDown();
                        try {
                          release.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(e);
                        }
                      }));
      assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

      assertThat(tryLockFromOtherConnection(xKey)).as("맞바꿈 트랜잭션이 X 를 쥐고 있다").isFalse();
      assertThat(tryLockFromOtherConnection(yKey)).as("다른 출력 Y 는 막히지 않는다").isTrue();

      release.countDown();
      holder.get(30, TimeUnit.SECONDS);
      assertThat(tryLockFromOtherConnection(xKey)).as("트랜잭션이 끝나면 X 도 풀린다").isTrue();
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void 맞바꿈이_실패해_롤백되면_잠금이_남지_않는다() throws Exception {
    String x = createOutput();
    String xKey = lockKey(x);

    assertThatThrownBy(
            () ->
                swapUnderLock(
                    x,
                    () -> {
                      throw new IllegalStateException("맞바꿈 실패");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("맞바꿈 실패");

    assertThat(tryLockFromOtherConnection(xKey)).isTrue();
  }

  @Test
  void 트랜잭션_밖에서는_잠그지_않는다() {
    assertThatThrownBy(() -> inTenant(() -> outputSwapLock.lockInCurrentTransaction("any_out")))
        .isInstanceOf(IllegalStateException.class);
  }

  /** 실제 트랜잭션 안이어도 트랜잭션 동기화가 꺼져 있으면(데이터 저장소 연결이 묶이지 않는다) 잠그지 않는다. */
  @Test
  void 트랜잭션_동기화가_꺼져_있으면_잠그지_않는다() {
    assertThatThrownBy(
            () ->
                inTenant(
                    () ->
                        fixtureTransactionTemplate.executeWithoutResult(
                            status -> {
                              TransactionSynchronizationManager.clearSynchronization();
                              try {
                                outputSwapLock.lockInCurrentTransaction("any_out");
                              } finally {
                                TransactionSynchronizationManager.initSynchronization();
                              }
                            })))
        .isInstanceOf(IllegalStateException.class);
  }
}
