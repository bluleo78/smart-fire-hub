package com.smartfirehub.dbexecutor.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.smartfirehub.dbexecutor.LogCapture;
import com.smartfirehub.dbexecutor.config.ExecutionTimeouts;
import com.smartfirehub.dbexecutor.store.StoreDescriptorClient;
import com.smartfirehub.dbexecutor.web.ExecuteController;
import com.smartfirehub.dbexecutor.web.dto.SqlExecuteRequest;
import com.smartfirehub.dbexecutor.web.dto.SqlExecuteResponse;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.sql.Types;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** /execute/sql 실행 규칙(스펙 §4.3): 재검증 → 선행문마다 LOCK→DELETE → 본문 → COMMIT, 실패·시간 초과는 ROLLBACK. */
class SqlRunnerTest {

  private static final String DELETE = "DELETE FROM \"GENIA_T5\".\"ORDERS\"";
  private static final String LOCK = "LOCK TABLE \"GENIA_T5\".\"ORDERS\" IN EXCLUSIVE MODE";
  private static final String INSERT =
      "INSERT INTO \"GENIA_T5\".\"ORDERS\" (\"ID\", \"A\", \"_UPDATED_AT\") SELECT"
          + " \"GENIA_T5\".\"SQ_X\".NEXTVAL, src.\"A\", SYSTIMESTAMP FROM (\nSELECT 1 a FROM DUAL\n) src";

  private final FakeJdbc db = new FakeJdbc();
  private final StoreDescriptorClient descriptors = mock(StoreDescriptorClient.class);

  SqlRunnerTest() throws SQLException {}

  private SqlRunner runner(int statementSeconds, int totalSeconds) {
    return new SqlRunner(
        db.pools,
        timeouts(new ExecutionTimeouts.Sql(statementSeconds, totalSeconds, 1800)),
        descriptors);
  }

  private static ExecutionTimeouts timeouts(ExecutionTimeouts.Sql sql) {
    return new ExecutionTimeouts(
        sql, new ExecutionTimeouts.Probe(30), new ExecutionTimeouts.Query(30));
  }

  /**
   * 컨트롤러처럼 지금부터 요청 기한을 잡아 실행한다 — 선행 문장이 있으면 {@code 선행 상한(기본 1800) + 본문 전체 기한}, 없으면 본문 전체 기한(컨트롤러와
   * 같은 계산 {@link ExecutionTimeouts.Sql#requestSeconds}).
   */
  private SqlExecuteResponse run(
      int statementSeconds, int totalSeconds, String query, List<String> preStatements) {
    ExecutionTimeouts.Sql sql = new ExecutionTimeouts.Sql(statementSeconds, totalSeconds, 1800);
    boolean hasPre = preStatements != null && !preStatements.isEmpty();
    try (Deadline deadline = new Deadline(sql.requestSeconds(totalSeconds, hasPre))) {
      return runner(statementSeconds, totalSeconds)
          .run(FakeJdbc.DESCRIPTOR, query, preStatements, deadline);
    }
  }

  /**
   * HTTP 만 뺀 실제 경로(컨트롤러 → 실제 SqlRunner → 모의 JDBC)로 실행한다 — 요청 기한은 컨트롤러가 도착 시점에 잡는다(WD-236 기한 분리는
   * 컨트롤러 기한과 실행기 기한이 함께 만든다).
   */
  private SqlExecuteResponse viaController(
      ExecutionTimeouts.Sql sql, String query, List<String> preStatements) {
    when(descriptors.get(eq(5L), any(Duration.class))).thenReturn(FakeJdbc.DESCRIPTOR);
    ExecutionTimeouts t = timeouts(sql);
    ExecuteController controller =
        new ExecuteController(
            descriptors,
            new SqlRunner(db.pools, t, descriptors),
            null,
            null,
            null,
            null,
            t,
            new com.fasterxml.jackson.databind.ObjectMapper());
    return controller.executeSql(new SqlExecuteRequest(5L, query, preStatements));
  }

