package com.smartfirehub.dataset.storage.external;

import com.smartfirehub.dataset.storage.tibero.PlannedAccount;
import com.smartfirehub.dataset.storage.tibero.ProvisioningStep;
import com.smartfirehub.dataset.storage.tibero.TiberoAccountPlan;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * DBA 자격으로 외부 DB 에 테넌트 계정을 만드는 실행기(WD-73).
 *
 * <p>단계 도중 실패하면 지금까지 만든 계정을 같은 커넥션에서 역순 DROP 해 고객 DB 에 고아 계정이 남지 않게 한다. 로그에는 단계 설명과 계정 이름만 남기고,
 * SQL({@code IDENTIFIED BY} 포함)과 비밀번호는 남기지 않는다.
 *
 * <p><b>보상은 이번 실행이 만든 계정만 지운다.</b> {@code DROP USER … CASCADE} 는 그 계정의 스키마(고객 데이터)까지 지우므로, 플랫폼이 만들지
 * 않은 계정(같은 id 로 앞서 돌린 SCRIPT 모드 계정, 같은 Tibero 를 쓰는 다른 환경의 계정 등)을 건드리면 복구할 수 없다. 그래서
 *
 * <ul>
 *   <li>DDL 전에 계획한 12개 계정 이름이 이미 있는지 확인하고, 하나라도 있으면 아무것도 만들거나 지우지 않고 실패한다.
 *   <li>실패한 {@code CREATE USER} 단계의 계정은 서버가 실제로 만들었을 수 있는 <b>모호한 실패</b>(쿼리 시간 초과·연결 오류·드라이버 비검사
 *       예외)일 때만 보상 대상에 넣는다. 서버가 거절한 일반 SQL 오류(예: ORA-01920 이름 충돌)는 만들어지지 않았으므로 넣지 않는다.
 * </ul>
 *
 * <p>사전 확인과 실제 생성 사이에 다른 주체가 같은 이름을 만드는 경합은 남지만, 그 경우 {@code CREATE USER} 가 서버 거절로 실패해 보상 대상에 들어가지
 * 않는다. {@code ALL_USERS} 조회는 Oracle 호환을 가정한 것이며 실서버 확인은 WD-71 에서 한다.
 */
@Slf4j
@Service
public class ExternalAccountProvisioner {

  private static final int QUERY_TIMEOUT_SECONDS = 10;

  private final ExternalDbConnector connector;

  public ExternalAccountProvisioner(ExternalDbConnector connector) {
    this.connector = connector;
  }

  /**
   * 계획의 프로비저닝 단계를 순서대로 실행하고 만든 계정 이름을 돌려준다(DDL 은 자동 커밋).
   *
   * @throws DataStoreProvisioningException 접속 실패 또는 단계 실패(이미 만든 계정은 DROP 한 뒤)
   */
  public List<String> provision(
      ExternalDbTarget target, String dbaUser, String dbaPassword, TiberoAccountPlan plan) {
    Connection conn = openOrThrow(target, dbaUser, dbaPassword);
    List<String> created = new ArrayList<>();
    try (conn) {
      try {
        conn.setAutoCommit(true);
      } catch (SQLException e) {
        throw new DataStoreProvisioningException("DBA 세션 설정 실패: " + e.getMessage());
      }
      // 이미 있는 계정이 하나라도 있으면 DDL 을 하나도 실행하지 않는다 — 보상 DROP 이 남의 계정을 지우는 일을 원천 차단한다.
      requireNoExistingAccounts(conn, plan);
      for (ProvisioningStep step : plan.provisioningSteps()) {
        try {
          execute(conn, step.sql());
          if (step.createdUsername() != null) {
            created.add(step.createdUsername());
          }
        } catch (SQLException | RuntimeException e) {
          log.error("외부 저장소 계정 생성 단계 실패: {}", step.description());
          List<String> toDrop = new ArrayList<>(created);
          // 쿼리 시간 초과·연결 오류처럼 서버가 CREATE USER 를 끝냈을 수 있는 경우에만 그 계정도 보상 대상에 넣는다.
          // 서버가 거절한 오류면 계정은 만들어지지 않았으므로 지우면 안 된다(사전 확인 뒤 다른 주체가 만든 계정일 수 있다).
          if (step.createdUsername() != null && mayHaveCompleted(e)) {
            toDrop.add(step.createdUsername());
          }
          dropQuietly(conn, plan, toDrop);
          throw new DataStoreProvisioningException(
              step.description() + " 실패: " + scrub(e.getMessage(), plan));
        }
      }
      return created;
    } catch (SQLException e) {
      // try-with-resources 의 close 실패. 생성은 끝났으므로 결과에 영향이 없다.
      log.warn("외부 DB 커넥션 닫기 실패");
      return created;
    }
  }

