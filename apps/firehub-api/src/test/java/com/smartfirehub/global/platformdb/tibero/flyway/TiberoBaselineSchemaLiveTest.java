package com.smartfirehub.global.platformdb.tibero.flyway;

import static com.smartfirehub.support.tibero.TiberoTestEvidence.write;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.storage.tibero.TiberoDialect;
import com.smartfirehub.jooq.Public;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoTestAccount;
import com.smartfirehub.support.tibero.TiberoTestAccount.Privileges;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.output.MigrateResult;
import org.jooq.Field;
import org.jooq.Table;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tibero 기준선(db/migration-tibero V155·V155.1)을 Tibero 실서버에 적용하고, PG 스키마와 구조가 맞는지 본다(WD-490).
 *
 * <p>무엇을: 운영과 같은 경로(Flyway + Tibero 플러그인, 세션 NLS 고정)로 기준선과 "주석만 있는 마이그레이션"을 적용한 뒤
 *
 * <ul>
 *   <li>PG 의 테이블·뷰·열(jOOQ 생성 코드 = PG V155 스키마)이 제외 목록을 빼고 Tibero 에 모두 있는지, 반대로 Tibero 에만 있는 테이블은
 *       없는지, 열의 NULL 허용이 같은지(일부러 푼 열은 허용 목록)
 *   <li>시드 뒤 IDENTITY 가 시드 ID 와 부딪히지 않는지, 부분 UNIQUE·NULL 낀 다열 UNIQUE 를 옮긴 함수 기반 인덱스가 PG 와 같게 막는지
 *   <li>글자 수 기준 VARCHAR2(n CHAR)·boolean CHECK 가 PG 와 같은 값을 받고 막는지
 * </ul>
 *
 * <p>왜 jOOQ 생성 코드와 대조하나: 기준선은 PG 덤프를 손으로 다듬은 한 파일이라 열 하나 빠져도 적용은 성공한다. jOOQ 생성 코드는 PG 마이그레이션을 바꿀
 * 때마다 다시 만들므로, 다음 PG 변경에서 Tibero 짝 마이그레이션이 테이블·열·NULL 허용을 빠뜨린 것도 이 대조가 잡는다. 대조하지 않는 것: 열 길이·타입,
 * 기본값, UNIQUE·인덱스 목록 — 이들은 Tibero 에서 모양이 일부러 다르므로(V155 머리말) 위 행동 시험으로 대표 사례만 본다.
 *
 * <p>계정 하나에 한 번만 적용한다(@BeforeAll) — 원격 서버에 DDL 약 370개라 시험마다 다시 적용하면 오래 걸리고, 시험들은 기준선을 읽거나 각자 지우는 행만
 * 넣는다.
 *
 * <p>실행 조건: {@code TIBERO_TEST_HOST} 가 있을 때만(공유 개발 서버). 일반 PG 회귀에서는 건너뛴다.
 */
@TiberoLive
class TiberoBaselineSchemaLiveTest {

  /** 기준선에서 일부러 뺀 PG 테이블·뷰 — V155 머리말의 제외 목록과 같다. */
  private static final Set<String> EXCLUDED =
      Set.of(
          "dataset_embedding_vec_1024", // VECTOR — WD-495
          "dataset_embedding_vec_1536",
          "document_chunk_vec_1024",
          "document_chunk_vec_1536",
          "spatial_ref_sys", // PostGIS
          "geography_columns",
          "geometry_columns",
          "flyway_schema_history"); // 양쪽 다 Flyway 가 만든다(대조 대상 아님)

  /** PG 는 NOT NULL 인데 Tibero 는 NULL 을 허용하는 열 — ''=NULL 이라 앱이 '' 를 쓰는 열(V155 머리말, 코드 쪽은 WD-491). */
  private static final Set<String> RELAXED_NOT_NULL =
      Set.of(
          "DATASET_SEARCH_INDEX.CONFIG_HASH",
          "PROACTIVE_JOB.PROMPT",
          "ONTOLOGY_ENTITY_TYPE.DESCRIPTION",
          "ONTOLOGY_ENTITY_TYPE.NAMING",
          "ONTOLOGY_RELATION.DESCRIPTION",
          "ONTOLOGY_ENTITY_PROPERTY.DESCRIPTION");

