package com.smartfirehub.support.tibero;

import com.smartfirehub.dataset.storage.DataStorePasswordGenerator;
import com.smartfirehub.dataset.storage.tibero.TiberoNames;
import com.smartfirehub.support.tibero.TiberoTestServer.Dba;
import com.zaxxer.hikari.HikariDataSource;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.regex.Pattern;

/**
 * Tibero 실서버 테스트용 일회성 계정(= 스키마)(WD-497).
 *
 * <p>무엇을: DBA 로 {@code FHT_<용도>_<접미사6>} 계정을 만들고 그 계정의 작은 풀을 준다. {@link #close()} 는 풀을 닫고 이 인스턴스가 만든
 * 바로 그 계정만 지운다.
 *
 * <p>공유 서버 규칙(DCIM 팀과 함께 쓴다):
 *
 * <ul>
 *   <li>이름에 실행마다 다른 접미사를 붙인다 — 여러 JVM·여러 사람이 동시에 돌려도 서로 부딪치지 않는다.
 *   <li>지울 때는 이름과 만든 직후 읽은 생성 시각({@code DBA_USERS.CREATED})이 모두 같을 때만 지운다. 이름이 같아도 시각이 다르면 다른 주체의
 *       계정이다.
 *   <li>이전 실행이 죽어 남긴 {@code FHT_*} 계정은 자동으로 지우지 않고 개수만 증거로 남긴다. {@code DCIM_*} 등 다른 계정은 보지도 않는다.
 *   <li>비밀번호는 실행 중에만 만들어 쓰고 어디에도 남기지 않는다. 비밀번호가 든 문장의 오류는 원문 대신 오류 코드만 남긴다.
 * </ul>
 */
public final class TiberoTestAccount implements AutoCloseable {

  /** 모든 테스트 계정 이름의 접두사. */
  public static final String PREFIX = "FHT_";

  /** 실행마다 바뀌는 접미사를 증거에서 가리는 패턴. */
  private static final Pattern NAME = Pattern.compile("(" + PREFIX + "[A-Z0-9]+_)[A-Z0-9]{6}");

  /** 용도 이름 규칙 — 대문자·숫자, 계정 이름이 30자를 넘지 않게 짧게. */
  private static final Pattern PURPOSE = Pattern.compile("[A-Z0-9]{1,16}");

  private static final SecureRandom RANDOM = new SecureRandom();

  /** 계정에 줄 권한 묶음. */
  public enum Privileges {
    /** 테이블·시퀀스 — 리포지토리 계약 테스트. */
    TABLES("CREATE SESSION, CREATE TABLE, CREATE SEQUENCE"),
    /**
     * {@link #TABLES} + {@code SYS.DBMS_LOCK} 실행 권한 — 플랫폼 잠금(WD-493) 실서버 테스트. 객체 권한이라 시스템 권한 GRANT
     * 뒤 DBA 연결로 이번 실행의 계정에만 따로 준다.
     */
    TABLES_WITH_DBMS_LOCK("CREATE SESSION, CREATE TABLE, CREATE SEQUENCE"),
    /**
     * 마이그레이션 소유자 — 테이블·시퀀스·뷰·프로시저·트리거(clean 이 지울 객체 종류를 고루 만든다), 구체화 뷰(clean 이 다루지 않는 종류를 만나면 멈추는지
     * 본다).
     */
    MIGRATION_OWNER(
        "CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE VIEW, CREATE PROCEDURE,"
            + " CREATE TRIGGER, CREATE MATERIALIZED VIEW");

    private final String grants;

    Privileges(String grants) {
      this.grants = grants;
    }
  }

  private final Dba dba;
  private final String name;
  private final Timestamp created;
  private final HikariDataSource dataSource;

  private TiberoTestAccount(
      Dba dba, String name, Timestamp created, String password, int poolSize) {
    this.dba = dba;
    this.name = name;
    this.created = created;
    this.dataSource = TiberoTestServer.pool(name.toLowerCase(), dba, name, password, poolSize);
  }

