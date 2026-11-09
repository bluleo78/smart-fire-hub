package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.global.platformdb.PlatformLocks;
import com.smartfirehub.global.platformdb.postgres.PostgresPlatformLocks;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.exception.PipelineExecutionCancelledException;
import com.smartfirehub.pipeline.service.executor.ExecutionCancelCheck;
import com.smartfirehub.support.IntegrationTestBase;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;

/**
 * 세션 범위 advisory 잠금 공통 장치({@link SessionAdvisoryLock})의 계약 — 실제 DB 로 확인한다. 지금 운영 호출자는 증분 스텝 잠금(플랫폼
 * 풀)뿐이다(WD-692, 출력 쪽 잠금은 WD-690 에서 트랜잭션 범위로 옮겼다). 세션 범위 advisory 잠금은 커밋으로 풀리지 않고 풀로 돌아간 커넥션에도 남으므로,
 * <b>누수가 없다는 것</b>이 이 클래스의 핵심 단언이다.
 *
 * <p>잠금 관측은 {@code pg_locks} 의 advisory 행으로 한다. 테스트 DB 는 이 JVM 전용 컨테이너이고 세션 범위 advisory 잠금을 쓰는 곳이 이
 * 클래스의 테스트뿐이라 다른 잠금이 섞이지 않는다. 클래스 레벨 {@code @Transactional} 은 쓰지 않는다 — 다른 커넥션이 픽스처를 봐야 한다.
 */
class SessionAdvisoryLockTest extends IntegrationTestBase {

  /**
   * 플랫폼 잠금 인터페이스와 플랫폼(@Primary) 풀. 운영의 증분 스텝 잠금은 같은 플랫폼 DB 의 작은 전용 잠금 풀을 쓰지만, 이 테스트는 @Primary 플랫폼
   * 풀을 빌린다 — DB 와 잠금 의미가 같다.
   */
  @Autowired private PlatformLocks platformLocks;

  @Autowired private DataSource dataSource;
  @Autowired private DSLContext dsl;
  @Autowired private IncrementalStepLock incrementalStepLock;
  @Autowired private DataSourceProperties dataSourceProperties;

  private static String randomKey(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
  }

  /** 플랫폼 DB 에 존재하는 advisory 잠금 수 — 증분 스텝 잠금(WD-223)은 플랫폼 DB 의 잠금 전용 풀에서 잡는다. */
  private int platformAdvisoryLocks(String condition) {
    return dsl.fetchOne(
            "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND " + condition)
        .get(0, Integer.class);
  }

  /**
   * 임의 키로 세션 잠금을 쥔 채 본문을 실행한다 — 공통 장치를 직접 부른다. 이 클래스가 검증하는 것은 그 뒤의 공통 장치({@link
   * SessionAdvisoryLock})의 획득·재시도·해제·누수 방지 계약이다.
   */
  private <T> T callLocked(DataSource pool, PlatformLocks locks, String key, Supplier<T> body) {
    try (SessionAdvisoryLock.Held ignored =
        SessionAdvisoryLock.acquire(
            locks,
            pool,
            key,
            "테스트 잠금",
            SessionAdvisoryLock.NO_LIMIT,
            ExecutionCancelCheck.NONE,
            () -> "테스트 잠금 대기 시간이 끝났습니다")) {
      return body.get();
    }
  }

  private <T> T inTenant(Supplier<T> action) {
    return TenantContext.runScopedGet(DEFAULT_TEST_TENANT_ID, action::get);
  }

  @Test
  void 본문이_도는_동안만_잠금을_쥐고_끝나면_남기지_않는다() {
    String key = randomKey("wd692_sl_");

    String result =
        inTenant(
            () ->
                callLocked(
                    dataSource,
                    platformLocks,
                    key,
                    () -> {
                      // 본문 안: 잠금이 실제로 잡혀 있다(없으면 이 테스트 전체가 공허하다).
                      assertThat(platformAdvisoryLocks("granted")).isEqualTo(1);
                      return "done";
                    }));

    assertThat(result).isEqualTo("done");
    // 커넥션은 풀로 돌아갔지만 세션은 살아 있다 — 여기서 0 이 아니면 잠금이 풀로 샌 것이다.
    assertThat(platformAdvisoryLocks("true")).isZero();
  }