  private static TiberoTestAccount account;
  private static MigrateResult migrated;

  @BeforeAll
  static void migrate() {
    account = TiberoTestAccount.create("BASELINE", Privileges.MIGRATION_OWNER, 2);
    // 단일 계정(런타임 null) — V156.1 의 자리표시자만 넣고 권한 콜백은 아무것도 하지 않는다(WD-492).
    Flyway flyway =
        TiberoTenantMigration.configure(
                Flyway.configure()
                    .dataSource(account.dataSource())
                    .schemas(account.name())
                    .initSql(TiberoDialect.SESSION_INIT_SQL)
                    .locations(
                        "classpath:db/migration-tibero", "classpath:tibero-baseline/comment-only"),
                account.name(),
                null)
            .load();
    long started = System.nanoTime();
    migrated = flyway.migrate();
    write(
        "기준선 적용 "
            + migrated.migrationsExecuted
            + "건, "
            + (System.nanoTime() - started) / 1_000_000
            + "ms");
  }

  @AfterAll
  static void dropAccount() {
    if (account != null) account.close();
  }

  /**
   * 기준선 두 파일·이후 마이그레이션과 주석만 있는 V9999 가 모두 성공으로 기록된다 — 빈 마이그레이션을 Oracle 파서가 받는지(ADR 0001 결정 2 의 확인
   * 항목).
   */
  @Test
  void baselineAndCommentOnlyMigration_succeed() {
    assertThat(migrated.success).isTrue();
    Flyway info =
        TiberoTenantMigration.configure(
                Flyway.configure()
                    .dataSource(account.dataSource())
                    .schemas(account.name())
                    .locations(
                        "classpath:db/migration-tibero", "classpath:tibero-baseline/comment-only"),
                account.name(),
                null)
            .load();
    List<String> states = new ArrayList<>();
    for (MigrationInfo i : info.info().all()) {
      states.add(i.getVersion() + ":" + i.getState());
    }
    write("마이그레이션 상태 " + states);
    // 기준선 뒤 마이그레이션은 계속 늘어나므로 버전을 하나하나 못 박지 않는다 — 기준선·코멘트 전용이 들어 있고 모두 성공인지만 본다.
    assertThat(states).contains("155:SUCCESS", "155.1:SUCCESS", "9999:SUCCESS");
    assertThat(states).allMatch(s -> s.endsWith(":SUCCESS"));
    assertThat(info.info().current().getState()).isEqualTo(MigrationState.SUCCESS);
  }