  /**
   * 드라이버가 없으면 건너뛰고, 있으면 이번 실행만의 계정을 새로 만든다.
   *
   * @param purpose 용도(대문자·숫자 1~16자) — 계정 이름에 들어가 누가 만든 계정인지 보인다
   * @param privileges 줄 권한 묶음
   * @param poolSize 풀 크기 — 공유 서버 세션 한도 때문에 2~3 으로 둔다
   */
  public static TiberoTestAccount create(String purpose, Privileges privileges, int poolSize) {
    TiberoTestServer.assumeDriverPresent();
    if (!PURPOSE.matcher(purpose).matches()) {
      throw new IllegalArgumentException("용도는 대문자·숫자 1~16자: " + purpose);
    }
    Dba dba = TiberoTestServer.dbaFromEnv();
    String name = PREFIX + purpose + "_" + suffix();
    String password = new DataStorePasswordGenerator().generate();
    Timestamp created;
    try (Connection c = TiberoTestServer.openDba(dba)) {
      long stale = countAccounts(c, PREFIX + purpose + "\\_%");
      if (stale > 0) {
        TiberoTestEvidence.write(
            "이전 실행이 남긴 " + PREFIX + purpose + "_* 계정 " + stale + "개 — 자동으로 지우지 않음");
      }
      if (createdAt(c, name) != null) {
        throw new IllegalStateException(name + " 계정이 이미 있다 — 접미사 충돌, 건드리지 않는다");
      }
      try {
        createAccount(c, name, password, privileges);
      } catch (SQLException e) {
        // CREATE USER 는 됐는데 QUOTA·GRANT 가 실패했을 수 있다. 바로 위에서 없음을 확인한 고유 이름이라 지금 있는 계정은 이 실행이 만든 것이다.
        if (createdAt(c, name) != null) dropUser(c, name);
        throw e;
      }
      created = createdAt(c, name);
    } catch (SQLException e) {
      // 원인 예외를 붙이지 않는다 — CREATE USER 문장(비밀번호 포함)이 드라이버 오류 메시지에 섞일 수 있다.
      throw new IllegalStateException("테스트 계정 준비 실패(오류 코드 " + e.getErrorCode() + ")");
    }
    try {
      return new TiberoTestAccount(dba, name, created, password, poolSize);
    } catch (RuntimeException e) {
      // 풀 생성이 실패해도 방금 만든 계정은 남기지 않는다.
      dropOwned(dba, name, created);
      throw e;
    }
  }

  /** 이번 실행의 계정(= 스키마) 이름. */
  public String name() {
    return name;
  }

  /** 계정 풀. 빌린 커넥션은 반드시 닫는다. */
  public HikariDataSource dataSource() {
    return dataSource;
  }

  /** 계정으로 문장 하나를 실행한다(픽스처용, 자동 커밋). */
  public void execute(String sql) throws SQLException {
    try (Connection c = dataSource.getConnection();
        Statement st = c.createStatement()) {
      st.execute(sql);
    }
  }

  /** 계정이 가진 객체 수(종류 무관). 휴지통(BIN$) 객체는 뺀다. */
  public long objectCount() throws SQLException {
    return count(
        "SELECT COUNT(*) FROM ALL_OBJECTS WHERE OWNER = ? AND OBJECT_NAME NOT LIKE 'BIN$%'", name);
  }

  /** 계정 소유 테이블이 있으면 true. */
  public boolean tableExists(String upperName) throws SQLException {
    return count("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME = ?", upperName) > 0;
  }

  /** 이름이 주어진 계정이 서버에 있으면 true(이 계정 권한으로 ALL_USERS 조회). */
  public boolean userExists(String userName) throws SQLException {
    return count("SELECT COUNT(*) FROM ALL_USERS WHERE USERNAME = ?", userName) > 0;
  }

  /**
   * 풀을 닫고(세션이 남으면 DROP USER 가 거절된다) 이 인스턴스가 만든 계정만 지운 뒤 남은 수를 증거로 남긴다. 이름이 같아도 생성 시각이 다르면(다른 주체가 같은
   * 이름으로 다시 만든 계정) 지우지 않는다.
   */
  @Override
  public void close() {
    try {
      dataSource.close();
    } finally {
      dropOwned(dba, name, created);
    }
  }

