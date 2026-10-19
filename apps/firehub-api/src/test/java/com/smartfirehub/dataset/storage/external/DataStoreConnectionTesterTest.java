package com.smartfirehub.dataset.storage.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.storage.DataStoreAccountRole;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.tibero.PlannedAccount;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** DBA 연결 테스트·계정 검증 단위 테스트(Mockito 가짜 JDBC). */
class DataStoreConnectionTesterTest {

  private static final ExternalDbTarget TARGET =
      new ExternalDbTarget(DataStoreBackend.TIBERO, "h", 8629, "tibero");

  private final ExternalDbConnector connector = mock(ExternalDbConnector.class);
  private final DataStoreConnectionTester tester = new DataStoreConnectionTester(connector);

  /** 주어진 권한 이름들을 한 행씩 돌려주는 ResultSet 가짜. */
  private static ResultSet privilegeRows(String... privs) throws SQLException {
    ResultSet rs = mock(ResultSet.class);
    int[] idx = {-1};
    when(rs.next()).thenAnswer(inv -> ++idx[0] < privs.length);
    when(rs.getString(1)).thenAnswer(inv -> privs[idx[0]]);
    return rs;
  }

  private Connection dbaConn(ResultSet rs) throws Exception {
    Connection conn = mock(Connection.class);
    DatabaseMetaData md = mock(DatabaseMetaData.class);
    when(md.getDatabaseProductVersion()).thenReturn("7.2.6");
    when(conn.getMetaData()).thenReturn(md);
    Statement st = mock(Statement.class);
    when(conn.createStatement()).thenReturn(st);
    when(st.executeQuery(anyString())).thenReturn(rs);
    when(connector.open(TARGET, "sys", "pw")).thenReturn(conn);
    return conn;
  }

  @Test
  void testDba_allPrivileges_ok() throws Exception {
    Connection conn =
        dbaConn(
            privilegeRows(
                "CREATE SESSION", "CREATE USER", "ALTER USER", "DROP USER", "GRANT ANY PRIVILEGE"));
    ConnectionTestResult r = tester.testDba(TARGET, "sys", "pw");
    assertThat(r.ok()).isTrue();
    assertThat(r.serverVersion()).isEqualTo("7.2.6");
    assertThat(r.missingPrivileges()).isEmpty();
    verify(conn).close();
  }

  @Test
  void testDba_missingPrivileges_listed() throws Exception {
    Connection conn = dbaConn(privilegeRows("CREATE USER"));
    ConnectionTestResult r = tester.testDba(TARGET, "sys", "pw");
    assertThat(r.ok()).isFalse();
    assertThat(r.missingPrivileges())
        .containsExactly("ALTER USER", "DROP USER", "GRANT ANY PRIVILEGE");
    verify(conn).close();
  }

  @Test
  void testDba_privilegeQueryFails_okWithWarning() throws Exception {
    Connection conn = mock(Connection.class);
    DatabaseMetaData md = mock(DatabaseMetaData.class);
    when(md.getDatabaseProductVersion()).thenReturn("7.2.6");
    when(conn.getMetaData()).thenReturn(md);
    Statement st = mock(Statement.class);
    when(conn.createStatement()).thenReturn(st);
    when(st.executeQuery(anyString())).thenThrow(new SQLException("table or view does not exist"));
    when(connector.open(TARGET, "sys", "pw")).thenReturn(conn);

    ConnectionTestResult r = tester.testDba(TARGET, "sys", "pw");
    assertThat(r.ok()).isTrue();
    assertThat(r.missingPrivileges()).isNull();
    assertThat(r.message()).contains("권한을 확인하지 못했습니다");
    verify(conn).close();
  }

  @Test
  void testDba_connectFails_okFalseNoThrow() throws Exception {
    when(connector.open(TARGET, "sys", "pw"))
        .thenThrow(new ExternalDbConnectionException("연결 실패: refused"));
    ConnectionTestResult r = tester.testDba(TARGET, "sys", "pw");
    assertThat(r.ok()).isFalse();
    assertThat(r.message()).isEqualTo("연결 실패: refused");
  }