  /** PG(jOOQ 생성 코드)의 테이블·뷰·열이 제외 목록을 빼고 Tibero 에 모두 있고, Tibero 에만 있는 테이블은 없고, NULL 허용이 같다. */
  @Test
  void schema_matchesPostgres_exceptExclusions() throws SQLException {
    Map<String, Map<String, Boolean>> tibero = tiberoColumns();
    List<String> missing = new ArrayList<>();
    List<String> nullability = new ArrayList<>();
    Set<String> pgTables = new TreeSet<>();
    for (Table<?> t : Public.PUBLIC.getTables()) {
      // 테이블 반환 함수(resolve_*·*_tenant_ids·PostGIS st_*)는 jOOQ 가 Table 로 만들지만 스키마 객체가 아니다.
      if (!(t.getTableType().isTable() || t.getTableType().isView())) continue;
      if (EXCLUDED.contains(t.getName())) continue;
      String name = t.getName().toUpperCase(Locale.ROOT);
      pgTables.add(name);
      Map<String, Boolean> cols = tibero.get(name);
      if (cols == null) {
        missing.add(t.getName() + " (테이블 없음)");
        continue;
      }
      for (Field<?> f : t.fields()) {
        String col = f.getName().toUpperCase(Locale.ROOT);
        Boolean tiberoNullable = cols.get(col);
        if (tiberoNullable == null) {
          missing.add(t.getName() + "." + f.getName());
          continue;
        }
        // 뷰 열의 NULL 허용은 DB 가 정한다(JobRunr 통계 뷰) — 테이블만 대조한다.
        boolean pgNullable = f.getDataType().nullable();
        if (t.getTableType().isTable()
            && pgNullable != tiberoNullable
            && !RELAXED_NOT_NULL.contains(name + "." + col)) {
          nullability.add(
              name + "." + col + "(PG " + pgNullable + ", Tibero " + tiberoNullable + ")");
        }
      }
    }
    Set<String> extra = new TreeSet<>(tibero.keySet());
    extra.removeAll(pgTables);
    write(
        "PG 대조: 테이블·뷰 "
            + pgTables.size()
            + "개, 빠짐 "
            + missing
            + ", Tibero 에만 "
            + extra
            + ", NULL 허용 다름 "
            + nullability);
    assertThat(missing).as("Tibero 기준선에 없는 PG 테이블·열").isEmpty();
    assertThat(extra).as("PG 에 없는 Tibero 테이블").isEmpty();
    assertThat(nullability).as("PG 와 NULL 허용이 다른 열(허용 목록 밖)").isEmpty();
  }

  /** 시드가 ID 를 명시로 넣은 테이블도 앱 INSERT(ID 생략)가 유일 위반 없이 시드 다음 번호를 받는다. */
  @Test
  void identity_startsAfterSeedIds() throws SQLException {
    try (Connection c = account.dataSource().getConnection();
        Statement s = c.createStatement()) {
      try {
        long tenantMax = scalar(c, "SELECT MAX(id) FROM tenant");
        long permMax = scalar(c, "SELECT MAX(id) FROM permission");
        long tenantId =
            insertReturningId(
                c, "INSERT INTO tenant (slug, name) VALUES ('wd490-identity', 'WD-490 IDENTITY')");
        long permId =
            insertReturningId(
                c,
                "INSERT INTO permission (code, description, category)"
                    + " VALUES ('wd490:probe', 'WD-490', 'probe')");
        write(
            "IDENTITY: tenant 시드 최대 "
                + tenantMax
                + " → "
                + tenantId
                + ", permission "
                + permMax
                + " → "
                + permId);
        assertThat(tenantId).isGreaterThan(tenantMax);
        assertThat(permId).isGreaterThan(permMax);
      } finally {
        // 단정이 실패해도 같은 계정을 쓰는 다른 시험에 행을 남기지 않는다.
        s.executeUpdate("DELETE FROM permission WHERE code = 'wd490:probe'");
        s.executeUpdate("DELETE FROM tenant WHERE slug = 'wd490-identity'");
      }
    }
  }

  /**
   * NULL 낀 다열 UNIQUE(uk_ai_session_slack_thread)가 PG 처럼 키에 NULL 이 있는 행은 검사하지 않고, 키가 모두 같은 행만 막는다.
   * Tibero 의 기본 다열 UNIQUE 는 NULL 아닌 열끼리 같으면 충돌하므로 CASE 함수 기반 인덱스로 바꿨다(V155 머리말).
   */
  @Test
  void nullableCompositeUnique_ignoresRowsWithNullKey() throws SQLException {
    try (Connection c = account.dataSource().getConnection();
        Statement s = c.createStatement()) {
      String insert =
          "INSERT INTO ai_session (user_id, session_id, channel_source, slack_team_id,"
              + " slack_channel_id, slack_thread_ts, tenant_id) VALUES (%d, '%s', 'SLACK', 'T1', 'C1', %s, 1)";
      try {
        long userId =
            insertReturningId(
                c,
                "INSERT INTO \"USER\" (username, password, name) VALUES ('wd490-slack', 'x', 'x')");
        // thread_ts 가 NULL 인 SLACK 세션 둘 — PG 는 둘 다 받는다.
        s.executeUpdate(String.format(insert, userId, "wd490-s1", "NULL"));
        s.executeUpdate(String.format(insert, userId, "wd490-s2", "NULL"));
        // 키가 모두 같은 둘째 행만 유일 위반.
        s.executeUpdate(String.format(insert, userId, "wd490-s3", "'1.0'"));
        assertThatThrownBy(
                () -> s.executeUpdate(String.format(insert, userId, "wd490-s4", "'1.0'")))
            .isInstanceOfSatisfying(
                SQLException.class, e -> assertThat(e.getErrorCode()).isEqualTo(-10007));
      } finally {
        s.executeUpdate("DELETE FROM ai_session WHERE session_id LIKE 'wd490-%'");
        s.executeUpdate("DELETE FROM \"USER\" WHERE username = 'wd490-slack'");
      }
    }
  }

