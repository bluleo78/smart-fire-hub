package com.smartfirehub.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.PostgresTestContainer;
import db.migration.V170__blank_saved_query_folder_to_null;
import db.migration.V171__blank_saved_query_folder_cf_to_null;
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
 * V170: saved_query.folder 가 공백(NBSP 포함)·Cf(폭 없는 문자)만인 행의 folder 를 NULL 로 비우고, 글자가 있는 폴더는 그대로 두는지
 * 본다 (WD-1103).
 *
 * <p><b>왜 새 DB 인가.</b> 공유 테스트 DB 는 부팅 때 V170 까지 적용돼 "마이그레이션 이전 상태"(Cf 만인 folder 가 남은 행)를 재현할 수 없다.
 * 같은 컨테이너에 일회용 DB 를 만들어 Flyway 를 target=169 까지 돌리고, 소유자 롤(app, RLS 우회)로 픽스처를 심은 뒤 V170 을 적용한다
 * ({@code PlatformSuperAdminBackfillMigrationTest} 와 같은 방식).
 *
 * <p>V170 본문은 public static 헬퍼({@code blankOutBlankFolders})를 직접 불러 넣는다 — 같은 코드를 두 번 부르므로 재실행(멱등)
 * 검사도 마이그레이션 파일 자체의 코드로 증명된다.
 */
class SavedQueryFolderBlankMigrationTest {

  private String dbName;
  private String url;

  @BeforeEach
  void createDatabase() throws Exception {
    dbName = "v170_mig_" + System.nanoTime();
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
    flyway("169").migrate();
    try (Connection c = connect()) {
      // saved_query 필수 컬럼: name, sql_text, folder, created_by, created_at, updated_at, is_shared
      long ok = insert(c, "정상", "reports", "a"); // 유지
      long ws = insert(c, "공백", "   ", "b"); // NULL
      long nbsp = insert(c, "NBSP", "\u00A0", "c"); // NULL
      long zwsp = insert(c, "ZWSP", "\u200B", "d"); // NULL
      long mixed = insert(c, "혼합", "a\u200B", "e"); // 유지 (글자 사이 Cf)
      long rlo = insert(c, "RLO", "\u202E", "h"); // NULL (Cf)
      long fill = insert(c, "채움", "\u3164", "i"); // NULL (한글 채움 문자)
      long letterRlo = insert(c, "글자RLO", "a\u202E", "j"); // 유지

      // 비울 행: ws·nbsp·zwsp·rlo·fill = 5 — 반환값이 바뀐 행 수와 같아야 한다.
      assertThat(applyV170(c)).isEqualTo(5); // 같은 파일의 헬퍼 호출
      assertThat(folderOf(c, rlo)).isNull();
      assertThat(folderOf(c, fill)).isNull();
      assertThat(folderOf(c, letterRlo)).isEqualTo("a\u202E");
      assertThat(folderOf(c, ok)).isEqualTo("reports");
      assertThat(folderOf(c, ws)).isNull();
      assertThat(folderOf(c, nbsp)).isNull();
      assertThat(folderOf(c, zwsp)).isNull();
      assertThat(folderOf(c, mixed)).isEqualTo("a\u200B");

      assertThat(applyV170(c)).isZero(); // 재실행(멱등) — 바뀐 행 0
      assertThat(folderOf(c, ok)).isEqualTo("reports");

      // V171(Tibero 짝)은 자기 고정 판정을 쓰지만 V170 이 이미 비웠으므로 바뀐 행 0(멱등)이다(WD-1144, WD-1161).
      assertThat(applyV171(c)).isZero();
      assertThat(folderOf(c, letterRlo)).isEqualTo("a\u202E");
    }
  }

  /**
   * V171 이 V170 에 기대지 않고 자기 판정만으로 비우는지 — V170 뒤에 들어온 Cf·Cc 만인 행을 V171 단독으로 NULL 로 만든다(WD-1161).
   */
  @Test
  void V170_뒤에_들어온_Cf_만인_폴더를_V171_만으로_NULL_로_만든다() throws Exception {
    flyway("169").migrate();
    try (Connection c = connect()) {
      applyV170(c);
      long zwsp = insert(c, "ZWSP", "\u200B", "k"); // NULL (Cf)
      long ctrl = insert(c, "제어", "\u0001", "l"); // NULL (Cc)
      long kept = insert(c, "글자ZWSP", "a\u200B", "m"); // 유지

      assertThat(applyV171(c)).isEqualTo(2);
      assertThat(folderOf(c, zwsp)).isNull();
      assertThat(folderOf(c, ctrl)).isNull();
      assertThat(folderOf(c, kept)).isEqualTo("a\u200B");

      assertThat(applyV171(c)).isZero(); // 재실행(멱등)
    }
  }

  /** Flyway 가 V170 (Java 마이그레이션)을 실제로 발견·적용하는지 — 운영 경로 그대로를 본다. */
  @Test
  void flyway_로_V170_을_적용하면_공백_폴더가_NULL_이_된다() throws Exception {
    flyway("169").migrate();
    long blank;
    long kept;
    try (Connection c = connect()) {
      blank = insert(c, "공백", "\u200B", "f");
      kept = insert(c, "정상", "reports", "g");
    }

    flyway("170").migrate();

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

  /** V170 본문을 같은 파일의 public static 헬퍼로 직접 실행한다(계획의 applyV170 별칭 — 재실행 검사도 같은 코드를 부른다). */
  private static int applyV170(Connection connection) throws Exception {
    return V170__blank_saved_query_folder_to_null.blankOutBlankFolders(connection);
  }

  /**
   * V171 본문(PG 짝)의 public static 헬퍼를 직접 실행해 바뀐 행 수를 돌려준다. V170 헬퍼를 다시 부르지 않아야 V171 자신의 고정 판정만 검증된다
   * (WD-1161).
   */
  private static int applyV171(Connection connection) throws Exception {
    return V171__blank_saved_query_folder_cf_to_null.blankOutBlankFolders(connection);
  }
}