  /** 계정별 가짜 커넥션·문장을 만들어 커넥터에 등록한다. */
  private Map<String, Connection> wireAccounts(
      List<PlannedAccount> accounts, Map<String, Statement> stmts) throws Exception {
    Map<String, Connection> conns = new HashMap<>();
    for (PlannedAccount a : accounts) {
      Connection c = mock(Connection.class);
      Statement st = mock(Statement.class);
      when(st.executeQuery(anyString())).thenReturn(mock(ResultSet.class));
      when(c.createStatement()).thenReturn(st);
      when(connector.open(TARGET, a.username(), a.password())).thenReturn(c);
      conns.put(a.username(), c);
      stmts.put(a.username(), st);
    }
    return conns;
  }

  private static List<PlannedAccount> accounts() {
    List<PlannedAccount> list = new ArrayList<>();
    for (DataStoreAccountRole role : DataStoreAccountRole.all()) {
      list.add(new PlannedAccount(role, role.usernameFor(5), "pw-" + role.name()));
    }
    return list;
  }

  @Test
  void verifyAccounts_setsCurrentSchemaForRoAndSlotsOnly() throws Exception {
    List<PlannedAccount> accounts = accounts();
    Map<String, Statement> stmts = new HashMap<>();
    Map<String, Connection> conns = wireAccounts(accounts, stmts);

    List<AccountCheck> checks = tester.verifyAccounts(TARGET, "GENIA_T5", accounts);

    assertThat(checks).hasSize(12).allMatch(AccountCheck::ok);
    String alter = "ALTER SESSION SET CURRENT_SCHEMA = \"GENIA_T5\"";
    verify(stmts.get("GENIA_T5"), never()).execute(eq(alter));
    verify(stmts.get("GENIA_T5")).executeQuery("SELECT 1 FROM DUAL");
    verify(stmts.get("GENIA_T5_RO")).execute(alter);
    verify(stmts.get("GENIA_T5_S10")).execute(alter);
    for (Connection c : conns.values()) {
      verify(c).close();
    }
  }

  @Test
  void verifyAccounts_reportsEachFailure() throws Exception {
    List<PlannedAccount> accounts = accounts();
    Map<String, Statement> stmts = new HashMap<>();
    Map<String, Connection> conns = wireAccounts(accounts, stmts);
    when(connector.open(TARGET, "GENIA_T5_S3", "pw-S3"))
        .thenThrow(new ExternalDbConnectionException("연결 실패: ORA-01017"));

    List<AccountCheck> checks = tester.verifyAccounts(TARGET, "GENIA_T5", accounts);

    assertThat(checks).filteredOn(c -> !c.ok()).hasSize(1);
    AccountCheck bad = checks.stream().filter(c -> !c.ok()).findFirst().orElseThrow();
    assertThat(bad.username()).isEqualTo("GENIA_T5_S3");
    assertThat(bad.message()).contains("ORA-01017").doesNotContain("pw-S3");
    for (PlannedAccount a : accounts) {
      if (!a.username().equals("GENIA_T5_S3")) {
        verify(conns.get(a.username())).close();
      }
    }
  }

  /**
   * 계정 검증은 최대 4개씩 동시에 한다 — 200ms 걸리는 계정 12개가 순차(2.4초)보다 훨씬 빨리 끝나야 하고, 결과는 끝난 순서가 아니라 입력 순서여야 한다. 앞
   * 계정일수록 늦게 끝나게 해 순서가 섞일 기회를 만든다.
   */
  @Test
  void verifyAccounts_runsConcurrentlyBounded_resultsInInputOrder() throws Exception {
    List<PlannedAccount> accounts = accounts();
    AtomicInteger inFlight = new AtomicInteger();
    AtomicInteger maxInFlight = new AtomicInteger();
    for (int i = 0; i < accounts.size(); i++) {
      PlannedAccount a = accounts.get(i);
      long delayMs = 200 + (accounts.size() - i) * 10L;
      Connection c = mock(Connection.class);
      Statement st = mock(Statement.class);
      when(st.executeQuery(anyString())).thenReturn(mock(ResultSet.class));
      when(c.createStatement()).thenReturn(st);
      when(connector.open(TARGET, a.username(), a.password()))
          .thenAnswer(
              inv -> {
                int now = inFlight.incrementAndGet();
                maxInFlight.accumulateAndGet(now, Math::max);
                try {
                  Thread.sleep(delayMs);
                } finally {
                  inFlight.decrementAndGet();
                }
                if (a.username().equals("GENIA_T5_S3")) {
                  throw new ExternalDbConnectionException("연결 실패: ORA-01017");
                }
                return c;
              });
    }
    ExecutorService pool = DataStoreConnectionTester.newVerifyExecutor();
    DataStoreConnectionTester parallel = new DataStoreConnectionTester(connector, pool);
    try {
      long start = System.nanoTime();
      List<AccountCheck> checks = parallel.verifyAccounts(TARGET, "GENIA_T5", accounts);
      long elapsedMs = (System.nanoTime() - start) / 1_000_000;

      assertThat(elapsedMs).isLessThan(1500);
      assertThat(maxInFlight.get())
          .isLessThanOrEqualTo(DataStoreConnectionTester.VERIFY_PARALLELISM);
      assertThat(checks)
          .extracting(AccountCheck::username)
          .containsExactlyElementsOf(accounts.stream().map(PlannedAccount::username).toList());
      // 실패 결과가 제 계정 자리에 있다.
      assertThat(checks)
          .filteredOn(c -> !c.ok())
          .extracting(AccountCheck::username)
          .containsExactly("GENIA_T5_S3");
    } finally {
      pool.shutdownNow();
    }
  }