  @Test
  void replace_runsLockThenDeleteThenBodyThenCommit() {
    SqlExecuteResponse res = run(50, 55, INSERT, List.of(DELETE));

    assertThat(res.success()).isTrue();
    assertThat(db.events).containsExactly(LOCK, DELETE, INSERT, "COMMIT");
    assertThat(res.rowCount()).isEqualTo(3);
    assertThat(res.rows()).isNull();
    assertThat(res.columns()).isNull();
    assertThat(res.error()).isNull();
    assertThat(res.dbError()).isNull();
    assertThat(res.executionLog()).isNotBlank();
    // 선행 LOCK·DELETE 는 선행 상한(1800, 남은 몫 올림), 본문은 본문 문장 제한 50초(WD-236 — 본문 시작부터 잰다).
    assertThat(db.queryTimeouts).hasSize(3);
    assertThat(db.queryTimeouts.subList(0, 2)).allMatch(t -> t >= 1799 && t <= 1800);
    assertThat(db.queryTimeouts.get(2)).isEqualTo(50);
  }

  @Test
  void bodyFailure_rollsBackAndPassesSignedCode() {
    db.handler =
        sql -> {
          if (sql.startsWith("INSERT")) {
            throw new SQLException("TBR-10007: UNIQUE constraint violation.", "23000", -10007);
          }
          return false;
        };

    SqlExecuteResponse res = run(50, 55, INSERT, List.of(DELETE));

    assertThat(res.success()).isFalse();
    assertThat(db.events).containsExactly(LOCK, DELETE, INSERT, "ROLLBACK");
    assertThat(res.dbError()).isNotNull();
    assertThat(res.dbError().code()).isEqualTo(-10007);
    assertThat(res.dbError().message()).contains("UNIQUE");
    assertThat(res.error()).contains("UNIQUE");
    assertThat(res.rows()).isNull();
  }

  @Test
  void totalTimeout_cancelsRunningStatementAndRollsBack() {
    db.handler =
        sql -> {
          if (sql.startsWith("INSERT")) {
            // cancel() 이 불릴 때까지 막혀 있다가, 드라이버처럼 취소 오류를 던진다.
            if (!db.cancelled.await(10, TimeUnit.SECONDS)) {
              throw new AssertionError("cancel 이 호출되지 않았다");
            }
            throw new SQLException("statement cancelled", "72000", -1013);
          }
          return false;
        };

    long start = System.nanoTime();
    SqlExecuteResponse res = run(50, 1, INSERT, List.of(DELETE));
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

    assertThat(res.success()).isFalse();
    assertThat(res.error()).isEqualTo("실행 시간이 1초를 넘어 취소했습니다.");
    assertThat(res.dbError()).isNull();
    assertThat(db.events).contains("CANCEL").endsWith("ROLLBACK").doesNotContain("COMMIT");
    assertThat(elapsedMs).isLessThan(5_000);
  }

  /** 본문이 cancel 을 무시하고 기한 뒤에 끝나도 커밋하지 않는다 — 커밋 직전에 기한을 다시 본다(api 는 이미 포기했을 수 있다). */
  @Test
  void bodyFinishingAfterDeadline_isRolledBackNotCommitted() {
    db.handler =
        sql -> {
          if (sql.startsWith("INSERT")) {
            Thread.sleep(1_300); // 취소에 반응하지 않는 드라이버처럼 기한(1초)을 넘겨 정상 종료한다.
          }
          return false;
        };

    SqlExecuteResponse res = run(50, 1, INSERT, List.of(DELETE));

    assertThat(res.success()).isFalse();
    assertThat(res.error()).isEqualTo("실행 시간이 1초를 넘어 취소했습니다.");
    assertThat(db.events).endsWith("ROLLBACK").doesNotContain("COMMIT");
  }

  /** 기한이 이미 지난 채로 들어오면(기술자 조회가 기한을 다 씀) 커넥션을 빌리지 않는다. */
  @Test
  void expiredDeadline_neverBorrowsConnection() throws Exception {
    try (Deadline expired = new Deadline(1)) {
      Thread.sleep(1_100);
      SqlExecuteResponse res =
          runner(50, 1).run(FakeJdbc.DESCRIPTOR, INSERT, List.of(DELETE), expired);
      assertThat(res.success()).isFalse();
      assertThat(res.error()).isEqualTo("실행 시간이 1초를 넘어 취소했습니다.");
    }
    assertThat(db.factoryCalls.get()).isZero();
    assertThat(db.events).isEmpty();
  }

  @Test
  void timeoutMessage_defaultIsExactKoreanString() {
    assertThat(SqlRunner.timeoutMessage(55)).isEqualTo("실행 시간이 55초를 넘어 취소했습니다.");
  }

