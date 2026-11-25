package com.smartfirehub.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.PostgresTestContainer;
import db.migration.V168__blank_saved_query_folder_to_null;
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
 * V168: saved_query.folder 가 공백(NBSP 포함)·Cf(폭 없는 문자)만인 행의 folder 를 NULL 로 비우고, 글자가 있는 폴더는 그대로 두는지
 * 본다 (WD-1103).
 *
 * <p><b>왜 새 DB 인가.</b> 공유 테스트 DB 는 부팅 때 V168 까지 적용돼 "마이그레이션 이전 상태"(Cf 만인 folder 가 남은 행)를 재현할 수 없다.
 * 같은 컨테이너에 일회용 DB 를 만들어 Flyway 를 target=167 까지 돌리고, 소유자 롤(app, RLS 우회)로 픽스처를 심은 뒤 V168 을 적용한다
 * ({@code PlatformSuperAdminBackfillMigrationTest} 와 같은 방식).
 *
 * <p>V168 본문은 public static 헬퍼({@code blankOutBlankFolders})를 직접 불러 넣는다 — 같은 코드를 두 번 부르므로 재실행(멱등)
 * 검사도 마이그레이션 파일 자체의 코드로 증명된다.
 */
class SavedQueryFolderBlankMigrationTest {

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
  void 공백과_Cf_만인_폴더는_NULL_이_되고_글자가_있는_폴더는_남는다() throws Exception {
    flyway("167").migrate();
    try (Connection c = connect()) {
      // saved_query 필수 컬럼: name, sql_text, folder, created_by, created_at, updated_at, is_shared
      long ok = insert(c, "정상", "reports", "a"); // 유지
      long ws = insert(c, "공백", "   ", "b"); // NULL
      long nbsp = insert(c, "NBSP", "\u00A0", "c"); // NULL
      long zwsp = insert(c, "ZWSP", "\u200B", "d"); // NULL
      long mixed = insert(c, "혼합", "a\u200B", "e"); // 유지 (글자 사이 Cf)

      applyV168(c); // 같은 파일의 헬퍼 호출
      assertThat(folderOf(c, ok)).isEqualTo("reports");
      assertThat(folderOf(c, ws)).isNull();
      assertThat(folderOf(c, nbsp)).isNull();
      assertThat(folderOf(c, zwsp)).isNull();
      assertThat(folderOf(c, mixed)).isEqualTo("a\u200B");

      applyV168(c); // 재실행(멱등)
      assertThat(folderOf(c, ok)).isEqualTo("reports");
    }
  }

  /** Flyway 가 V168 (Java 마이그레이션)을 실제로 발견·적용하는지 — 운영 경로 그대로를 본다. */
  @Test
  void flyway_로_V168_을_적용하면_공백_폴더가_NULL_이_된다() throws Exception {
    flyway("167").migrate();
    long blank;
    long kept;
    try (Connection c = connect()) {
      blank = insert(c, "공백", "\u200B", "f");
      kept = insert(c, "정상", "reports", "g");
    }

    flyway("168").migrate();

    try (Connection c = connect()) {
      assertThat(folderOf(c, blank)).isNull();
      assertThat(folderOf(c, kept)).isEqualTo("reports");
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

  /**
   * 저장 쿼리 한 행을 만들고 id 를 돌려준다. created_by 는 전용 사용자({@code username})를 만들어 넣는다 —
   * saved_query.created_by 는 NOT NULL FK 다. RLS 를 우회하는 소유자 세션이라 tenant_id(기본 테넌트 1)를 명시한다.
   */
  private static long insert(Connection c, String name, String folder, String username)
      throws Exception {
    long userId;
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO \"user\" (username, password, name, email) VALUES (?, 'x', ?, ?)"
                + " RETURNING id")) {
      ps.setString(1, username);
      ps.setString(2, username);
      ps.setString(3, username + "@e.com");
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        userId = rs.getLong(1);
      }
    }
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO saved_query (name, sql_text, folder, created_by, created_at, updated_at,"
                + " is_shared, tenant_id) VALUES (?, 'SELECT 1', ?, ?, NOW(), NOW(), FALSE, 1)"
                + " RETURNING id")) {
      ps.setString(1, name);
      ps.setString(2, folder);
      ps.setLong(3, userId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  /** 그 행의 folder 값 — SQL NULL 이면 Java null. */
  private static String folderOf(Connection c, long id) throws Exception {
    try (PreparedStatement ps = c.prepareStatement("SELECT folder FROM saved_query WHERE id = ?")) {
      ps.setLong(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("id=%d 행이 있어야 한다", id).isTrue();
        return rs.getString(1);
      }
    }
  }

  /** V168 본문을 같은 파일의 public static 헬퍼로 직접 실행한다(계획의 applyV168 별칭 — 재실행 검사도 같은 코드를 부른다). */
  private static int applyV168(Connection connection) throws Exception {
    return V168__blank_saved_query_folder_to_null.blankOutBlankFolders(connection);
  }
}
