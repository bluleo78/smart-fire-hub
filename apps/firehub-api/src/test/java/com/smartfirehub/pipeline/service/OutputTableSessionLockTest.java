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

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 출력 테이블 세션 잠금(#735)의 계약 — 실제 DB 로 확인한다. 세션 범위 advisory 잠금은 커밋으로 풀리지
 * 않고 풀로 돌아간 커넥션에도 남으므로, <b>누수가 없다는 것</b>이 이 클래스의 핵심 단언이다.
 *
 * <p>잠금 관측은 {@code pg_locks} 의 advisory 행으로 한다. 테스트 DB 는 이 JVM 전용 컨테이너이고
 * 제품 코드에서 advisory 잠금을 쓰는 곳은 출력 테이블 직렬화뿐이라 다른 잠금이 섞이지 않는다.
 * 클래스 레벨 {@code @Transactional} 은 쓰지 않는다 — 다른 커넥션이 픽스처를 봐야 한다.
 */
class OutputTableSessionLockTest extends IntegrationTestBase {

  @Autowired private OutputTableSessionLock sessionLock;
  @Autowired private SqlScriptExecutor sqlScriptExecutor;
  @Autowired private DSLContext dsl;
  @Autowired private DataTableService dataTableService;

  private static String randomTable(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
  }

  /** 현재 DB 에 존재하는 advisory 잠금 수(쥔 것·기다리는 것 모두). */
  private int advisoryLocks(String condition) {
    return dsl.fetchOne("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND " + condition)
        .get(0, Integer.class);
  }

  private <T> T inTenant(java.util.function.Supplier<T> action) {
    return TenantContext.runScopedGet(DEFAULT_TEST_TENANT_ID, action::get);
  }

  @Test
  void 본문이_도는_동안만_잠금을_쥐고_끝나면_남기지_않는다() {
    String table = randomTable("p735_sl_");

    String result =
        inTenant(
            () ->
                sessionLock.callLocked(
                    table,
                    () -> {
                      // 본문 안: 잠금이 실제로 잡혀 있다(없으면 이 테스트 전체가 공허하다).
                      assertThat(advisoryLocks("granted")).isEqualTo(1);
                      return "done";
                    }));

    assertThat(result).isEqualTo("done");
    // 커넥션은 풀로 돌아갔지만 세션은 살아 있다 — 여기서 0 이 아니면 잠금이 풀로 샌 것이다.
    assertThat(advisoryLocks("true")).isZero();
  }

  @Test
  void 본문이_예외로_끝나도_잠금을_남기지_않는다() {
    String table = randomTable("p735_sl_");

    assertThatThrownBy(
            () ->
                inTenant(
                    () ->
                        sessionLock.callLocked(
                            table,
                            () -> {
                              assertThat(advisoryLocks("granted")).isEqualTo(1);
                              throw new IllegalStateException("자식 프로세스 실패");
                            })))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("자식 프로세스 실패");

    assertThat(advisoryLocks("true")).isZero();
    // 같은 테이블을 바로 다시 잠글 수 있다 — 앞 실패가 뒤 실행을 막지 않는다.
    assertThat(inTenant(() -> sessionLock.callLocked(table, () -> "again"))).isEqualTo("again");
    assertThat(advisoryLocks("true")).isZero();
  }

  /**
   * 세션 잠금을 쥔 동안에는 같은 출력에 대한 SQL 스텝의 "비우기 + 적재"(트랜잭션 범위 잠금, #731)가
   * 기다린다 — 두 경로의 키가 같다는 증거다. 키가 어긋나면 SQL 실행이 기다리지 않고 바로 끝나
   * 대기 단언(1건)과 "아직 비워지지 않았다" 단언이 깨진다.
   */
  @Test
  void 세션_잠금을_쥔_동안_같은_출력의_SQL_REPLACE는_기다린다() throws Exception {
    String out = randomTable("p735_sl_out_");
    dataTableService.createTable(
        out,
        List.of(new DatasetColumnRequest("name", "name", "TEXT", null, true, false, null, false)));
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      dsl.execute("INSERT INTO " + DataSchema.qualify(out) + " (name) VALUES ('old')");
      String insert = "INSERT INTO " + DataSchema.qualify(out) + " (name) VALUES ('new')";

      Future<String> sqlRun =
          inTenant(
              () ->
                  sessionLock.callLocked(
                      out,
                      () -> {
                        Future<String> run =
                            pool.submit(
                                () ->
                                    TenantContext.runScopedGet(
                                        DEFAULT_TEST_TENANT_ID,
                                        () ->
                                            sqlScriptExecutor.execute(
                                                List.of(OutputClearStatement.deleteAll(out)),
                                                insert)));
                        awaitAdvisoryWaiters(1);
                        assertThat(advisoryLocks("NOT granted"))
                            .as("SQL REPLACE 가 같은 키의 잠금을 기다려야 한다")
                            .isEqualTo(1);
                        // 기다리는 동안 출력은 그대로다(비우기가 잠금 뒤에 있다).
                        assertThat(names(out)).containsExactly("old");
                        assertThat(run.isDone()).isFalse();
                        return run;
                      }));

      // 세션 잠금이 풀리면 SQL 실행이 이어서 끝난다.
      assertThat(sqlRun.get(30, TimeUnit.SECONDS)).isEqualTo("SQL executed successfully");
      assertThat(names(out)).containsExactly("new");
      assertThat(advisoryLocks("true")).isZero();
    } finally {
      pool.shutdownNow();
      dsl.execute("DROP TABLE IF EXISTS " + DataSchema.qualify(out));
    }
  }

  /**
   * 같은 출력에 대한 세션 잠금끼리도 배타다 — 뒤 본문은 앞 본문이 끝난 뒤에 시작한다. 뒤 실행은
   * 커넥션을 쥐지 않고 시도를 반복하므로(pg_locks 대기 행이 없다) 시도가 시작됐다는 신호를 받은 뒤
   * 재시도 간격 여러 번만큼 기다려도 들어오지 못함을 확인한다.
   *
   * <p>테스트 프로파일의 메인 풀은 2개다 — 앞 본문이 1개를 쥔 채 뒤 실행이 시도를 반복하고 테스트가
   * 조회까지 하는데도 막히지 않는다는 것이, 대기자가 풀을 붙잡지 않는다는 증거이기도 하다.
   */
  @Test
  void 같은_출력의_두_본문은_겹쳐_돌지_않는다() throws Exception {
    String table = randomTable("p735_sl_");
    ExecutorService pool = Executors.newSingleThreadExecutor();
    CountDownLatch secondStarted = new CountDownLatch(1);
    CountDownLatch secondEntered = new CountDownLatch(1);
    try {
      Future<String> second =
          inTenant(
              () ->
                  sessionLock.callLocked(
                      table,
                      () -> {
                        Future<String> run =
                            pool.submit(
                                () ->
                                    TenantContext.runScopedGet(
                                        DEFAULT_TEST_TENANT_ID,
                                        () -> {
                                          secondStarted.countDown();
                                          return sessionLock.callLocked(
                                              table,
                                              () -> {
                                                secondEntered.countDown();
                                                return "second";
                                              });
                                        }));
                        try {
                          assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
                          assertThat(
                                  secondEntered.await(
                                      OutputTableSessionLock.RETRY_INTERVAL_MS * 5,
                                      TimeUnit.MILLISECONDS))
                              .as("앞 본문이 도는 동안 뒤 본문은 시작하지 않는다")
                              .isFalse();
                          // 테스트 커넥션 조회도 막히지 않는다(대기자가 풀을 붙잡지 않는다).
                          assertThat(advisoryLocks("granted")).isEqualTo(1);
                        } catch (InterruptedException e) {
                          throw new IllegalStateException(e);
                        }
                        return run;
                      }));

      assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo("second");
      assertThat(advisoryLocks("true")).isZero();
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * 해제 문장이 실패하면 커넥션을 물리적으로 끊는다 — 잠금을 쥔 세션이 풀로 돌아가면 그 출력의 모든
   * REPLACE 가 영원히 기다리게 되므로, 서버가 세션 종료로 잠금을 회수하게 만든다. 실제 DB 로는 해제
   * 실패를 결정적으로 만들 수 없어 이 분기만 목으로 확인한다.
   */
  @Test
  void 해제에_실패하면_커넥션을_끊고_반납한다() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement acquire = mock(PreparedStatement.class);
    Statement release = mock(Statement.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(OutputTableSessionLock.TRY_ACQUIRE_SQL)).thenReturn(acquire);
    ResultSet acquired = mock(ResultSet.class);
    when(acquire.executeQuery()).thenReturn(acquired);
    when(acquired.next()).thenReturn(true);
    when(acquired.getBoolean(1)).thenReturn(true);
    when(connection.createStatement()).thenReturn(release);
    when(release.executeQuery(anyString())).thenThrow(new SQLException("연결 끊김"));

    String result =
        inTenant(() -> new OutputTableSessionLock(dataSource).callLocked("some_out", () -> "ok"));

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
    when(connection.prepareStatement(OutputTableSessionLock.TRY_ACQUIRE_SQL)).thenReturn(acquire);
    ResultSet acquired = mock(ResultSet.class);
    when(acquire.executeQuery()).thenReturn(acquired);
    when(acquired.next()).thenReturn(true);
    when(acquired.getBoolean(1)).thenReturn(true);
    when(connection.createStatement()).thenReturn(release);

    inTenant(() -> new OutputTableSessionLock(dataSource).callLocked("some_out", () -> "ok"));

    verify(release).executeQuery(OutputTableSessionLock.RELEASE_SQL);
    verify(connection, never()).abort(any());
    verify(connection).close();
  }

  private void awaitAdvisoryWaiters(int expected) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline && advisoryLocks("NOT granted") < expected) {
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
  }

  private List<String> names(String table) {
    return dsl.fetch("SELECT name FROM " + DataSchema.qualify(table) + " ORDER BY name")
        .getValues(0, String.class);
  }
}