  @Test
  void select_withoutPreStatements_returnsConvertedRowsAndColumns() throws SQLException {
    ResultSet rs = mock(ResultSet.class);
    ResultSetMetaData md = mock(ResultSetMetaData.class);
    when(rs.getMetaData()).thenReturn(md);
    when(md.getColumnCount()).thenReturn(2);
    when(md.getColumnLabel(1)).thenReturn("AMOUNT");
    when(md.getColumnType(1)).thenReturn(Types.NUMERIC);
    when(md.getPrecision(1)).thenReturn(18);
    when(md.getScale(1)).thenReturn(6);
    when(md.getColumnLabel(2)).thenReturn("TotalAmt");
    when(md.getColumnType(2)).thenReturn(Types.VARCHAR);
    when(rs.next()).thenReturn(true, false);
    when(rs.getBigDecimal(1)).thenReturn(new BigDecimal("1.5"));
    when(rs.getString(2)).thenReturn("x");
    db.resultSet = rs;
    db.handler = sql -> true;

    SqlExecuteResponse res = run(50, 55, "SELECT amount, \"TotalAmt\" FROM t;", List.of());

    assertThat(res.success()).isTrue();
    assertThat(res.columns()).containsExactly("amount", "TotalAmt");
    assertThat(res.rows())
        .containsExactly(Map.of("amount", new BigDecimal("1.500000"), "TotalAmt", "x"));
    assertThat(res.rowCount()).isEqualTo(1);
    // 끝 세미콜론은 떼고 실행한다(드라이버는 ; 를 받지 않는다).
    assertThat(db.events.get(0)).isEqualTo("SELECT amount, \"TotalAmt\" FROM t");
  }

  @Test
  void select_withPreStatements_doesNotReturnRows() {
    db.handler = sql -> false;
    SqlExecuteResponse res = run(50, 55, "SELECT 1 FROM DUAL", List.of(DELETE));
    assertThat(res.success()).isTrue();
    assertThat(res.rows()).isNull();
    assertThat(res.columns()).isNull();
  }

  @Test
  void dml_withoutPreStatements_returnsUpdateCountWithoutRows() {
    db.updateCount = 7;
    SqlExecuteResponse res =
        run(50, 55, "UPDATE \"GENIA_T5\".\"ORDERS\" SET \"A\" = 1;", List.of());
    assertThat(res.success()).isTrue();
    assertThat(res.rowCount()).isEqualTo(7);
    assertThat(res.rows()).isNull();
    assertThat(db.events).containsExactly("UPDATE \"GENIA_T5\".\"ORDERS\" SET \"A\" = 1", "COMMIT");
  }

  @Test
  void rejectedBody_isRefusedBeforeAnyConnection() {
    for (String sql :
        new String[] {
          "DROP TABLE \"GENIA_T5\".\"ORDERS\"",
          "BEGIN NULL; END;",
          "SELECT 1 FROM DUAL; SELECT 2 FROM DUAL",
          "SELECT DBMS_XMLGEN.getxml('select 1 from dual') FROM DUAL",
          "SELECT other.fn(1) FROM DUAL",
          "SELECT 'unclosed FROM DUAL",
          "",
        }) {
      SqlExecuteResponse res = run(50, 55, sql, List.of());
      assertThat(res.success()).as(sql).isFalse();
      assertThat(res.error()).as(sql).isNotBlank();
      assertThat(res.dbError()).as(sql).isNull();
    }
    assertThat(db.factoryCalls.get()).isZero();
    assertThat(db.events).isEmpty();
  }

  @Test
  void rejectedPreStatement_isRefusedBeforeAnyConnection() {
    SqlExecuteResponse res = run(50, 55, INSERT, List.of("DELETE FROM \"GENIA_T6\".\"ORDERS\""));
    assertThat(res.success()).isFalse();
    assertThat(res.error()).isEqualTo("허용되지 않은 선행 문장입니다.");
    assertThat(db.factoryCalls.get()).isZero();
  }

  @Test
  void connectFailure_returnsGenericMessageWithoutHostOrPassword() {
    db.connectFailure = new SQLException("cannot connect to 10.0.0.1:8629 as GENIA_T5/pw-secret");
    SqlExecuteResponse res = run(50, 55, INSERT, List.of(DELETE));
    assertThat(res.success()).isFalse();
    assertThat(res.error()).isEqualTo("테넌트 저장소에 연결하지 못했습니다.");
    assertThat(res.dbError()).isNull();
    assertThat(res.executionLog()).doesNotContain("10.0.0.1").doesNotContain("pw-secret");
    // F7: 대여(로그인) 실패면 기술자 캐시를 지운다 — 비밀번호 교체 뒤 다음 요청이 새 기술자로 풀을 다시 만든다.
    verify(descriptors).invalidate(5L);
  }