  @Test
  void 본문이_예외로_끝나도_잠금을_남기지_않는다() {
    String key = randomKey("wd692_sl_");

    assertThatThrownBy(
            () ->
                inTenant(
                    () ->
                        callLocked(
                            dataSource,
                            platformLocks,
                            key,
                            () -> {
                              assertThat(platformAdvisoryLocks("granted")).isEqualTo(1);
                              throw new IllegalStateException("자식 프로세스 실패");
                            })))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("자식 프로세스 실패");

    assertThat(platformAdvisoryLocks("true")).isZero();
    // 같은 테이블을 바로 다시 잠글 수 있다 — 앞 실패가 뒤 실행을 막지 않는다.
    assertThat(inTenant(() -> callLocked(dataSource, platformLocks, key, () -> "again")))
        .isEqualTo("again");
    assertThat(platformAdvisoryLocks("true")).isZero();
  }

  /**
   * 같은 키의 세션 잠금끼리도 배타다 — 뒤 본문은 앞 본문이 끝난 뒤에 시작한다. 뒤 실행은 커넥션을 쥐지 않고 시도를 반복하므로(pg_locks 대기 행이 없다) 시도가
   * 시작됐다는 신호를 받은 뒤 재시도 간격 여러 번만큼 기다려도 들어오지 못함을 확인한다.
   *
   * <p>테스트 프로파일의 메인 풀은 2개다 — 앞 본문이 1개를 쥔 채 뒤 실행이 시도를 반복하고 테스트가 조회까지 하는데도 막히지 않는다는 것이, 대기자가 풀을 붙잡지
   * 않는다는 증거이기도 하다.
   */
  @Test
  void 같은_키의_두_본문은_겹쳐_돌지_않는다() throws Exception {
    String key = randomKey("wd692_sl_");
    ExecutorService pool = Executors.newSingleThreadExecutor();
    CountDownLatch secondStarted = new CountDownLatch(1);
    CountDownLatch secondEntered = new CountDownLatch(1);
    try {
      Future<String> second =
          inTenant(
              () ->
                  callLocked(
                      dataSource,
                      platformLocks,
                      key,
                      () -> {
                        Future<String> run =
                            pool.submit(
                                () ->
                                    TenantContext.runScopedGet(
                                        DEFAULT_TEST_TENANT_ID,
                                        () -> {
                                          secondStarted.countDown();
                                          return callLocked(
                                              dataSource,
                                              platformLocks,
                                              key,
                                              () -> {
                                                secondEntered.countDown();
                                                return "second";
                                              });
                                        }));
                        try {
                          assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
                          assertThat(
                                  secondEntered.await(
                                      SessionAdvisoryLock.RETRY_INTERVAL_MS * 5,
                                      TimeUnit.MILLISECONDS))
                              .as("앞 본문이 도는 동안 뒤 본문은 시작하지 않는다")
                              .isFalse();
                          // 테스트 커넥션 조회도 막히지 않는다(대기자가 풀을 붙잡지 않는다).
                          assertThat(platformAdvisoryLocks("granted")).isEqualTo(1);
                        } catch (InterruptedException e) {
                          throw new IllegalStateException(e);
                        }
                        return run;
                      }));

      assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo("second");
      assertThat(platformAdvisoryLocks("true")).isZero();
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * 해제 문장이 실패하면 커넥션을 물리적으로 끊는다 — 잠금을 쥔 세션이 풀로 돌아가면 그 키를 기다리는 모든 실행이 영원히 기다리게 되므로, 서버가 세션 종료로 잠금을
   * 회수하게 만든다. 실제 DB 로는 해제 실패를 결정적으로 만들 수 없어 이 분기만 목으로 확인한다.
   */
  @Test
  void 해제에_실패하면_커넥션을_끊고_반납한다() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement acquire = mock(PreparedStatement.class);
    Statement release = mock(Statement.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(PostgresPlatformLocks.TRY_ACQUIRE_SQL)).thenReturn(acquire);
    ResultSet acquired = mock(ResultSet.class);
    when(acquire.executeQuery()).thenReturn(acquired);
    when(acquired.next()).thenReturn(true);
    when(acquired.getBoolean(1)).thenReturn(true);
    when(connection.createStatement()).thenReturn(release);
    when(release.executeQuery(anyString())).thenThrow(new SQLException("연결 끊김"));

    String result =
        inTenant(
            () ->
                callLocked(
                    dataSource,
                    new PostgresPlatformLocks(mock(DSLContext.class)),
                    "some_key",
                    () -> "ok"));

    // 본문의 결과는 그대로 돌려준다(해제 실패가 성공한 실행을 실패로 바꾸지 않는다).
    assertThat(result).isEqualTo("ok");
    var order = inOrder(connection);
    order.verify(connection).abort(any());
    order.verify(connection).close();
  }

  /** 해제에 성공하면 커넥션을 끊지 않는다 — 풀의 커넥션을 매번 버리지 않는다. */
  @Test
  void 해제에_성공하면_커넥션을_끊지_않는다() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement acquire = mock(PreparedStatement.class);
    Statement release = mock(Statement.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(PostgresPlatformLocks.TRY_ACQUIRE_SQL)).thenReturn(acquire);
    ResultSet acquired = mock(ResultSet.class);
    when(acquire.executeQuery()).thenReturn(acquired);
    when(acquired.next()).thenReturn(true);
    when(acquired.getBoolean(1)).thenReturn(true);
    when(connection.createStatement()).thenReturn(release);

    inTenant(
        () ->
            callLocked(
                dataSource,
                new PostgresPlatformLocks(mock(DSLContext.class)),
                "some_key",
                () -> "ok"));

    verify(release).executeQuery(PostgresPlatformLocks.RELEASE_SQL);
    verify(connection, never()).abort(any());
    verify(connection).close();
  }

