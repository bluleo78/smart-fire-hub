package com.smartfirehub.dataset.storage.external;

import com.smartfirehub.dataset.storage.DataStoreAccountRole;
import com.smartfirehub.dataset.storage.tibero.PlannedAccount;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 외부 저장소 연결 테스트(WD-73): DBA 접속·권한 확인과 테넌트 계정별 로그인 검증.
 *
 * <p>모든 접속은 1회성이며 항상 닫는다. 비밀번호는 로그·메시지에 남기지 않는다.
 *
 * <p>계정 검증({@link #verifyAccounts})은 계정당 접속·쿼리에 각각 최대 10초가 걸릴 수 있어 12개를 차례로 하면 2분을 넘길 수 있다. 그래서 이
 * 객체가 소유한 작은 데몬 풀({@value #VERIFY_PARALLELISM}개)에서 동시에 확인한다. 상한을 두는 이유는 고객 DB 에 한꺼번에 접속을 몰아 세션
 * 한도·접속 폭주 감지에 걸리지 않게 하려는 것이다.
 */
@Service
public class DataStoreConnectionTester {

  /** 계정 자동 생성에 필요한 DBA 권한. */
  static final List<String> REQUIRED_PRIVILEGES =
      List.of("CREATE USER", "ALTER USER", "DROP USER", "GRANT ANY PRIVILEGE");

  private static final int QUERY_TIMEOUT_SECONDS = 10;

  /** 계정 검증 동시 접속 상한. */
  static final int VERIFY_PARALLELISM = 4;

  /**
   * 계정 하나를 기다리는 최대 시간(초). 접속 10초 + 문장 10초씩(쿼리·ALTER SESSION) + 여유 5초. 응답 없는 계정 하나가 요청 전체를 붙잡지 않게
   * 한다.
   */
  static final long ACCOUNT_CHECK_TIMEOUT_SECONDS = 35;

  private final ExternalDbConnector connector;

  /** 계정 하나를 기다리는 시간(초). 운영은 {@link #ACCOUNT_CHECK_TIMEOUT_SECONDS}, 테스트는 줄여 쓴다. */
  private final long accountCheckTimeoutSeconds;

  /** 계정 검증 전용 풀. 응답 없는 접속이 JVM 종료를 막지 않도록 데몬 스레드를 쓴다. */
  private final ExecutorService verifyExecutor;

  @Autowired
  public DataStoreConnectionTester(ExternalDbConnector connector) {
    this(connector, newVerifyExecutor(), ACCOUNT_CHECK_TIMEOUT_SECONDS);
  }

  /** 테스트용: 검증 풀을 주입해 테스트가 직접 종료할 수 있게 한다. */
  DataStoreConnectionTester(ExternalDbConnector connector, ExecutorService verifyExecutor) {
    this(connector, verifyExecutor, ACCOUNT_CHECK_TIMEOUT_SECONDS);
  }

  /** 테스트용: 계정별 대기 시간까지 주입한다. */
  DataStoreConnectionTester(
      ExternalDbConnector connector,
      ExecutorService verifyExecutor,
      long accountCheckTimeoutSeconds) {
    this.connector = connector;
    this.verifyExecutor = verifyExecutor;
    this.accountCheckTimeoutSeconds = accountCheckTimeoutSeconds;
  }

  /** 이름 {@code data-store-verify-N} 의 데몬 스레드 {@value #VERIFY_PARALLELISM}개짜리 고정 풀. */
  static ExecutorService newVerifyExecutor() {
    AtomicInteger seq = new AtomicInteger(1);
    return Executors.newFixedThreadPool(
        VERIFY_PARALLELISM,
        r -> {
          Thread t = new Thread(r, "data-store-verify-" + seq.getAndIncrement());
          t.setDaemon(true);
          return t;
        });
  }

  /** 빈 종료 시 검증 스레드를 정리한다. */
  @PreDestroy
  void shutdown() {
    verifyExecutor.shutdownNow();
  }

  /**
   * DBA 자격으로 접속해 버전과 필요 권한을 확인한다. 접속 실패는 예외 대신 {@code ok=false} 로 돌려준다.
   *
   * <p>권한 조회 자체가 실패하면(뷰 접근 불가 등) 접속은 된 것이므로 {@code ok=true, missingPrivileges=null} 로 경고만 낸다.
   */
  public ConnectionTestResult testDba(ExternalDbTarget target, String username, String password) {
    Connection conn;
    try {
      conn = connector.open(target, username, password);
    } catch (ExternalDbConnectionException e) {
      return new ConnectionTestResult(false, null, null, e.getMessage());
    }
    try {
      String version = null;
      try {
        version = conn.getMetaData().getDatabaseProductVersion();
      } catch (SQLException ignored) {
        // 버전은 부가 정보라 못 읽어도 진행한다.
      }
      try {
        Set<String> granted = readSessionPrivileges(conn);
        List<String> missing = new ArrayList<>();
        for (String required : REQUIRED_PRIVILEGES) {
          if (!granted.contains(required)) {
            missing.add(required);
          }
        }
        String msg = missing.isEmpty() ? "연결 성공" : "부족한 권한: " + String.join(", ", missing);
        return new ConnectionTestResult(missing.isEmpty(), version, missing, msg);
      } catch (SQLException e) {
        return new ConnectionTestResult(
            true, version, null, "접속은 됐지만 권한을 확인하지 못했습니다: " + e.getMessage());
      }
    } finally {
      // 닫기 실패는 검사 결과와 무관하므로 삼킨다.
      JdbcClose.closeQuietly(conn);
    }
  }

  /** 세션 권한 이름을 대문자 집합으로 읽는다. */
  private Set<String> readSessionPrivileges(Connection conn) throws SQLException {
    Set<String> granted = new HashSet<>();
    try (Statement st = conn.createStatement()) {
      st.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
      try (ResultSet rs = st.executeQuery("SELECT PRIVILEGE FROM SESSION_PRIVS")) {
        while (rs.next()) {
          String p = rs.getString(1);
          if (p != null) {
            granted.add(p.trim().toUpperCase(Locale.ROOT));
          }
        }
      }
    }
    return granted;
  }

  /**
   * 각 계정으로 접속해 {@code SELECT 1 FROM DUAL} 을 실행한다. RO·슬롯은 소유 스키마로 {@code CURRENT_SCHEMA} 도 맞춰 본다.
   *
   * <p>한 계정이 실패해도 나머지를 계속 검증하고 항목별로 결과를 돌려준다. 계정들은 최대 {@value #VERIFY_PARALLELISM}개씩 동시에 확인하지만 결과는
   * 입력 순서 그대로다.
   *
   * <p>검증 중 예기치 않은 비검사 예외는 순차로 돌릴 때와 같이 그대로 던진다. 기다리는 동안 인터럽트되면 남은 확인을 취소하고 인터럽트 표시를 되살린 뒤 던진다.
   */
  public List<AccountCheck> verifyAccounts(
      ExternalDbTarget target, String schemaName, List<PlannedAccount> accounts) {
    List<Future<AccountCheck>> futures = new ArrayList<>(accounts.size());
    for (PlannedAccount account : accounts) {
      futures.add(verifyExecutor.submit(() -> verifyOne(target, schemaName, account)));
    }
    List<AccountCheck> results = new ArrayList<>(accounts.size());
    try {
      // 제출 순서대로 기다려 입력 순서를 유지한다.
      for (int i = 0; i < futures.size(); i++) {
        Future<AccountCheck> f = futures.get(i);
        try {
          results.add(f.get(accountCheckTimeoutSeconds, TimeUnit.SECONDS));
        } catch (TimeoutException e) {
          // 응답 없는 계정은 취소(인터럽트)하고 실패 항목으로 바꾼 뒤 나머지를 계속 확인한다.
          f.cancel(true);
          results.add(new AccountCheck(accounts.get(i).username(), false, "계정 확인 시간이 초과되었습니다"));
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      futures.forEach(f -> f.cancel(true));
      throw new IllegalStateException("계정 검증 대기 중 중단되었습니다", e);
    } catch (ExecutionException e) {
      futures.forEach(f -> f.cancel(true));
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException re) {
        throw re;
      }
      if (cause instanceof Error err) {
        throw err;
      }
      // verifyOne 은 검사 예외를 던지지 않으므로 여기에 오지 않는다.
      throw new IllegalStateException("계정 검증 중 예기치 않은 오류", cause);
    }
    return results;
  }

  private AccountCheck verifyOne(ExternalDbTarget target, String schemaName, PlannedAccount a) {
    Connection conn;
    try {
      conn = connector.open(target, a.username(), a.password());
    } catch (ExternalDbConnectionException e) {
      return new AccountCheck(a.username(), false, e.getMessage());
    }
    try (Statement st = conn.createStatement()) {
      st.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
      try (ResultSet rs = st.executeQuery("SELECT 1 FROM DUAL")) {
        // 결과 내용은 보지 않고 실행 가능 여부만 확인한다.
      }
      if (a.role() != DataStoreAccountRole.OWNER) {
        st.execute("ALTER SESSION SET CURRENT_SCHEMA = \"" + schemaName + "\"");
      }
      return new AccountCheck(a.username(), true, "접속 성공");
    } catch (SQLException e) {
      return new AccountCheck(a.username(), false, "검증 실패: " + e.getMessage());
    } finally {
      JdbcClose.closeQuietly(conn);
    }
  }
}