  /** E7: 풀 고갈 대기 시간 초과(원인 없는 SQLTransientConnectionException)는 기술자 탓이 아니므로 캐시를 지우지 않는다. */
  @Test
  void poolExhaustedTimeout_keepsDescriptorCache() {
    db.connectFailure =
        new SQLTransientConnectionException(
            "tibero-tenant-5 - Connection is not available, request timed out after 10000ms.");
    SqlExecuteResponse res = run(50, 55, INSERT, List.of(DELETE));
    assertThat(res.success()).isFalse();
    assertThat(res.error()).isEqualTo("테넌트 저장소에 연결하지 못했습니다.");
    verify(descriptors, never()).invalidate(anyLong());
  }

  /** E7: Hikari 는 로그인 실패도 대기 시간 초과로 알리되 드라이버 오류를 원인으로 붙인다 — 이때는 캐시를 지운다. */
  @Test
  void loginFailureReportedAsPoolTimeout_invalidatesDescriptor() {
    db.connectFailure =
        new SQLTransientConnectionException(
            "tibero-tenant-5 - Connection is not available, request timed out after 10000ms.",
            "28000",
            new SQLException("invalid username/password", "28000", -12));
    SqlExecuteResponse res = run(50, 55, INSERT, List.of(DELETE));
    assertThat(res.success()).isFalse();
    verify(descriptors).invalidate(5L);
  }

  @Test
  void successfulRun_keepsDescriptorCache() {
    run(50, 55, INSERT, List.of(DELETE));
    verify(descriptors, never()).invalidate(anyLong());
  }

  /**
   * 최종 리뷰 FF1: 대여 실패는 원인을 WARN 으로 남긴다(테넌트·실패 종류·원인 체인 단계별 클래스와 가린 메시지). 예외 객체는 로거에 넘기지 않는다 — SLF4J
   * 는 원인 체인을 가리지 않고 찍으므로, 드라이버 메시지에 섞인 비밀번호가 바깥·안쪽 어느 단계에 있든 로그에 남으면 안 된다.
   */
  @Test
  void connectFailure_logsScrubbedCauseChainWithoutThrowable() {
    db.connectFailure =
        new SQLTransientConnectionException(
            "tibero-tenant-5 - Connection is not available (pw-secret)",
            "08001",
            new SQLException("login denied for GENIA_T5/pw-secret", "28000", -12));
    try (LogCapture logs = LogCapture.of(LeasedExecution.class)) {
      SqlExecuteResponse res = run(50, 55, INSERT, List.of(DELETE));
      assertThat(res.error()).isEqualTo("테넌트 저장소에 연결하지 못했습니다.");

      assertThat(logs.events()).hasSize(1);
      ILoggingEvent e = logs.events().get(0);
      assertThat(e.getLevel()).isEqualTo(Level.WARN);
      assertThat(e.getThrowableProxy()).isNull();
      assertThat(e.getFormattedMessage())
          .contains("tenant=5")
          .contains("접속·인증 실패")
          .contains("java.sql.SQLTransientConnectionException")
          .contains("java.sql.SQLException: login denied for GENIA_T5/****")
          .doesNotContain("pw-secret");
    }
  }

  /** 최종 리뷰 FF1: 풀 고갈 대기 시간 초과도 종류를 갈라 WARN 으로 남긴다. */
  @Test
  void poolExhaustedTimeout_logsKind() {
    db.connectFailure =
        new SQLTransientConnectionException(
            "tibero-tenant-5 - Connection is not available, request timed out after 10000ms.");
    try (LogCapture logs = LogCapture.of(LeasedExecution.class)) {
      run(50, 55, INSERT, List.of(DELETE));
      assertThat(logs.events())
          .singleElement()
          .satisfies(
              e -> {
                assertThat(e.getLevel()).isEqualTo(Level.WARN);
                assertThat(e.getThrowableProxy()).isNull();
                assertThat(e.getFormattedMessage()).contains("tenant=5").contains("풀 대기 시간 초과");
              });
    }
  }