  /**
   * 서버에 남은 {@code FHT_<용도>_*} 계정 수를 센다(읽기 전용 — 지우지 않는다). 테스트가 끝난 뒤 "이번 실행이 계정을 남기지 않았는지"를 증거로 남기는 데
   * 쓴다. 밑줄을 이스케이프해 {@code FHT_LOCKS_*} 가 {@code FHT_LOCKSX_*} 같은 다른 용도까지 세지 않게 한다.
   */
  public static long remainingAccounts(String purpose) {
    if (!PURPOSE.matcher(purpose).matches()) {
      throw new IllegalArgumentException("용도는 대문자·숫자 1~16자: " + purpose);
    }
    try (Connection c = TiberoTestServer.openDbaForCleanup(TiberoTestServer.dbaFromEnv())) {
      return countAccounts(c, PREFIX.replace("_", "\\_") + purpose + "\\_%");
    } catch (SQLException e) {
      throw new IllegalStateException("계정 수 조회 실패(오류 코드 " + e.getErrorCode() + ")", e);
    }
  }

  /** 증거·로그 문자열에서 테스트 계정의 실행별 접미사를 가린다. */
  static String mask(String line) {
    return NAME.matcher(line).replaceAll("$1*");
  }

  // ── 내부 ──────────────────────────────────────────────

  /** 이름 + 생성 시각이 같을 때만 지우고, 남은 수를 증거로 남긴다. */
  private static void dropOwned(Dba dba, String name, Timestamp created) {
    try (Connection c = TiberoTestServer.openDbaForCleanup(dba)) {
      Timestamp now = createdAt(c, name);
      if (now != null && now.equals(created)) {
        dropUser(c, name);
      } else if (now != null) {
        TiberoTestEvidence.write(name + " 생성 시각이 달라 지우지 않음");
      }
      // 정확한 이름으로 다시 본다 — LIKE 로 세면 이름의 '_' 가 한 글자 와일드카드라 다른 계정까지 셀 수 있다.
      TiberoTestEvidence.write(name + " remaining count=" + (createdAt(c, name) == null ? 0 : 1));
    } catch (SQLException e) {
      TiberoTestEvidence.write(name + " 정리 실패(오류 코드 " + e.getErrorCode() + ")");
      throw new IllegalStateException("테스트 계정 정리 실패(오류 코드 " + e.getErrorCode() + ")", e);
    }
  }

  private long count(String sql, String arg) throws SQLException {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, arg);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  /** 계정 이름 접미사 — 대문자·숫자 6자(인용 없이도 유효한 식별자). */
  private static String suffix() {
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    StringBuilder b = new StringBuilder(6);
    for (int i = 0; i < 6; i++) b.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
    return b.toString();
  }

  /** 계정 생성 시각, 없으면 null. */
  private static Timestamp createdAt(Connection c, String user) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement("SELECT CREATED FROM DBA_USERS WHERE USERNAME = ?")) {
      ps.setString(1, user);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getTimestamp(1) : null;
      }
    }
  }

  /** LIKE 패턴에 맞는 계정 수(이전 실행이 남긴 계정 세기용). 패턴의 {@code \_} 는 밑줄 그대로다. */
  private static long countAccounts(Connection c, String pattern) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement("SELECT COUNT(*) FROM DBA_USERS WHERE USERNAME LIKE ? ESCAPE '\\'")) {
      ps.setString(1, pattern);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private static void dropUser(Connection c, String name) throws SQLException {
    try (Statement st = c.createStatement()) {
      st.execute("DROP USER " + TiberoNames.quote(name) + " CASCADE");
    }
  }

  /** 계정 생성 + 권한. 비밀번호가 든 문장은 오류 메시지에 SQL 이 섞일 수 있어 호출자가 오류 코드만 남긴다. */
  private static void createAccount(
      Connection c, String name, String password, Privileges privileges) throws SQLException {
    String account = TiberoNames.quote(name);
    String ts = TiberoNames.quote(TiberoTestServer.tablespace());
    try (Statement st = c.createStatement()) {
      st.execute(
          "CREATE USER "
              + account
              + " IDENTIFIED BY "
              + TiberoNames.quote(password)
              + " DEFAULT TABLESPACE "
              + ts);
      st.execute("ALTER USER " + account + " QUOTA UNLIMITED ON " + ts);
      st.execute("GRANT " + privileges.grants + " TO " + account);
      if (privileges == Privileges.TABLES_WITH_DBMS_LOCK) {
        // 이번 실행이 방금 만든 계정에만 준다. 실패하면 호출자의 "생성 실패면 이번 계정 정리" 경로를 탄다.
        st.execute("GRANT EXECUTE ON SYS.DBMS_LOCK TO " + account);
      }
    }
  }
}
