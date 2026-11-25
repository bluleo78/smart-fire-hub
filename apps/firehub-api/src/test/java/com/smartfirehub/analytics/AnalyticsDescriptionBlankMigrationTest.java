package com.smartfirehub.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.PostgresTestContainer;
import db.migration.V172__blank_analytics_description_to_null;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V172: saved_query·chart·dashboard 의 description 이 글자 없는 행을 NULL 로 비우고, 글자가 있는 설명은 그대로 두는지 본다 (WD-1161).
 *
 * <p><b>왜 새 DB 인가.</b> 공유 테스트 DB 는 부팅 때 V172 까지 적용돼 "마이그레이션 이전 상태"(빈 설명이 남은 행)를 재현할 수 없다. 같은 컨테이너에 일회용
 * DB 를 만들어 Flyway 를 target=171 까지 돌리고, 소유자 롤(app, RLS 우회)로 픽스처를 심은 뒤 V172 를 적용한다({@link
 * SavedQueryFolderBlankMigrationTest} 와 같은 방식).
 */
class AnalyticsDescriptionBlankMigrationTest {

  private static final String[] TABLES = {"saved_query", "chart", "dashboard"};

  private String dbName;
  private String url;

  @BeforeEach
  void createDatabase() throws Exception {
    dbName = "v172_mig_" + System.nanoTime();
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
  void 세_표의_글자_없는_설명은_NULL_이_되고_글자가_있는_설명은_남는다() throws Exception {
    flyway("171").migrate();
    try (Connection c = connect()) {
      long user = insertUser(c, "u1");
      long sq = insertSavedQuery(c, user, "기준", "x");
      // 표마다 같은 8개 설명을 심는다 — 글자 없는 5개는 NULL, 나머지 3개는 그대로여야 한다.
      String[] values = {"", "   ", "\u200B", "\n\n", "\u0001", "설명", "a\u200B", " 설명 "};
      Map<String, long[]> ids = new LinkedHashMap<>();
      for (String table : TABLES) {
        long[] row = new long[values.length];
        for (int i = 0; i < values.length; i++) {
          row[i] = insert(c, table, user, sq, "n" + i, values[i]);
        }
        ids.put(table, row);
      }

      assertThat(V172__blank_analytics_description_to_null.blankOutBlankDescriptions(c))
          .isEqualTo(15); // 표마다 5 × 3

      for (String table : TABLES) {
        long[] row = ids.get(table);
        for (int i = 0; i < 5; i++) {
          assertThat(descriptionOf(c, table, row[i])).as("%s #%d", table, i).isNull();
        }
        for (int i = 5; i < values.length; i++) {
          assertThat(descriptionOf(c, table, row[i])).as("%s #%d", table, i).isEqualTo(values[i]);
        }
      }

      assertThat(V172__blank_analytics_description_to_null.blankOutBlankDescriptions(c))
          .isZero(); // 재실행(멱등)
    }
  }

  /** Flyway 가 V172 (Java 마이그레이션)를 실제로 발견·적용하는지 — 운영 경로 그대로를 본다. */
  @Test
  void flyway_로_V172_를_적용하면_빈_설명이_NULL_이_된다() throws Exception {
    flyway("171").migrate();
    long blank;
    long zwsp;
    try (Connection c = connect()) {
      long user = insertUser(c, "u2");
      blank = insertSavedQuery(c, user, "빈값", "");
      zwsp = insertSavedQuery(c, user, "ZWSP", "\u200B");
    }

    flyway("172").migrate();

    try (Connection c = connect()) {
      assertThat(descriptionOf(c, "saved_query", blank)).isNull();
      assertThat(descriptionOf(c, "saved_query", zwsp)).isNull();
    }
  }

  // ── 도우미 ──────────────────────────────────────────────────────────────────

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

  /** 사용자 한 명을 만들고 id 를 돌려준다 — 세 표의 created_by 는 NOT NULL FK 다. */
  private static long insertUser(Connection c, String username) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO \"user\" (username, password, name, email) VALUES (?, 'x', ?, ?)"
                + " RETURNING id")) {
      ps.setString(1, username);
      ps.setString(2, username);
      ps.setString(3, username + "@e.com");
      return id(ps);
    }
  }

  /** 저장 쿼리 한 행 — RLS 를 우회하는 소유자 세션이라 tenant_id(기본 테넌트 1)를 명시한다. */
  private static long insertSavedQuery(Connection c, long user, String name, String description)
      throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO saved_query (name, description, sql_text, created_by, created_at,"
                + " updated_at, is_shared, tenant_id)"
                + " VALUES (?, ?, 'SELECT 1', ?, NOW(), NOW(), FALSE, 1) RETURNING id")) {
      ps.setString(1, name);
      ps.setString(2, description);
      ps.setLong(3, user);
      return id(ps);
    }
  }

  /** 표 이름에 맞는 행 한 개를 심는다. 차트는 저장 쿼리를 참조해야 한다(saved_query_id NOT NULL FK). */
  private static long insert(
      Connection c, String table, long user, long savedQuery, String name, String description)
      throws Exception {
    switch (table) {
      case "saved_query":
        return insertSavedQuery(c, user, name, description);
      case "chart":
        try (PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO chart (name, description, saved_query_id, chart_type, config,"
                    + " is_shared, created_by, created_at, updated_at, tenant_id)"
                    + " VALUES (?, ?, ?, 'BAR', '{}', FALSE, ?, NOW(), NOW(), 1) RETURNING id")) {
          ps.setString(1, name);
          ps.setString(2, description);
          ps.setLong(3, savedQuery);
          ps.setLong(4, user);
          return id(ps);
        }
      case "dashboard":
        try (PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO dashboard (name, description, is_shared, created_by, created_at,"
                    + " updated_at, tenant_id) VALUES (?, ?, FALSE, ?, NOW(), NOW(), 1)"
                    + " RETURNING id")) {
          ps.setString(1, name);
          ps.setString(2, description);
          ps.setLong(3, user);
          return id(ps);
        }
      default:
        throw new IllegalArgumentException(table);
    }
  }

  private static long id(PreparedStatement ps) throws Exception {
    try (ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getLong(1);
    }
  }

  /** 그 행의 description 값 — SQL NULL 이면 Java null. */
  private static String descriptionOf(Connection c, String table, long id) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement("SELECT description FROM " + table + " WHERE id = ?")) {
      ps.setLong(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("%s id=%d 행이 있어야 한다", table, id).isTrue();
        return rs.getString(1);
      }
    }
  }
}