  /** 최종 리뷰 FF1: 예기치 않은 RuntimeException(내부 결함)은 스택과 함께 ERROR 로 남긴다. 메시지에는 SQL 원문을 넣지 않는다. */
  @Test
  void unexpectedRuntimeException_logsErrorWithStack() {
    db.handler =
        sql -> {
          throw new IllegalStateException("내부 결함");
        };
    try (LogCapture logs = LogCapture.of(LeasedExecution.class)) {
      SqlExecuteResponse res = run(50, 55, INSERT, List.of());
      assertThat(res.error()).isEqualTo("SQL 실행 중 예기치 않은 오류가 발생했습니다.");
      assertThat(db.events).contains("ROLLBACK");

      assertThat(logs.events()).hasSize(1);
      ILoggingEvent e = logs.events().get(0);
      assertThat(e.getLevel()).isEqualTo(Level.ERROR);
      assertThat(e.getThrowableProxy()).isNotNull();
      assertThat(e.getThrowableProxy().getClassName())
          .isEqualTo(IllegalStateException.class.getName());
      assertThat(e.getFormattedMessage()).contains("tenant=5").doesNotContain("INSERT");
    }
  }

  @Test
  void dbErrorMessage_neverCarriesPassword() {
    db.handler =
        sql -> {
          throw new SQLException("odd error mentioning pw-secret", "99999", -1);
        };
    SqlExecuteResponse res = run(50, 55, INSERT, List.of());
    assertThat(res.error()).doesNotContain("pw-secret");
    assertThat(res.dbError().message()).doesNotContain("pw-secret");
  }

  /** 요청별 timeout_seconds: 본문 전체 기한·문장 제한이 그 값이고, 시간 초과 문구도 그 값을 쓴다. 선행 문장은 선행 상한을 쓴다. */
  @Test
  void requestedSeconds_overridesStatementAndTotalLimits() {
    // 컨트롤러가 잡는 기한: 선행 상한(1800) + 요청값(120).
    try (Deadline deadline =
        new Deadline(new ExecutionTimeouts.Sql(50, 55, 1800).requestSeconds(120, true))) {
      SqlExecuteResponse res =
          runner(50, 55).run(FakeJdbc.DESCRIPTOR, INSERT, List.of(DELETE), deadline, 120);

      assertThat(res.success()).isTrue();
      // 선행 LOCK·DELETE 는 선행 상한(1800, 남은 몫 올림), 본문은 50초가 아니라 요청 제한(120)을 쓴다(WD-236).
      assertThat(db.queryTimeouts).hasSize(3);
      assertThat(db.queryTimeouts.subList(0, 2)).allMatch(t -> t >= 1799 && t <= 1800);
      assertThat(db.queryTimeouts.get(2)).isEqualTo(120);
    }
  }

  // ── WD-236: 선행 문장은 별도 상한, 사용자 시간 제한은 본문에만(본문 시작부터) ──

  /** 선행 문장이 본문 전체 기한(1초)보다 오래 걸려도(선행 상한 5초 안) 본문은 자기 기한을 온전히 받아 성공하고 커밋된다. */
  @Test
  void preStatementsSlow_bodyStillGetsFullDeadline() {
    db.handler =
        sql -> {
          if (sql.startsWith("DELETE")) {
            Thread.sleep(1_300); // 기존 출력이 많아 비우기가 본문 기한(1초)보다 오래 걸린다.
          } else if (sql.startsWith("INSERT")) {
            Thread.sleep(300);
          }
          return false;
        };

    SqlExecuteResponse res =
        viaController(new ExecutionTimeouts.Sql(50, 1, 5), INSERT, List.of(DELETE));

    assertThat(res.success()).as(res.error()).isTrue();
    assertThat(db.events).containsExactly(LOCK, DELETE, INSERT, "COMMIT");
    // 본문 문장 제한은 남은 요청 기한이 아니라 본문 기한(1초)이다.
    assertThat(db.queryTimeouts.get(2)).isEqualTo(1);
  }