  // ------------------------------------------------------------------ //
  // 증분 스텝 잠금(WD-223) — 같은 공통 장치(SessionAdvisoryLock)에 대기 상한·취소를 더한 판
  // ------------------------------------------------------------------ //

  /** 같은 스텝 키를 다른 실행이 쥐고 있으면 상한까지만 기다리고 한국어 안내로 실패한다 — 대기자는 잠금을 남기지 않는다. */
  @Test
  void 증분_스텝_잠금은_대기_상한을_넘기면_안내와_함께_실패한다() {
    IncrementalStepLock shortWait = new IncrementalStepLock(platformLocks, dataSource, 600);
    long stepId = 9_000_000L + (System.nanoTime() % 1_000_000);
    try (SessionAdvisoryLock.Held first =
        inTenant(() -> shortWait.acquire(stepId, "느린 스텝", ExecutionCancelCheck.NONE))) {
      assertThat(platformAdvisoryLocks("granted")).isEqualTo(1);
      long started = System.currentTimeMillis();
      assertThatThrownBy(
              () -> inTenant(() -> shortWait.acquire(stepId, "느린 스텝", ExecutionCancelCheck.NONE)))
          .isInstanceOf(com.smartfirehub.pipeline.exception.ScriptExecutionException.class)
          .hasMessageContaining("같은 증분 스텝('느린 스텝')의 이전 실행이")
          .hasMessageContaining("다시 실행해 주세요");
      assertThat(System.currentTimeMillis() - started).as("상한만큼은 기다린다").isGreaterThanOrEqualTo(600);
      assertThat(platformAdvisoryLocks("granted")).as("대기자는 잠금을 쥐지 않는다").isEqualTo(1);
    }
    assertThat(platformAdvisoryLocks("true")).as("닫으면 잠금이 남지 않는다").isZero();
  }

  /** 기다리는 동안 실행이 취소되면 바로 취소 예외로 끝난다(WD-153) — 상한까지 기다리지 않는다. */
  @Test
  void 증분_스텝_잠금_대기_중_취소되면_바로_멈춘다() {
    IncrementalStepLock longWait = new IncrementalStepLock(platformLocks, dataSource, 60_000);
    long stepId = 8_000_000L + (System.nanoTime() % 1_000_000);
    java.util.concurrent.atomic.AtomicInteger checks =
        new java.util.concurrent.atomic.AtomicInteger();
    // 처음 두 번은 취소 아님, 세 번째 확인부터 취소 — 대기 루프가 회차마다 취소를 보는지 확인한다.
    ExecutionCancelCheck cancelLater = () -> checks.incrementAndGet() >= 3;
    try (SessionAdvisoryLock.Held first =
        inTenant(() -> longWait.acquire(stepId, "느린 스텝", ExecutionCancelCheck.NONE))) {
      long started = System.currentTimeMillis();
      assertThatThrownBy(() -> inTenant(() -> longWait.acquire(stepId, "느린 스텝", cancelLater)))
          .isInstanceOf(PipelineExecutionCancelledException.class);
      assertThat(System.currentTimeMillis() - started).isLessThan(10_000);
    }
    assertThat(platformAdvisoryLocks("true")).isZero();
  }

