package com.smartfirehub.global.platformdb.tibero;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.storage.tibero.TiberoDialect;
import com.smartfirehub.dataset.storage.tibero.TiberoErrors;
import com.smartfirehub.global.platformdb.PlatformTenantSession;
import com.smartfirehub.global.tenant.TenantAwareTransactionManager;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoTestAccount;
import com.smartfirehub.support.tibero.TiberoTestAccount.Privileges;
import com.smartfirehub.support.tibero.TiberoTestEvidence;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tibero 문장 취소(queryTimeout)가 바깥 트랜잭션에서 원래 예외 그대로 보이는지 실서버로 확인한다(WD-727).
 *
 * <p>왜: Tibero 드라이버는 queryTimeout 취소를 {@code SQLTimeoutException}(-12040)으로 보내고, HikariCP 5 는 이 예외를
 * 받은 연결을 「고장」으로 보고 닫는다(실측 로그: "marked as broken because of SQLSTATE(ERRFR), ErrorCode(-12040)").
 * 그러면 바깥 트랜잭션의 롤백이 「Connection is closed」로 다시 실패한다. 예전에는 스프링이 그 롤백 실패를 {@code
 * TransactionSystemException} 으로 던져 원래 시간 초과를 가렸다. 운영 트랜잭션 관리자({@link
 * TenantAwareTransactionManager})가 이미 닫힌 연결의 롤백 실패를 삼키므로, 이제 부르는 쪽은 원래 예외를 받아야 한다. 닫힌 연결의 작업이 커밋되지
 * 않았다는 것(그래서 롤백 실패를 삼켜도 되는 근거)도 함께 확인한다.
 *
 * <p><b>부하 없는 재현.</b> 연결 A 가 이번 실행의 소유자 계정 임시 테이블의 행 1 을 {@code FOR UPDATE} 로 잡고 커밋하지 않는다. 연결 B 는
 * 운영과 같은 트랜잭션 관리자의 {@link TransactionTemplate} 안에서 먼저 행 2 를 바꾼 뒤, queryTimeout 1초로 행 1 을 {@code FOR
 * UPDATE} 하다가 취소된다. 문장 번역은 운영 MyBatis 세션 템플릿과 같은 {@link TiberoSqlExceptionTranslator} 로 한다. CPU 를
 * 쓰는 긴 쿼리·큰 데이터는 쓰지 않는다. 테이블은 끝에 지우고 계정도 지운다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 90, unit = SECONDS)
class TiberoQueryTimeoutLiveTest {

  private static final String TABLE = "QTO_LOCK";

  /** 같은 행을 잠그는 문장 — A 가 먼저 잡고, B 는 queryTimeout 안에서 기다리다 취소된다. */
  private static final String LOCK_ROW_SQL = "SELECT V FROM " + TABLE + " WHERE ID = 1 FOR UPDATE";

  /** B 가 취소되기 전에 바꾸는 행 — 이 변경이 커밋되지 않아야 한다. */
  private static final String TOUCH_ROW2_SQL = "UPDATE " + TABLE + " SET V = 1 WHERE ID = 2";

  /** B 의 세션이 행 2 잠금을 놓았는지 기다리지 않고 본다(잠겨 있으면 -12033). */
  private static final String PROBE_ROW2_SQL =
      "SELECT V FROM " + TABLE + " WHERE ID = 2 FOR UPDATE NOWAIT";

  /**
   * 테넌트 세션 값을 다루지 않는 포트 — 임시 계정에는 {@code FH_TENANT} 패키지가 없다. 롤백 경로는 {@link
   * TenantAwareTransactionManager#doRollback} 그대로라 이 대체가 시험 대상을 바꾸지 않는다.
   */
  private static final PlatformTenantSession NO_TENANT_SESSION =
      new PlatformTenantSession() {
        @Override
        public void applyToConnection(Connection connection, long tenantId) {}

        @Override
        public void applyToCurrentTransaction(long tenantId) {}
      };

  private TiberoTestAccount account;

  @BeforeAll
  void setUp() throws SQLException {
    account = TiberoTestAccount.create("QTO", Privileges.TABLES, 2);
    account.execute("CREATE TABLE " + TABLE + " (ID NUMBER PRIMARY KEY, V NUMBER)");
    account.execute("INSERT INTO " + TABLE + " (ID, V) VALUES (1, 0)");
    account.execute("INSERT INTO " + TABLE + " (ID, V) VALUES (2, 0)");
  }

  @AfterAll
  void tearDown() {
    if (account == null) return;
    try {
      account.execute("DROP TABLE " + TABLE + " PURGE");
    } catch (SQLException e) {
      // 계정을 지우면 테이블도 함께 사라지므로 여기 실패는 증거로만 남긴다.
      TiberoTestEvidence.write("QTO 임시 테이블 DROP 실패(계정 삭제로 정리) 코드 " + e.getErrorCode());
    } finally {
      account.close();
    }
  }