  /** 선행 문장이 선행 상한(1초)을 넘기면 고정 문구로 실패하고 롤백하며 본문은 실행하지 않는다. */
  @Test
  void preStatementsExceedPreCap_failsWithPreMessageAndRollsBack() {
    db.handler =
        sql -> {
          if (sql.startsWith("DELETE")) {
            Thread.sleep(1_300); // 취소에 반응하지 않는 드라이버처럼 선행 상한(1초)을 넘겨 정상 종료한다.
          }
          return false;
        };

    SqlExecuteResponse res =
        viaController(new ExecutionTimeouts.Sql(50, 5, 1), INSERT, List.of(DELETE));

    assertThat(res.success()).isFalse();
    assertThat(res.error()).isEqualTo("기존 출력 정리(선행 문장)가 1초 안에 끝나지 않아 취소했습니다.");
    assertThat(res.dbError()).isNull();
    assertThat(db.events).containsExactly(LOCK, DELETE, "ROLLBACK");
  }

  /**
   * 멈춘 선행 문장은 선행 상한 시점에 문장 제한({@code setQueryTimeout})으로 끝나도록 남은 선행 몫을 문장 제한으로 건다. 드라이버가 그 제한(또는 요청
   * 기한 cancel)으로 오류를 던지면 DB 오류가 아니라 선행 상한 초과 문구로 돌려준다.
   */
  @Test
  void hangingPreStatement_isCancelledAndReportedAsPreTimeout() {
    db.handler =
        sql -> {
          if (sql.startsWith("DELETE")) {
            // 모의 드라이버는 setQueryTimeout 을 집행하지 않는다 — 요청 기한(선행 1 + 본문 1 = 2초)의 cancel 을 기다린다.
            if (!db.cancelled.await(10, TimeUnit.SECONDS)) {
              throw new AssertionError("cancel 이 호출되지 않았다");
            }
            throw new SQLException("statement cancelled", "72000", -1013);
          }
          return false;
        };

    SqlExecuteResponse res =
        viaController(new ExecutionTimeouts.Sql(50, 1, 1), INSERT, List.of(DELETE));

    assertThat(res.success()).isFalse();
    assertThat(res.error()).isEqualTo("기존 출력 정리(선행 문장)가 1초 안에 끝나지 않아 취소했습니다.");
    assertThat(res.dbError()).isNull();
    assertThat(db.events).contains("CANCEL").endsWith("ROLLBACK").doesNotContain(INSERT, "COMMIT");
    // 선행 문장의 문장 제한은 남은 선행 몫(요청 기한 - 본문 몫, 올림)이라 1초다 — 실제 드라이버는 선행 상한 시점에 스스로 끝낸다.
    assertThat(db.queryTimeouts.subList(0, 2)).allMatch(t -> t == 1);
  }

  /** 본문이 본문 기한을 넘기면 지금처럼 실행 중 문장을 취소하고 롤백한다(회귀). */
  @Test
  void bodyExceedsDeadline_cancelsAndRollsBack() {
    db.handler =
        sql -> {
          if (sql.startsWith("INSERT")) {
            if (!db.cancelled.await(10, TimeUnit.SECONDS)) {
              throw new AssertionError("cancel 이 호출되지 않았다");
            }
            throw new SQLException("statement cancelled", "72000", -1013);
          }
          return false;
        };

    long start = System.nanoTime();
    SqlExecuteResponse res =
        viaController(new ExecutionTimeouts.Sql(50, 1, 5), INSERT, List.of(DELETE));
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

    assertThat(res.success()).isFalse();
    assertThat(res.error()).isEqualTo("실행 시간이 1초를 넘어 취소했습니다.");
    assertThat(res.dbError()).isNull();
    assertThat(db.events).contains(INSERT, "CANCEL").endsWith("ROLLBACK").doesNotContain("COMMIT");
    // 요청 기한(선행 5 + 본문 1 = 6초)이 아니라 본문 기한(본문 시작부터 1초)에 취소된다.
    assertThat(elapsedMs).isLessThan(3_000);
  }