  /** 검증 중 예기치 않은 비검사 예외는 순차 실행 때와 같이 그대로 던진다(ExecutionException 으로 감싸지 않는다). */
  @Test
  void verifyAccounts_unexpectedRuntimeException_propagatesAsIs() throws Exception {
    List<PlannedAccount> accounts = accounts();
    wireAccounts(accounts, new HashMap<>());
    IllegalStateException boom = new IllegalStateException("driver bug");
    when(connector.open(TARGET, "GENIA_T5_RO", "pw-RO")).thenThrow(boom);

    assertThatThrownBy(() -> tester.verifyAccounts(TARGET, "GENIA_T5", accounts)).isSameAs(boom);
  }

  /** 응답 없는 계정은 시간 제한 뒤 실패 항목이 되고, 나머지 계정은 정상 결과를 돌려준다(멈추지 않는다). */
  @Test
  void verifyAccounts_hangingAccount_timesOutAsFailedCheck() throws Exception {
    List<PlannedAccount> accounts = accounts();
    wireAccounts(accounts, new HashMap<>());
    when(connector.open(TARGET, "GENIA_T5_S3", "pw-S3"))
        .thenAnswer(
            inv -> {
              Thread.sleep(30_000);
              return null;
            });
    ExecutorService pool = DataStoreConnectionTester.newVerifyExecutor();
    DataStoreConnectionTester shortTimeout = new DataStoreConnectionTester(connector, pool, 1);
    try {
      long start = System.nanoTime();
      List<AccountCheck> checks = shortTimeout.verifyAccounts(TARGET, "GENIA_T5", accounts);

      assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(10_000);
      assertThat(checks).hasSize(12);
      assertThat(checks)
          .filteredOn(c -> !c.ok())
          .containsExactly(new AccountCheck("GENIA_T5_S3", false, "계정 확인 시간이 초과되었습니다"));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void testDba_closeFailsAfterSuccess_stillOk() throws Exception {
    Connection conn =
        dbaConn(privilegeRows("CREATE USER", "ALTER USER", "DROP USER", "GRANT ANY PRIVILEGE"));
    org.mockito.Mockito.doThrow(new SQLException("close failed")).when(conn).close();
    ConnectionTestResult r = tester.testDba(TARGET, "sys", "pw");
    assertThat(r.ok()).isTrue();
    assertThat(r.missingPrivileges()).isEmpty();
  }

  @Test
  void verifyAccounts_closeFailsAfterSuccess_stillOk() throws Exception {
    List<PlannedAccount> accounts = accounts();
    Map<String, Statement> stmts = new HashMap<>();
    Map<String, Connection> conns = wireAccounts(accounts, stmts);
    org.mockito.Mockito.doThrow(new SQLException("close failed"))
        .when(conns.get("GENIA_T5_RO"))
        .close();
    List<AccountCheck> checks = tester.verifyAccounts(TARGET, "GENIA_T5", accounts);
    assertThat(checks).allMatch(AccountCheck::ok);
  }
}
