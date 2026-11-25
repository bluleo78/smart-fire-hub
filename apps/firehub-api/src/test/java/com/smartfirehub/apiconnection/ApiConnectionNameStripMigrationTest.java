package com.smartfirehub.apiconnection;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.PostgresTestContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V168: api_connection.name 옛 행의 앞뒤 공백(NBSP·전각 공백·탭 등 유니코드 White_Space)을 걷어내되, 정확 일치 유니크 인덱스(V119)를
 * 어기지 않도록 겹치는 행은 건너뛰는지 본다(WD-1107).
 *
 * <p>공유 테스트 DB 는 이미 V168 까지 적용돼 있어 "옛 행"을 만들 수 없으므로, 일회용 DB 를 만들어 V167 까지 적용 → 행 심기 → V168 적용
 * 순으로 돈다(PlatformSuperAdminBackfillMigrationTest 와 같은 방식).
 */
class ApiConnectionNameStripMigrationTest {

  private String dbName;
  private String url;

  @BeforeEach
  void createDatabase() throws Exception {
    dbName = "v168_mig_" + System.nanoTime();
    try (Connection c = admin();
        Statement s = c.createStatement()) {
      s.execute("CREATE DATABASE " + dbName);
    }
    url = PostgresTestContainer.INSTANCE.getJdbcUrl().replace("/smartfirehub_test", "/" + dbName);
  }

  @AfterEach
  void dropDatabase() throws Exception {
    try (Connection c = admin();
        Statement s = c.createStatement()) {
      s.execute("DROP DATABASE IF EXISTS " + dbName + " WITH (FORCE)");
    }
  }

  @Test
  void 옛_행_이름의_앞뒤_공백을_걷되_겹치거나_공백뿐이면_건너뛴다() throws Exception {
    flyway("167").migrate();
    long a;
    long d1;
    long dSpace;
    long e1;
    long e2;
    long blank;
    long b;
    long cTab;
    long ws;
    long t2;
    try (Connection c = connect()) {
      c.createStatement().execute("INSERT INTO tenant (id, slug, name) VALUES (2, 'other', 'Other')");
      c.createStatement().execute("SELECT provision_tenant_defaults(2)");
      long user = user(c);
      a = conn(c, 1, user, "A\u00a0");
      b = conn(c, 1, user, " B\u3000");
      cTab = conn(c, 1, user, "\tC ");
      d1 = conn(c, 1, user, "D");
      dSpace = conn(c, 1, user, "D ");
      e1 = conn(c, 1, user, "E ");
      e2 = conn(c, 1, user, " E");
      blank = conn(c, 1, user, "\u00a0\u3000");
      t2 = conn(c, 2, user, "D ");
      ws = conn(c, 1, user, "F\u2003G ");
    }

    flyway("168").migrate();

    try (Connection c = connect()) {
      assertThat(name(c, a)).isEqualTo("A");
      assertThat(name(c, b)).isEqualTo("B");
      assertThat(name(c, cTab)).isEqualTo("C");
      assertThat(name(c, d1)).isEqualTo("D");
      assertThat(name(c, dSpace)).isEqualTo("D ");
      assertThat(name(c, e1)).isEqualTo("E");
      assertThat(name(c, e2)).isEqualTo(" E");
      assertThat(name(c, blank)).isEqualTo("\u00a0\u3000");
      assertThat(name(c, t2)).isEqualTo("D");
      assertThat(name(c, ws)).as("가운데 공백은 건드리지 않는다").isEqualTo("F\u2003G");
    }
  }

  private static Connection admin() throws Exception {
    return DriverManager.getConnection(PostgresTestContainer.INSTANCE.getJdbcUrl(), "app", "app");
  }

  private Connection connect() throws Exception {
    return DriverManager.getConnection(url, "app", "app");
  }

  /** V125 의 CREATE INDEX CONCURRENTLY 때문에 트랜잭션 잠금을 끈다(application.yml 과 같은 설정). */
  private Flyway flyway(String target) {
    return Flyway.configure()
        .dataSource(url, "app", "app")
        .locations("classpath:db/migration")
        .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
        .target(target)
        .load();
  }

  private static long user(Connection c) throws Exception {
    try (Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery(
                "INSERT INTO \"user\" (username, password, name, email, is_active)"
                    + " VALUES ('u', 'x', 'u', 'u@e.com', true) RETURNING id")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static long conn(Connection c, long tenantId, long userId, String name) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO api_connection (name, auth_type, auth_config, created_by, base_url,"
                + " tenant_id) VALUES (?, 'BEARER', 'x', ?, 'http://x', ?) RETURNING id")) {
      ps.setString(1, name);
      ps.setLong(2, userId);
      ps.setLong(3, tenantId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private static String name(Connection c, long id) throws Exception {
    try (Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT name FROM api_connection WHERE id = " + id)) {
      rs.next();
      return rs.getString(1);
    }
  }
}