  /** 선행 문장이 없으면 기존 50/55초 동작 그대로 — 요청 도착부터 본문 전체 기한, 문장 제한 50초(선행 상한을 더하지 않는다). */
  @Test
  void noPreStatements_unchanged() {
    SqlExecuteResponse ok = viaController(new ExecutionTimeouts.Sql(50, 55, 5), INSERT, List.of());
    assertThat(ok.success()).isTrue();
    assertThat(db.queryTimeouts).containsExactly(50);
    assertThat(db.events).containsExactly(INSERT, "COMMIT");

    db.events.clear();
    db.handler =
        sql -> {
          if (sql.startsWith("INSERT")) {
            if (!db.cancelled.await(10, TimeUnit.SECONDS)) {
              throw new AssertionError("cancel 이 호출되지 않았다");
            }
            throw new SQLException("statement cancelled", "72000", -1013);
          }
          return false;
        };
    long start = System.nanoTime();
    SqlExecuteResponse res = viaController(new ExecutionTimeouts.Sql(50, 1, 5), INSERT, List.of());
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

    assertThat(res.success()).isFalse();
    assertThat(res.error()).isEqualTo("실행 시간이 1초를 넘어 취소했습니다.");
    assertThat(db.events).endsWith("ROLLBACK").doesNotContain("COMMIT");
    // 선행 상한(5초)을 더하지 않은 도착 기한 1초에 취소된다.
    assertThat(elapsedMs).isLessThan(3_000);
  }

  /** 커밋 직전 기한 검사는 본문 기한 기준이다 — 선행이 본문 기한보다 길었어도 본문이 자기 기한 안에 끝났으면 커밋한다. */
  @Test
  void commitCheck_usesBodyDeadline_notArrival() {
    db.handler =
        sql -> {
          if (sql.startsWith("DELETE")) {
            Thread.sleep(2_500);
          } else if (sql.startsWith("INSERT")) {
            Thread.sleep(200); // 도착부터 2.7초 — 도착 기준 본문 기한(2초)이면 커밋 직전에 지났다. 본문만으론 0.2초라 여유가 1.8초다.
          }
          return false;
        };

    SqlExecuteResponse res =
        viaController(new ExecutionTimeouts.Sql(50, 2, 5), INSERT, List.of(DELETE));

    assertThat(res.success()).as(res.error()).isTrue();
    assertThat(db.events).endsWith("COMMIT").doesNotContain("ROLLBACK");
  }

  @Test
  void preTimeoutMessage_isExactKoreanString() {
    assertThat(SqlRunner.preTimeoutMessage(1800))
        .isEqualTo("기존 출력 정리(선행 문장)가 1800초 안에 끝나지 않아 취소했습니다.");
  }

  @Test
  void requestedSeconds_timeoutMessageUsesRequestedValue() {
    try (Deadline expired = new Deadline(1)) {
      sleep(1_100);
      SqlExecuteResponse res =
          runner(50, 55).run(FakeJdbc.DESCRIPTOR, INSERT, List.of(), expired, 1);
      assertThat(res.success()).isFalse();
      assertThat(res.error()).isEqualTo("실행 시간이 1초를 넘어 취소했습니다.");
    }
  }