  @Test
  @DisplayName("행 잠금 대기가 queryTimeout 으로 취소되면 부르는 쪽은 원래 시간 초과를 받고, 닫힌 연결의 변경은 커밋되지 않는다")
  void cancelledStatementPropagatesOriginalTimeout() throws Exception {
    TransactionTemplate tx =
        new TransactionTemplate(
            new TenantAwareTransactionManager(account.dataSource(), NO_TENANT_SESSION));
    JdbcTemplate jdbc = new JdbcTemplate(account.dataSource());
    jdbc.setExceptionTranslator(TiberoSqlExceptionTranslator.create());
    jdbc.setQueryTimeout(1);

    RuntimeException[] inner = new RuntimeException[1];
    RuntimeException outer = null;
    long elapsedMs;
    long row2;
    try (Connection a = account.dataSource().getConnection()) {
      a.setAutoCommit(false);
      try (Statement st = a.createStatement();
          ResultSet rs = st.executeQuery(LOCK_ROW_SQL)) {
        assertThat(rs.next()).isTrue();
      }
      long start = System.nanoTime();
      try {
        tx.executeWithoutResult(
            status -> {
              jdbc.update(TOUCH_ROW2_SQL);
              try {
                jdbc.queryForObject(LOCK_ROW_SQL, Long.class);
              } catch (RuntimeException e) {
                inner[0] = e;
                throw e;
              }
            });
      } catch (RuntimeException e) {
        outer = e;
      } finally {
        elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        a.rollback();
      }
      row2 = row2AfterSessionReleased(a);
    }

    TiberoTestEvidence.write("QTO 소요 " + elapsedMs + "ms, 행 2 값 " + row2);
    TiberoTestEvidence.write("QTO 안쪽 예외 사슬: " + chain(inner[0]));
    TiberoTestEvidence.write("QTO 바깥 예외 사슬: " + chain(outer));

    // 문장은 드라이버의 SQLTimeoutException(-12040)이 스프링 시간 초과로 번역된다.
    assertThat(inner[0]).isInstanceOf(QueryTimeoutException.class);
    assertThat(TiberoErrors.hasAnyCode(inner[0], TiberoDialect.STATEMENT_CANCELED)).isTrue();
    // 부르는 쪽은 롤백 실패(TransactionSystemException)가 아니라 원래 예외 그 자체를 받는다.
    assertThat(outer).isSameAs(inner[0]);
    // 닫힌 연결에서 한 변경은 커밋되지 않았다 — 롤백 실패를 삼켜도 데이터가 남지 않는다.
    assertThat(row2).isZero();
    // 무한정 기다리지 않고(상한), 즉시 실패하지도 않는다(하한 — 실제로 잠금을 기다렸다).
    assertThat(elapsedMs).isBetween(900L, 30_000L);
  }

  /**
   * B 의 세션이 끝나 행 2 잠금이 풀리기를 잠깐 기다린 뒤 행 2 값을 읽는다. 풀은 고장 난 연결을 따로(비동기로) 닫으므로 바로 읽으면 아직 B 가 잠금을 쥐고 있을
   * 수 있다. 잠금을 얻은 뒤 읽은 값은 B 가 커밋했는지 되돌렸는지를 그대로 보여 준다. 10초 안에 풀리지 않으면 실패다.
   */
  private static long row2AfterSessionReleased(Connection a) throws Exception {
    SQLException last = null;
    for (int i = 0; i < 40; i++) {
      try (Statement st = a.createStatement();
          ResultSet rs = st.executeQuery(PROBE_ROW2_SQL)) {
        rs.next();
        long v = rs.getLong(1);
        a.rollback();
        return v;
      } catch (SQLException e) {
        last = e;
        Thread.sleep(250);
      }
    }
    throw new AssertionError("B 세션이 행 2 잠금을 놓지 않았다", last);
  }

  /** 예외 사슬을 한 줄로 — 클래스·SQL 오류 코드·SQLState·메시지(앞 160자). */
  private static String chain(Throwable top) {
    if (top == null) return "(없음)";
    StringBuilder sb = new StringBuilder();
    for (Throwable t = top; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (sb.length() > 0) sb.append(" <- ");
      sb.append(t.getClass().getName());
      if (t instanceof SQLException sql) {
        sb.append("[code=").append(sql.getErrorCode()).append(" state=").append(sql.getSQLState());
        sb.append("]");
      }
      String msg = String.valueOf(t.getMessage());
      sb.append(": ").append(msg.length() > 160 ? msg.substring(0, 160) + "…" : msg);
    }
    return sb.toString();
  }
}