  /** 부분 UNIQUE(uq_security_level_default — 테넌트당 기본 등급 하나)를 옮긴 함수 기반 인덱스가 둘째 기본 등급만 막는다. */
  @Test
  void partialUniqueIndex_blocksOnlyRowsInsideCondition() throws SQLException {
    try (Connection c = account.dataSource().getConnection();
        Statement s = c.createStatement()) {
      // 조건 밖(is_default=0) 행은 몇 개든 들어간다.
      s.executeUpdate(
          "INSERT INTO security_level (tenant_id, rank, name, is_default) VALUES (1, 90, 'wd490-a', 0)");
      s.executeUpdate(
          "INSERT INTO security_level (tenant_id, rank, name, is_default) VALUES (1, 91, 'wd490-b', 0)");
      try {
        // 시드에 테넌트 1 의 기본 등급(내부)이 이미 있다 — 둘째 기본 등급은 유일 위반.
        assertThatThrownBy(
                () ->
                    s.executeUpdate(
                        "INSERT INTO security_level (tenant_id, rank, name, is_default)"
                            + " VALUES (1, 92, 'wd490-c', 1)"))
            .isInstanceOfSatisfying(
                SQLException.class, e -> assertThat(e.getErrorCode()).isEqualTo(-10007));
      } finally {
        s.executeUpdate("DELETE FROM security_level WHERE name LIKE 'wd490-%'");
      }
    }
  }