  @Test
  void absentRequestedSeconds_keepsDefault50And55() {
    try (Deadline deadline = new Deadline(55)) {
      runner(50, 55).run(FakeJdbc.DESCRIPTOR, INSERT, List.of(), deadline, null);
    }
    assertThat(db.queryTimeouts).containsExactly(50);
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // ── 잠금 대기 중 취소(WD-482) ─────────────────────────────────────────────────────────

  private static final String DELETE_B = "DELETE FROM \"GENIA_T5\".\"ITEMS\"";
  private static final String LOCK_B = "LOCK TABLE \"GENIA_T5\".\"ITEMS\" IN EXCLUSIVE MODE";

  /** 중지 확인을 넘겨 실행한다(컨트롤러와 같은 요청 기한 계산). */
  private SqlExecuteResponse runWithStop(
      String query, List<String> preStatements, java.util.function.BooleanSupplier stop) {
    ExecutionTimeouts.Sql sql = new ExecutionTimeouts.Sql(50, 55, 1800);
    boolean hasPre = preStatements != null && !preStatements.isEmpty();
    try (Deadline deadline = new Deadline(sql.requestSeconds(55, hasPre))) {
      return runner(50, 55).run(FakeJdbc.DESCRIPTOR, query, preStatements, deadline, null, stop);
    }
  }

  /** 잠금을 얻은 뒤 중지 요청이 보이면 비우기·본문을 보내지 않고 롤백해 cancelled 로 답한다 — 출력은 그대로다. */
  @Test
  void stopRequestedAfterLock_rollsBackWithoutDeleteOrBody() {
    java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();

    SqlExecuteResponse res =
        runWithStop(
            INSERT,
            List.of(DELETE),
            () -> {
              // 확인 시점에는 잠금만 실행됐다(비우기 전).
              assertThat(db.events).containsExactly(LOCK);
              calls.incrementAndGet();
              return true;
            });

    assertThat(calls).hasValue(1);
    assertThat(db.events).containsExactly(LOCK, "ROLLBACK");
    assertThat(res.cancelled()).isTrue();
    assertThat(res.success()).isFalse();
    assertThat(res.error()).isEqualTo(SqlExecuteResponse.CANCELLED_MESSAGE);
    assertThat(res.dbError()).isNull();
  }

  /** 중지 요청이 없으면 기존 순서 그대로 실행·커밋한다. */
  @Test
  void notStopped_proceedsAsBefore() {
    SqlExecuteResponse res = runWithStop(INSERT, List.of(DELETE), () -> false);

    assertThat(res.success()).isTrue();
    assertThat(res.cancelled()).isFalse();
    assertThat(db.events).containsExactly(LOCK, DELETE, INSERT, "COMMIT");
  }

  /** 확인이 실패하면(api 장애 등) 중지 아님으로 보고 그대로 실행한다(fail-open) — 경고만 남긴다. */
  @Test
  void stopCheckFailure_failsOpenAndProceeds() {
    try (LogCapture logs = LogCapture.of(SqlRunner.class)) {
      SqlExecuteResponse res =
          runWithStop(
              INSERT,
              List.of(DELETE),
              () -> {
                throw new IllegalStateException("중지 요청 확인 응답 상태=503");
              });

      assertThat(res.success()).isTrue();
      assertThat(res.cancelled()).isFalse();
      assertThat(db.events).containsExactly(LOCK, DELETE, INSERT, "COMMIT");
      assertThat(logs.events())
          .singleElement()
          .satisfies(e -> assertThat(e.getLevel()).isEqualTo(Level.WARN));
    }
  }

  /** 선행 문장이 없으면 잠금도 대기도 없으므로 되묻지 않는다. */
  @Test
  void noPreStatements_neverChecksStop() {
    SqlExecuteResponse res =
        runWithStop(
            INSERT,
            List.of(),
            () -> {
              throw new AssertionError("선행 문장이 없으면 확인하지 않는다");
            });

    assertThat(res.success()).isTrue();
    assertThat(db.events).containsExactly(INSERT, "COMMIT");
  }

  /** 선행 문장이 여럿이면 잠금마다 확인한다 — 두 번째 잠금 대기 중 취소도 본문 전에 잡는다. */
  @Test
  void stopCheckedAfterEachLock() {
    java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();

    SqlExecuteResponse res =
        runWithStop(INSERT, List.of(DELETE, DELETE_B), () -> calls.incrementAndGet() == 2);

    assertThat(res.cancelled()).isTrue();
    assertThat(db.events).containsExactly(LOCK, DELETE, LOCK_B, "ROLLBACK");
  }

  /** 공개 경로(실행 id): 클라이언트에 기술자의 테넌트와 실행 id 로 묻고, id 가 없거나 0 이하면 묻지 않는다. */
  @Test
  void executionId_resolvesStopCheckForDescriptorTenant() {
    com.smartfirehub.dbexecutor.store.PipelineStopClient stopClient =
        mock(com.smartfirehub.dbexecutor.store.PipelineStopClient.class);
    when(stopClient.check(FakeJdbc.DESCRIPTOR.tenantId(), 42L)).thenReturn(() -> true);
    SqlRunner withClient =
        new SqlRunner(
            db.pools, timeouts(new ExecutionTimeouts.Sql(50, 55, 1800)), descriptors, stopClient);

    try (Deadline deadline = new Deadline(1855)) {
      SqlExecuteResponse res =
          withClient.run(FakeJdbc.DESCRIPTOR, INSERT, List.of(DELETE), deadline, null, 42L);
      assertThat(res.cancelled()).isTrue();
    }
    db.events.clear();
    try (Deadline deadline = new Deadline(1855)) {
      assertThat(
              withClient
                  .run(FakeJdbc.DESCRIPTOR, INSERT, List.of(DELETE), deadline, null, (Long) null)
                  .success())
          .isTrue();
      assertThat(
              withClient
                  .run(FakeJdbc.DESCRIPTOR, INSERT, List.of(DELETE), deadline, null, 0L)
                  .success())
          .isTrue();
    }
    verify(stopClient).check(FakeJdbc.DESCRIPTOR.tenantId(), 42L);
    verify(stopClient, never()).check(anyLong(), eq(0L));
  }
}