  /** 다른 스텝(다른 키)은 서로 기다리지 않는다 — 증분 스텝끼리라도 스텝이 다르면 직렬화하지 않는다. */
  @Test
  void 다른_증분_스텝의_잠금은_서로_막지_않는다() {
    IncrementalStepLock shortWait = new IncrementalStepLock(platformLocks, dataSource, 600);
    long stepId = 7_000_000L + (System.nanoTime() % 1_000_000);
    try (SessionAdvisoryLock.Held a =
            inTenant(() -> shortWait.acquire(stepId, "A", ExecutionCancelCheck.NONE));
        SessionAdvisoryLock.Held b =
            inTenant(() -> shortWait.acquire(stepId + 1, "B", ExecutionCancelCheck.NONE))) {
      // 둘 다 상한(600ms) 안에 얻었다 — 같은 키였다면 두 번째가 안내와 함께 실패했다. (테스트 풀이 2개라 두 잠금을 쥔 채
      // pg_locks 를 조회할 커넥션이 없다 — 대신 잡힌 핸들로 확인한다.)
      assertThat(a).isNotNull();
      assertThat(b).isNotNull();
    }
    assertThat(platformAdvisoryLocks("true")).isZero();
  }

  /**
   * 운영 빈은 잠금 커넥션을 메인 풀이 아니라 잠금 전용 풀에서 얻는다 — 잠금을 쥔 동안 메인 풀의 사용 중 커넥션이 늘지 않는다. 메인 풀에서 얻으면 증분 스텝이 많이
   * 동시에 돌 때 API 전체가 커넥션 타임아웃으로 멈춘다(WD-223 검토).
   */
  @Test
  void 증분_스텝_잠금은_메인_풀이_아니라_잠금_전용_풀의_커넥션을_쥔다() {
    HikariDataSource main = (HikariDataSource) dataSource;
    long stepId = 6_000_000L + (System.nanoTime() % 1_000_000);
    int activeBefore = main.getHikariPoolMXBean().getActiveConnections();
    try (SessionAdvisoryLock.Held held =
        inTenant(() -> incrementalStepLock.acquire(stepId, "A", ExecutionCancelCheck.NONE))) {
      assertThat(main.getHikariPoolMXBean().getActiveConnections())
          .as("잠금을 쥔 동안 메인 풀 사용 중 커넥션이 늘지 않는다")
          .isEqualTo(activeBefore);
      assertThat(platformAdvisoryLocks("granted")).as("잠금은 실제로 잡혀 있다").isEqualTo(1);
    }
    assertThat(platformAdvisoryLocks("true")).isZero();
  }

  /** 잠금 전용 풀이 바닥나면 메인 풀로 넘어가지 않고 상한 안에서 기다리다 같은 안내로 실패한다. */
  @Test
  void 잠금_전용_풀이_바닥나면_상한_안에서_기다리다_안내와_함께_실패한다() {
    HikariConfig config = new HikariConfig();
    config.setPoolName("wd223-test-lock-pool");
    config.setJdbcUrl(dataSourceProperties.determineUrl());
    config.setUsername(dataSourceProperties.determineUsername());
    config.setPassword(dataSourceProperties.determinePassword());
    config.setMaximumPoolSize(1);
    config.setConnectionTimeout(IncrementalStepLock.POOL_CONNECTION_TIMEOUT_MS);
    try (HikariDataSource onePool = new HikariDataSource(config)) {
      IncrementalStepLock lock = new IncrementalStepLock(platformLocks, onePool, 1_500);
      long stepId = 5_000_000L + (System.nanoTime() % 1_000_000);
      try (SessionAdvisoryLock.Held first =
          inTenant(() -> lock.acquire(stepId, "A", ExecutionCancelCheck.NONE))) {
        long started = System.currentTimeMillis();
        // 다른 스텝이라 잠금 경합은 없다 — 오직 잠금 전용 풀(1개)이 바닥나서 기다린다.
        assertThatThrownBy(
                () -> inTenant(() -> lock.acquire(stepId + 1, "B", ExecutionCancelCheck.NONE)))
            .isInstanceOf(com.smartfirehub.pipeline.exception.ScriptExecutionException.class)
            .hasMessageContaining("같은 증분 스텝('B')의 이전 실행이");
        assertThat(System.currentTimeMillis() - started).isGreaterThanOrEqualTo(1_500);
        // 메인 풀은 그대로 쓸 수 있다(이 조회가 메인 풀로 돈다).
        assertThat(platformAdvisoryLocks("granted")).isEqualTo(1);
      }
    }
    assertThat(platformAdvisoryLocks("true")).isZero();
  }
}
