package com.smartfirehub.global.platformdb.tibero.flyway;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.smartfirehub.dataset.storage.DataStorePasswordGenerator;
import com.smartfirehub.dataset.storage.tibero.TiberoDialect;
import com.smartfirehub.dataset.storage.tibero.TiberoNames;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;

/**
 * Flyway·JobRunr 의 Tibero 동작을 재는 PoC 전용 계정(WD-488).
 *
 * <p>무엇을: 환경변수 {@code TIBERO_TEST_HOST/PORT/SID/DBA_USER/DBA_PASSWORD}(선택 {@code
 * TIBERO_TEST_TABLESPACE})의 DBA 로 이번 실행만의 계정 {@code WD488_POC_xxxxxx} 를 만들고, 그 계정의 작은 Hikari 풀을
 * 빌려준다. {@link #close()} 가 풀을 닫고 그 계정을 지운다. 시험마다 새 계정을 만들어 앞 시험의 잔재(반쯤 적용된 마이그레이션 등)가 다음 시험을 오염시키지
 * 않게 한다.
 *
 * <p>공유 서버 규칙: 세션 20개를 다른 팀과 나눠 쓰므로 풀은 작게 잡고 DBA 연결은 준비·정리 때만 연다. 계정 이름에 실행마다 다른 접미사를 붙이고, 지울 때는 이
 * 인스턴스가 만든 바로 그 계정(이름 + 만든 직후 읽은 생성 시각이 모두 같을 때)만 지운다 — 같은 시기에 다른 사람이 이 테스트를 돌려도 서로의 계정을 지우지 않는다.
 * 이전 실행이 죽어 남긴 계정은 자동으로 지우지 않고 개수만 증거로 남긴다. 비밀번호는 실행 중에만 만들어 쓰고 어디에도 남기지 않는다(계정 준비 오류도 원문 대신 오류 코드만
 * 남긴다).
 *
 * <p>왜 {@code TiberoTestEnvironment} 를 쓰지 않나: 그 클래스는 main 에서 패키지 전용이고, 공개로 바꾸는 수정이 병합 전인 WD-487
 * 브랜치에도 있어 두 브랜치가 같은 곳을 고치면 충돌한다. 환경변수 읽기·URL 조립 몇 줄만 겹치므로 ADR 뒤 하네스를 하나로 합칠 때(WD-497) 정리한다.
 */
final class TiberoFlywayPocAccount implements AutoCloseable {

  /** PoC 계정 이름 접두사. 뒤에 실행마다 다른 6자를 붙인다. */
  static final String ACCOUNT_PREFIX = "WD488_POC_";

  /** 측정 결과를 남기는 파일(작업 디렉터리 apps/firehub-api 기준) — 결과 코멘트의 근거. 비밀값은 쓰지 않는다. */
  static final Path EVIDENCE = Path.of("build", "tibero-flyway-poc.txt");

  private static final SecureRandom RANDOM = new SecureRandom();

  private final String name;
  private final Timestamp created;
  private final String url;
  private final String dbaUser;
  private final String dbaPassword;
  private final HikariDataSource dataSource;

  private TiberoFlywayPocAccount(
      String name,
      Timestamp created,
      String url,
      String dbaUser,
      String dbaPassword,
      String password,
      int poolSize) {
    this.name = name;
    this.created = created;
    this.url = url;
    this.dbaUser = dbaUser;
    this.dbaPassword = dbaPassword;
    this.dataSource = pool(name, url, password, poolSize);
  }