  /**
   * 주어진 계정을 best-effort 로 DROP 한다. 어떤 실패도 예외로 던지지 않고 실패한 계정만 ERROR 로 남긴다.
   *
   * <p>테넌트 트랜잭션 롤백 뒤 보상 등 이미 실패 경로에서 쓰이므로 여기서 던지면 원래 오류를 가린다.
   */
  public void dropAccounts(
      ExternalDbTarget target,
      String dbaUser,
      String dbaPassword,
      TiberoAccountPlan plan,
      Collection<String> usernames) {
    try {
      Connection conn = connector.open(target, dbaUser, dbaPassword);
      try (conn) {
        dropQuietly(conn, plan, usernames);
      }
    } catch (ExternalDbConnectionException e) {
      log.error("외부 저장소 계정 삭제를 위한 접속 실패: {}", usernames);
    } catch (Exception e) {
      log.error("외부 저장소 계정 삭제 중 오류: {}", usernames);
    }
  }

  /**
   * 계획한 계정 이름이 외부 DB 에 이미 있는지 확인한다.
   *
   * @throws DataStoreProvisioningException 이미 있는 계정이 있음(이름 나열) 또는 확인 쿼리 자체가 실패함 — 확인하지 못한 채로 진행하지
   *     않는다
   */
  private static void requireNoExistingAccounts(Connection conn, TiberoAccountPlan plan) {
    List<String> planned = plan.accounts().stream().map(PlannedAccount::username).toList();
    String sql =
        "SELECT USERNAME FROM ALL_USERS WHERE USERNAME IN ("
            + String.join(", ", Collections.nCopies(planned.size(), "?"))
            + ")";
    Set<String> existing = new HashSet<>();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
      for (int i = 0; i < planned.size(); i++) {
        ps.setString(i + 1, planned.get(i));
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          existing.add(rs.getString(1));
        }
      }
    } catch (SQLException | RuntimeException e) {
      throw new DataStoreProvisioningException(
          "계정 존재 여부를 확인하지 못했습니다: " + scrub(e.getMessage(), plan));
    }
    if (!existing.isEmpty()) {
      // 계획 순서(OWNER, RO, S1..)로 나열해 메시지가 결정적이게 한다.
      List<String> found = planned.stream().filter(existing::contains).toList();
      log.error("외부 저장소에 같은 이름의 계정이 이미 있어 생성하지 않음: {}", found);
      throw new DataStoreProvisioningException(
          "이미 존재하는 계정이 있어 생성하지 않았습니다: " + String.join(", ", found));
    }
  }

  /**
   * 실패한 문장을 서버가 실제로는 끝냈을 수 있는지(모호한 실패) 판정한다.
   *
   * <p>쿼리 시간 초과·연결 계열 오류(SQLState {@code 08xxx})·드라이버 비검사 예외는 응답만 못 받았을 수 있어 참이다. 그 밖의 {@link
   * SQLException} 은 서버가 문장을 거절한 것이라 거짓이다.
   */
  static boolean mayHaveCompleted(Exception e) {
    if (e instanceof RuntimeException) {
      return true;
    }
    if (e instanceof SQLTimeoutException
        || e instanceof SQLRecoverableException
        || e instanceof SQLTransientConnectionException
        || e instanceof SQLNonTransientConnectionException) {
      return true;
    }
    if (e instanceof SQLException sql) {
      String state = sql.getSQLState();
      return state != null && state.startsWith("08");
    }
    return false;
  }

  private Connection openOrThrow(ExternalDbTarget target, String user, String password) {
    try {
      return connector.open(target, user, password);
    } catch (ExternalDbConnectionException e) {
      throw new DataStoreProvisioningException("DBA 접속 실패: " + e.getMessage());
    }
  }

  /** 문장 하나를 타임아웃과 함께 실행하고 항상 닫는다. */
  private static void execute(Connection conn, String sql) throws SQLException {
    try (Statement st = conn.createStatement()) {
      st.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
      st.execute(sql);
    }
  }

  /** 보상 DROP. 나중에 만든 계정부터(입력의 역순) 지운다. 각 DROP 이 실패해도 계속하고 실패한 계정 이름만 ERROR 로 남긴다. */
  private void dropQuietly(Connection conn, TiberoAccountPlan plan, Collection<String> usernames) {
    for (String username : new ArrayList<>(usernames).reversed()) {
      try {
        execute(conn, plan.dropStatement(username));
      } catch (SQLException e) {
        log.error("외부 저장소 계정 보상 삭제 실패: {}", username);
      }
    }
  }

  /** 드라이버가 SQL 을 되풀이해 보여 줄 가능성에 대비해 계획의 비밀번호를 가린다. */
  private static String scrub(String message, TiberoAccountPlan plan) {
    String m = message == null ? "알 수 없는 오류" : message;
    for (PlannedAccount a : plan.accounts()) {
      if (a.password() != null && !a.password().isEmpty()) {
        m = m.replace(a.password(), "***");
      }
    }
    // 비밀번호가 중간에 잘려 되풀이된 경우까지 막기 위해 IDENTIFIED BY 이후는 모두 버린다.
    int idx = m.toUpperCase(Locale.ROOT).indexOf("IDENTIFIED BY");
    if (idx >= 0) {
      m = m.substring(0, idx) + "…";
    }
    return m;
  }
}
