package com.smartfirehub.dbexecutor.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.smartfirehub.dbexecutor.store.StoreDescriptor;
import com.smartfirehub.dbexecutor.store.TiberoSession;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Tibero 실서버 테스트용 전용 소유 계정(WD-77) — {@link TiberoLiveSqlTest}·{@link TiberoLiveHttpTest} 가 공유한다.
 *
 * <p>{@code TIBERO_TEST_HOST/PORT/SID/DBA_USER/DBA_PASSWORD}(선택 {@code TIBERO_TEST_TABLESPACE})가
 * 없거나 JDBC 드라이버가 없으면 {@link #open()} 이 테스트를 건너뛴다. 접속 값은 어디에도 출력하지 않는다.
 *
 * <p><b>공유 서버 규칙</b>: api 계약 테스트와 같은 예약 대역(900,000,000 + 무작위) 테넌트 id 로 {@code GENIA_T<id>} 계정 하나만
 * 만들고, 같은 이름이 이미 있으면 거절한다(남의 계정은 절대 지우지 않는다). {@link #close()} 는 이 클래스가 만든 계정만 {@code DROP USER …
 * CASCADE} 하며, 각 단계가 서로 독립적으로(실패해도 다음 단계가 돌도록) 실행된다.
 */
final class TiberoLiveAccount implements AutoCloseable {

  private final long tenantId;
  private final String schema;
  private final String roSchema;
  private final StoreDescriptor descriptor;
  private final Connection dba;
  private Connection owner;
  private boolean created;
  private boolean roCreated;
  private final List<AutoCloseable> beforeDrop = new ArrayList<>();

  private TiberoLiveAccount(
      long tenantId, String schema, StoreDescriptor descriptor, Connection dba) {
    this.tenantId = tenantId;
    this.schema = schema;
    // api TiberoDdlSql 이 만드는 읽기 전용 계정 이름 규칙(GENIA_T{id}_RO)을 그대로 따른다.
    this.roSchema = schema + "_RO";
    this.descriptor = descriptor;
    this.dba = dba;
  }

  private static String env(String name) {
    String v = System.getenv(name);
    return v == null || v.isBlank() ? null : v;
  }

  private static boolean driverPresent() {
    try {
      Class.forName(TiberoSession.DRIVER_CLASS);
      return true;
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }

  /** 환경이 없으면 건너뛰고, 있으면 전용 계정을 만들어 소유 계정 연결까지 연다. 중간에 실패하면 만든 것을 치우고 예외를 던진다. */
  static TiberoLiveAccount open() throws Exception {
    String host = env("TIBERO_TEST_HOST");
    String dbaUser = env("TIBERO_TEST_DBA_USER");
    String dbaPassword = env("TIBERO_TEST_DBA_PASSWORD");
    assumeTrue(host != null && dbaUser != null && dbaPassword != null, "TIBERO_TEST_* 없음 — 건너뜀");
    assumeTrue(driverPresent(), "Tibero JDBC 드라이버 없음 — apps/firehub-db-executor/libs/ 에 jar 를 두세요");
    int port = Integer.parseInt(env("TIBERO_TEST_PORT") == null ? "8629" : env("TIBERO_TEST_PORT"));
    String sid = env("TIBERO_TEST_SID") == null ? "tibero" : env("TIBERO_TEST_SID");
    String tablespace = env("TIBERO_TEST_TABLESPACE");
    if (tablespace == null || !tablespace.matches("[A-Za-z][A-Za-z0-9_$#]{0,29}")) {
      tablespace = "USR";
    }
    tablespace = tablespace.toUpperCase(java.util.Locale.ROOT);

    long tenantId = 900_000_000L + ThreadLocalRandom.current().nextLong(1_000_000L);
    String schema = "GENIA_T" + tenantId;
    // 비밀번호는 이 테스트 안에서만 쓰는 임의값이며 출력하지 않는다.
    String ownerPassword = "Wd" + UUID.randomUUID().toString().replace("-", "");
    String roPassword = "Wd" + UUID.randomUUID().toString().replace("-", "");
    String roName = schema + "_RO";

    Connection dba =
        DriverManager.getConnection(TiberoSession.jdbcUrl(host, port, sid), dbaUser, dbaPassword);
    StoreDescriptor descriptor =
        new StoreDescriptor(
            tenantId,
            "TIBERO",
            host,
            port,
            sid,
            schema,
            new StoreDescriptor.Account(schema, ownerPassword),
            new StoreDescriptor.Account(roName, roPassword));
    TiberoLiveAccount account = new TiberoLiveAccount(tenantId, schema, descriptor, dba);
    try {
      if (account.userCount(schema) > 0 || account.userCount(roName) > 0) {
        // 우리가 만든 계정이 아니다 — 절대 지우지 않고 중단한다(created 는 false 로 남는다).
        throw new IllegalStateException("이미 있는 계정이라 테스트를 중단합니다: " + schema);
      }
      exec(
          dba,
          "CREATE USER \""
              + schema
              + "\" IDENTIFIED BY \""
              + ownerPassword
              + "\" DEFAULT TABLESPACE \""
              + tablespace
              + "\"");
      account.created = true;
      exec(dba, "ALTER USER \"" + schema + "\" QUOTA UNLIMITED ON \"" + tablespace + "\"");
      exec(
          dba,
          "GRANT CREATE SESSION, CREATE TABLE, CREATE VIEW, CREATE SEQUENCE TO \"" + schema + "\"");
      // 읽기 전용 계정: 접속 권한만 준다. 테이블 SELECT 는 테스트가 테이블을 만든 뒤 grantSelectToRo 로 준다.
      exec(dba, "CREATE USER \"" + roName + "\" IDENTIFIED BY \"" + roPassword + "\"");
      account.roCreated = true;
      exec(dba, "GRANT CREATE SESSION TO \"" + roName + "\"");
      account.owner =
          DriverManager.getConnection(
              TiberoSession.jdbcUrl(host, port, sid), schema, ownerPassword);
    } catch (Throwable t) {
      try {
        account.close();
      } catch (Throwable suppressed) {
        t.addSuppressed(suppressed);
      }
      throw t;
    }
    return account;
  }

  long tenantId() {
    return tenantId;
  }

  String schema() {
    return schema;
  }

  /** 실행기에 돌려줄 접속 기술자. */
  StoreDescriptor descriptor() {
    return descriptor;
  }

  /** 읽기 전용 계정 이름. */
  String roSchema() {
    return roSchema;
  }

  /** 읽기 전용 계정으로 여는 직접 연결(호출자가 닫는다). 쓰기 거부 확인용이다. */
  Connection openRo() throws SQLException {
    StoreDescriptor.Account ro = descriptor.ro();
    return DriverManager.getConnection(
        TiberoSession.jdbcUrl(descriptor.host(), descriptor.port(), descriptor.database()),
        ro.username(),
        ro.password());
  }

  /** 소유 계정의 테이블 하나에만 읽기 전용 계정 SELECT 를 준다(테이블을 다시 만들면 권한도 사라지므로 그때마다 호출). */
  void grantSelectToRo(String table) throws SQLException {
    exec(dba, "GRANT SELECT ON \"" + schema + "\".\"" + table + "\" TO \"" + roSchema + "\"");
  }

  /** 검증·DDL 용 직접 연결(소유 계정). 실행기 경로와 별개다. */
  Connection owner() {
    return owner;
  }

  /** DROP USER 전에 닫을 대상(예: 앱의 테넌트 풀)을 등록한다. 접속 중인 세션이 있으면 DROP USER 가 거절되기 때문이다. */
  void closeBeforeDrop(AutoCloseable closer) {
    beforeDrop.add(closer);
  }

  /**
   * 등록된 대상 → 소유 연결 → DROP USER(재시도) → 남은 계정 확인 → DBA 연결 순으로 정리한다. 앞 단계가 실패해도 뒤 단계는 반드시 실행하며, 첫 예외를
   * 던지고 나머지는 suppressed 로 붙인다.
   */
  @Override
  public void close() throws Exception {
    Throwable first = null;
    for (AutoCloseable c : beforeDrop) {
      first = run(first, c::close);
    }
    first = run(first, () -> closeQuietly(owner));
    if (roCreated) {
      first = run(first, () -> dropWithRetry(roSchema));
    }
    if (created) {
      first = run(first, () -> dropWithRetry(schema));
    }
    if (created || roCreated) {
      first = run(first, this::reportLeftover);
    }
    first = run(first, this::printReservedRange);
    first = run(first, () -> closeQuietly(dba));
    if (first instanceof Exception e) {
      throw e;
    }
    if (first instanceof Error err) {
      throw err;
    }
  }

  private interface Step {
    void run() throws Exception;
  }

  private static Throwable run(Throwable first, Step step) {
    try {
      step.run();
      return first;
    } catch (Throwable t) {
      if (first == null) {
        return t;
      }
      first.addSuppressed(t);
      return first;
    }
  }

  /** 시간 초과로 취소된 세션이 잠시 남아 있을 수 있어 몇 번 다시 시도한다. */
  private void dropWithRetry(String user) throws Exception {
    SQLException last = null;
    for (int attempt = 0; attempt < 12; attempt++) {
      try {
        exec(dba, "DROP USER \"" + user + "\" CASCADE");
        last = null;
        break;
      } catch (SQLException e) {
        last = e;
        Thread.sleep(5_000);
      }
    }
    if (last != null) {
      throw new IllegalStateException("테스트 계정 정리 실패(수동 확인 필요): " + user, last);
    }
  }

  /** 이 테스트가 만든 두 계정(소유·읽기 전용)이 남았는지 확인하고 출력한다. */
  private void reportLeftover() throws SQLException {
    long mine = userCount(schema) + userCount(roSchema);
    System.out.println("LIVE leftover accounts=" + mine);
    assertThat(mine).as("이 테스트가 만든 계정이 남았다").isZero();
  }

  /** 참고용: 예약 대역(GENIA_T900xxxxxx) 계정 수 — api 계약 테스트 잔재까지 포함하므로 단언하지 않는다. */
  private void printReservedRange() throws SQLException {
    try (PreparedStatement ps =
        dba.prepareStatement("SELECT COUNT(*) FROM ALL_USERS WHERE USERNAME LIKE ? ESCAPE '\\'")) {
      ps.setString(1, "GENIA\\_T900______");
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        System.out.println("LIVE reserved-range accounts=" + rs.getLong(1));
      }
    }
  }

  private long userCount(String username) throws SQLException {
    try (PreparedStatement ps =
        dba.prepareStatement("SELECT COUNT(*) FROM ALL_USERS WHERE USERNAME = ?")) {
      ps.setString(1, username);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  static void exec(Connection c, String sql) throws SQLException {
    try (Statement st = c.createStatement()) {
      st.execute(sql);
    }
  }

  /** 실패해도 무시한다(없으면 무시 — 첫 테스트의 DROP 등). */
  static void execIgnore(Connection c, String sql) {
    try {
      exec(c, sql);
    } catch (SQLException ignored) {
      // 없으면 무시.
    }
  }

  private static void closeQuietly(Connection c) {
    if (c == null) {
      return;
    }
    try {
      c.close();
    } catch (SQLException ignored) {
      // 정리 실패는 무시한다.
    }
  }
}