  /** varchar(n) 은 글자 수 기준(n CHAR)이라 한글 n 자가 들어가고, boolean 열은 0/1 밖의 값을 CHECK(-10006)로 막는다. */
  @Test
  void charSemanticsAndBooleanCheck_matchPostgres() throws SQLException {
    String fiftyKorean = "가".repeat(50); // "USER".name 은 PG varchar(50) — UTF-8 로 150바이트
    try (Connection c = account.dataSource().getConnection()) {
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO \"USER\" (username, password, name) VALUES ('wd490-char', 'x', ?)")) {
        ps.setString(1, fiftyKorean);
        assertThat(ps.executeUpdate()).isEqualTo(1);
      }
      try (Statement s = c.createStatement()) {
        try {
          assertThatThrownBy(
                  () ->
                      s.executeUpdate(
                          "UPDATE \"USER\" SET is_active = 2 WHERE username = 'wd490-char'"))
              .isInstanceOfSatisfying(
                  SQLException.class, e -> assertThat(e.getErrorCode()).isEqualTo(-10006));
        } finally {
          s.executeUpdate("DELETE FROM \"USER\" WHERE username = 'wd490-char'");
        }
      }
    }
  }

  /**
   * 식이 든 기본값·인덱스가 실제 행에서 PG 와 같게 동작한다 — gen_random_uuid() 를 옮긴 SYS_GUID 기본값이 UUID 문자열이 되고, JSON 키
   * 부분 UNIQUE(uq_trigger_api_token_hash)가 같은 API 토큰만 막고 다른 종류(WEBHOOK)의 같은 값은 받는다.
   */
  @Test
  void expressionDefaultsAndJsonUnique_behaveLikePostgres() throws SQLException {
    try (Connection c = account.dataSource().getConnection();
        Statement s = c.createStatement()) {
      try {
        long userId =
            insertReturningId(
                c,
                "INSERT INTO \"USER\" (username, password, name) VALUES ('wd490-expr', 'x', 'x')");
        s.executeUpdate(
            "INSERT INTO analytics_query_run (tenant_id, user_id, sql_text, max_rows)"
                + " VALUES (1, "
                + userId
                + ", 'SELECT 1', 10)");
        String runId;
        try (ResultSet rs =
            s.executeQuery("SELECT id FROM analytics_query_run WHERE user_id = " + userId)) {
          rs.next();
          runId = rs.getString(1);
        }
        write("UUID 기본값 " + runId);
        assertThat(java.util.UUID.fromString(runId).toString()).isEqualTo(runId);

        long pipelineId =
            insertReturningId(
                c,
                "INSERT INTO pipeline (name, created_by, tenant_id) VALUES ('wd490-p', "
                    + userId
                    + ", 1)");
        String trigger =
            "INSERT INTO pipeline_trigger (pipeline_id, trigger_type, name, created_by, tenant_id,"
                + " config) VALUES ("
                + pipelineId
                + ", '%s', '%s', "
                + userId
                + ", 1, '{\"tokenHash\":\"h1\",\"webhookId\":\"h1\"}')";
        s.executeUpdate(String.format(trigger, "API", "wd490-t1"));
        assertThatThrownBy(() -> s.executeUpdate(String.format(trigger, "API", "wd490-t2")))
            .isInstanceOfSatisfying(
                SQLException.class, e -> assertThat(e.getErrorCode()).isEqualTo(-10007));
        // 조건 밖(WEBHOOK) 행의 tokenHash 는 API 토큰 유일 검사에 들어가지 않는다.
        assertThat(s.executeUpdate(String.format(trigger, "WEBHOOK", "wd490-t3"))).isEqualTo(1);
      } finally {
        s.executeUpdate("DELETE FROM pipeline_trigger WHERE name LIKE 'wd490-%'");
        s.executeUpdate("DELETE FROM pipeline WHERE name = 'wd490-p'");
        s.executeUpdate(
            "DELETE FROM analytics_query_run WHERE user_id IN"
                + " (SELECT id FROM \"USER\" WHERE username = 'wd490-expr')");
        s.executeUpdate("DELETE FROM \"USER\" WHERE username = 'wd490-expr'");
      }
    }
  }

  /** 이 계정의 테이블·뷰 → 열 이름(대문자) → NULL 허용 여부. */
  private static Map<String, Map<String, Boolean>> tiberoColumns() throws SQLException {
    Map<String, Map<String, Boolean>> out = new HashMap<>();
    try (Connection c = account.dataSource().getConnection();
        Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery("SELECT table_name, column_name, nullable FROM user_tab_columns")) {
      while (rs.next()) {
        // Flyway 이력 테이블은 따옴표 소문자 이름("flyway_schema_history")으로 만들어진다 — 대조 대상이 아니다.
        if (rs.getString(1).equalsIgnoreCase("flyway_schema_history")) continue;
        out.computeIfAbsent(rs.getString(1), k -> new HashMap<>())
            .put(rs.getString(2), "Y".equals(rs.getString(3)));
      }
    }
    return out;
  }

  private static long scalar(Connection c, String sql) throws SQLException {
    try (Statement s = c.createStatement();
        ResultSet rs = s.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  /** ID 를 생략한 INSERT 후 IDENTITY 가 준 값 — 열 이름을 지정해야 ROWID 가 아니라 ID 를 돌려준다(WD-487 실측). */
  private static long insertReturningId(Connection c, String sql) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql, new String[] {"ID"})) {
      ps.executeUpdate();
      try (ResultSet rs = ps.getGeneratedKeys()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }
}
