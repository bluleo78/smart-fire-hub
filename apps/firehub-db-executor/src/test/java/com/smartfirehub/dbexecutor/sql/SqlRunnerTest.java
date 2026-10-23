package com.smartfirehub.dbexecutor.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.smartfirehub.dbexecutor.LogCapture;
import com.smartfirehub.dbexecutor.config.ExecutionTimeouts;
import com.smartfirehub.dbexecutor.store.StoreDescriptorClient;
import com.smartfirehub.dbexecutor.web.dto.SqlExecuteResponse;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.sql.Types;
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
        new ExecutionTimeouts(
            new ExecutionTimeouts.Sql(statementSeconds, totalSeconds),
            new ExecutionTimeouts.Probe(30),
            new ExecutionTimeouts.Query(30)),
        descriptors);
  }

  /** 컨트롤러처럼 지금부터 전체 기한({@code totalSeconds})을 잡아 실행한다. */
  private SqlExecuteResponse run(
      int statementSeconds, int totalSeconds, String query, List<String> preStatements) {
    try (Deadline deadline = new Deadline(totalSeconds)) {
      return runner(statementSeconds, totalSeconds)
          .run(FakeJdbc.DESCRIPTOR, query, preStatements, deadline);
    }
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
    // 문장마다 50초 제한(첫 문장은 전체 남은 시간 55초보다 짧으므로 정확히 50).
    assertThat(db.queryTimeouts).hasSize(3);
    assertThat(db.queryTimeouts.get(0)).isEqualTo(50);
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
}