  /** 드라이버가 없으면 건너뛰고, 있으면 이번 실행만의 계정을 새로 만든다. */
  static TiberoFlywayPocAccount create(int poolSize) {
    assumeTrue(driverPresent(), "Tibero JDBC 드라이버 없음 — apps/firehub-api/libs/ 에 jar 를 두세요");
    String host = env("TIBERO_TEST_HOST", null);
    String dbaUser = env("TIBERO_TEST_DBA_USER", null);
    String dbaPassword = env("TIBERO_TEST_DBA_PASSWORD", null);
    if (host == null || dbaUser == null || dbaPassword == null) {
      throw new IllegalStateException(
          "TIBERO_TEST_HOST / TIBERO_TEST_DBA_USER / TIBERO_TEST_DBA_PASSWORD 가 필요합니다");
    }
    String url =
        "jdbc:tibero:thin:@"
            + host
            + ":"
            + env("TIBERO_TEST_PORT", "8629")
            + ":"
            + env("TIBERO_TEST_SID", "tibero");
    String name = ACCOUNT_PREFIX + suffix();
    String password = new DataStorePasswordGenerator().generate();
    Timestamp created;
    try (Connection c = DriverManager.getConnection(url, dbaUser, dbaPassword)) {
      long stale = countAccounts(c, ACCOUNT_PREFIX + "%");
      if (stale > 0) evidence("이전 실행이 남긴 " + ACCOUNT_PREFIX + "* 계정 " + stale + "개 — 자동으로 지우지 않음");
      if (createdAt(c, name) != null) {
        throw new IllegalStateException(name + " 계정이 이미 있다 — 접미사 충돌, 건드리지 않는다");
      }
      try {
        createAccount(c, name, password, System.getenv("TIBERO_TEST_TABLESPACE"));
      } catch (SQLException e) {
        // CREATE USER 는 됐는데 QUOTA·GRANT 가 실패했을 수 있다. 바로 위에서 없음을 확인한 고유 이름이라 지금 있는 계정은 이 실행이 만든 것이다.
        if (createdAt(c, name) != null) {
          try (Statement st = c.createStatement()) {
            st.execute("DROP USER " + TiberoNames.quote(name) + " CASCADE");
          }
        }
        throw e;
      }
      created = createdAt(c, name);
    } catch (SQLException e) {
      // 원인 예외를 붙이지 않는다 — CREATE USER 문장(비밀번호 포함)이 드라이버 오류 메시지에 섞일 수 있다.
      throw new IllegalStateException("PoC 계정 준비 실패(오류 코드 " + e.getErrorCode() + ")");
    }
    return new TiberoFlywayPocAccount(name, created, url, dbaUser, dbaPassword, password, poolSize);
  }

  /** 이번 실행의 계정(= 스키마) 이름. */
  String name() {
    return name;
  }

  /** PoC 계정 풀. 빌린 커넥션은 반드시 닫는다. */
  HikariDataSource dataSource() {
    return dataSource;
  }

  /** PoC 계정이 가진 객체 수(종류 무관). 휴지통(BIN$) 객체는 뺀다. */
  long objectCount() throws SQLException {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT COUNT(*) FROM ALL_OBJECTS WHERE OWNER = ? AND OBJECT_NAME NOT LIKE 'BIN$%'")) {
      ps.setString(1, name);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  /** PoC 계정 소유 테이블이 있으면 true. */
  boolean tableExists(String upperName) throws SQLException {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps =
            c.prepareStatement("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME = ?")) {
      ps.setString(1, upperName);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1) > 0;
      }
    }
  }

  /** 이름이 주어진 계정이 서버에 있으면 true(PoC 계정 권한으로 ALL_USERS 조회). */
  boolean userExists(String userName) throws SQLException {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps =
            c.prepareStatement("SELECT COUNT(*) FROM ALL_USERS WHERE USERNAME = ?")) {
      ps.setString(1, userName);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1) > 0;
      }
    }
  }

  /** PoC 계정으로 문장 하나를 실행한다(픽스처용). */
  void execute(String sql) throws SQLException {
    try (Connection c = dataSource.getConnection();
        Statement st = c.createStatement()) {
      st.execute(sql);
    }
  }

  /**
   * 풀을 닫고(세션이 남으면 DROP USER 가 거절된다) 이 인스턴스가 만든 계정만 지운 뒤 남은 수를 증거 파일에 남긴다. 이름이 같아도 생성 시각이 다르면(다른 주체가
   * 같은 이름으로 다시 만든 계정) 지우지 않는다.
   */
  @Override
  public void close() {
    try {
      dataSource.close();
    } finally {
      try (Connection c = DriverManager.getConnection(url, dbaUser, dbaPassword)) {
        Timestamp now = createdAt(c, name);
        if (now != null && now.equals(created)) {
          try (Statement st = c.createStatement()) {
            st.execute("DROP USER " + TiberoNames.quote(name) + " CASCADE");
          }
        } else if (now != null) {
          evidence(name + " 생성 시각이 달라 지우지 않음");
        }
        evidence("PoC account remaining count=" + countAccounts(c, name));
      } catch (SQLException e) {
        evidence("PoC 계정 정리 실패(오류 코드 " + e.getErrorCode() + ")");
        throw new IllegalStateException("PoC 계정 정리 실패(오류 코드 " + e.getErrorCode() + ")", e);
      }
    }
  }

  /** 측정 한 줄을 표준 출력과 증거 파일에 덧붙인다(실행마다 바뀌는 계정 접미사는 가린다). */
  static void evidence(String line) {
    String masked = line.replaceAll(ACCOUNT_PREFIX + "[A-Z0-9]{6}", ACCOUNT_PREFIX + "*");
    System.out.println("WD488 " + masked);
    try {
      Files.createDirectories(EVIDENCE.getParent());
      Files.writeString(
          EVIDENCE,
          masked + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException ignored) {
      // 증거 파일은 보조 수단이다 — 표준 출력에는 이미 남겼다.
    }
  }

  // ── 내부 ──────────────────────────────────────────────

  /** Tibero JDBC 드라이버가 클래스패스에 있으면 true(jar 는 libs/ 에 선택적으로 둔다). */
  private static boolean driverPresent() {
    try {
      Class.forName(TiberoDialect.DRIVER_CLASS);
      return true;
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }

  /** 환경변수 값, 비어 있으면 기본값. */
  private static String env(String key, String fallback) {
    String v = System.getenv(key);
    return (v == null || v.isBlank()) ? fallback : v;
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

  /** LIKE 패턴(또는 정확한 이름)에 맞는 계정 수. */
  private static long countAccounts(Connection c, String pattern) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement("SELECT COUNT(*) FROM DBA_USERS WHERE USERNAME LIKE ?")) {
      ps.setString(1, pattern);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  /**
   * 계정 생성 + 마이그레이션 소유자에게 필요한 최소 권한(테이블·시퀀스·뷰·프로시저·트리거 — clean 이 지울 객체 종류를 고루 만들기 위해, 구체화 뷰 — clean
   * 이 다루지 않는 종류를 만나면 멈추는지 보기 위해). 비밀번호가 든 문장은 오류 메시지에 SQL 이 섞일 수 있어 호출자가 오류 코드만 남긴다.
   */
  private static void createAccount(Connection c, String name, String password, String tablespace)
      throws SQLException {
    String account = TiberoNames.quote(name);
    String ts =
        TiberoNames.quote((tablespace == null || tablespace.isBlank()) ? "USR" : tablespace);
    try (Statement st = c.createStatement()) {
      st.execute(
          "CREATE USER "
              + account
              + " IDENTIFIED BY "
              + TiberoNames.quote(password)
              + " DEFAULT TABLESPACE "
              + ts);
      st.execute("ALTER USER " + account + " QUOTA UNLIMITED ON " + ts);
      st.execute(
          "GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE VIEW, CREATE PROCEDURE,"
              + " CREATE TRIGGER, CREATE MATERIALIZED VIEW TO "
              + account);
    }
  }

  /** PoC 계정 풀 — 세션 초기화·검증 문장은 운영 Tibero 풀과 같다(세션 NLS 고정). 유휴 0 으로 쓰지 않는 세션을 빨리 돌려준다. */
  private static HikariDataSource pool(String name, String url, String password, int size) {
    HikariConfig cfg = new HikariConfig();
    cfg.setPoolName("wd488-tibero-poc");
    cfg.setDriverClassName(TiberoDialect.DRIVER_CLASS);
    cfg.setJdbcUrl(url);
    cfg.setUsername(name);
    cfg.setPassword(password);
    cfg.setMaximumPoolSize(size);
    cfg.setMinimumIdle(0);
    cfg.setIdleTimeout(10_000);
    cfg.setConnectionTimeout(15_000);
    cfg.setConnectionInitSql(TiberoDialect.SESSION_INIT_SQL);
    cfg.setConnectionTestQuery(TiberoDialect.VALIDATION_QUERY);
    return new HikariDataSource(cfg);
  }
}
