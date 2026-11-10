package com.smartfirehub.global.platformdb.tibero.flyway;

import static com.smartfirehub.support.tibero.TiberoSql.exec;
import static com.smartfirehub.support.tibero.TiberoSql.longs;
import static com.smartfirehub.support.tibero.TiberoSql.queryLong;
import static com.smartfirehub.support.tibero.TiberoSql.strings;
import static com.smartfirehub.support.tibero.TiberoTenantRlsSchema.SET_TENANT_CALL;
import static com.smartfirehub.support.tibero.TiberoTenantRlsSchema.clearTenant;
import static com.smartfirehub.support.tibero.TiberoTenantRlsSchema.setTenant;
import static com.smartfirehub.support.tibero.TiberoTestEvidence.write;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoTenantRlsSchema;
import com.smartfirehub.support.tibero.TiberoTenantRlsSchema.RuntimeWork;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tibero 테넌트 격리(WD-492) 실서버 시험 — 소유자 + 런타임 두 계정에서 V156.1 과 런타임 권한 콜백이 운영처럼 동작하는지 본다.
 *
 * <p>무엇을(이번 분량): 런타임 계정이 모든 테이블에 DML 권한을 받는지(따옴표가 필요한 {@code "USER"} 포함), IDENTITY 테이블에 id 를 빼고 넣을
 * 수 있는지, 컨텍스트를 직접 바꾸지 못하고 {@code FH_TENANT} 패키지로만 바꾸는지, migrate 를 다시 해도 콜백이 오류 없이 도는지.
 *
 * <p>하네스는 JVM 공용({@link TiberoTenantRlsSchema#shared()})이라 시험마다 자기가 넣은 행과 바꾼 컨텍스트를 지운다.
 */
@TiberoLive
class TiberoTenantRlsLiveTest {

  /** 런타임 계정이 테이블마다 받아야 하는 권한. */
  private static final Set<String> DML = Set.of("SELECT", "INSERT", "UPDATE", "DELETE");

  /** 이 클래스가 넣는 픽스처 행의 표식 — saved_query.name, audit_log.username, "USER".username 에 쓴다. */
  private static final String MARK = "wd492-rls";

  /** update_check 위반(정책 술어를 만족하지 않는 행을 쓰려 함) — 실측. */
  private static final int POLICY_CHECK_VIOLATION = -10067;

  /** NOT NULL 위반(TiberoPlatformDbErrors.NOT_NULL_VIOLATION 과 같은 값). */
  private static final int NOT_NULL_VIOLATION = -10005;

  private static TiberoTenantRlsSchema schema;

  /** 시드 테넌트 1 외에 소유자가 만든 두 번째 테넌트 id(IDENTITY 로 받는다 — 하드코딩하지 않는다). */
  private static long tenant2;

  /** created_by 로 쓰는 픽스처 사용자 id. */
  private static long userId;

  /** 테넌트 2 의 기본 보안 등급 id·기본이 아닌 등급 id. 테넌트 1 의 기본 등급(시드 2)과 달라야 조회 키가 테넌트임을 증명한다. */
  private static long tenant2DefaultLevel;

  private static long tenant2OtherLevel;

  @BeforeAll
  static void setUp() throws SQLException {
    schema = TiberoTenantRlsSchema.shared();
    seedRows();
  }

  /**
   * 소유자 연결(정책에 걸리지 않음)로 두 테넌트의 행을 넣는다 — saved_query 는 테넌트 1·2 에 하나씩, audit_log 는 테넌트 1·2·NULL 에
   * 하나씩. 테넌트 2 에는 기본 등급과 기본이 아닌 등급을 하나씩 만든다.
   */
  private static void seedRows() throws SQLException {
    try (Connection c = schema.owner().dataSource().getConnection()) {
      exec(c, "INSERT INTO tenant (slug, name) VALUES ('" + MARK + "', '" + MARK + "')");
      tenant2 = queryLong(c, "SELECT id FROM tenant WHERE slug = '" + MARK + "'");
      exec(c, "INSERT INTO \"USER\" (username, password, name) VALUES ('" + MARK + "', 'x', 'x')");
      userId = queryLong(c, "SELECT id FROM \"USER\" WHERE username = '" + MARK + "'");
      exec(
          c,
          "INSERT INTO security_level (tenant_id, rank, name, is_default) VALUES ("
              + tenant2
              + ", 1, 'wd492-기본', 1)");
      exec(
          c,
          "INSERT INTO security_level (tenant_id, rank, name, is_default) VALUES ("
              + tenant2
              + ", 2, 'wd492-기타', 0)");
      tenant2DefaultLevel =
          queryLong(
              c,
              "SELECT id FROM security_level WHERE tenant_id = " + tenant2 + " AND is_default = 1");
      tenant2OtherLevel =
          queryLong(
              c,
              "SELECT id FROM security_level WHERE tenant_id = " + tenant2 + " AND is_default = 0");
      for (long t : new long[] {1, tenant2}) {
        exec(
            c,
            "INSERT INTO saved_query (name, sql_text, created_by, tenant_id) VALUES ('"
                + MARK
                + "', 'SELECT 1', "
                + userId
                + ", "
                + t
                + ")");
      }
      for (String t : new String[] {"1", String.valueOf(tenant2), "NULL"}) {
        exec(
            c,
            "INSERT INTO audit_log (username, action_type, \"RESOURCE\", tenant_id) VALUES ('"
                + MARK
                + "', 'TEST', 'TEST', "
                + t
                + ")");
      }
    }
  }

  /** 이 클래스가 넣은 행을 FK 역순으로 지운다(소유자 연결 — 정책에 걸리지 않는다). */
  @AfterAll
  static void tearDown() throws SQLException {
    if (schema == null || userId == 0) return;
    try (Connection c = schema.owner().dataSource().getConnection()) {
      exec(c, "DELETE FROM dataset WHERE created_by = " + userId);
      exec(c, "DELETE FROM role WHERE name LIKE '" + MARK + "%'");
      exec(c, "DELETE FROM saved_query WHERE created_by = " + userId);
      exec(c, "DELETE FROM audit_log WHERE username = '" + MARK + "'");
      exec(c, "DELETE FROM security_level WHERE tenant_id = " + tenant2);
      exec(c, "DELETE FROM tenant WHERE id = " + tenant2);
      exec(c, "DELETE FROM \"USER\" WHERE id = " + userId);
    }
  }

  @Test
  void 테넌트_1_은_자기_행만_본다() throws SQLException {
    List<Long> seen =
        withRuntime(
            1L, c -> longs(c, "SELECT tenant_id FROM saved_query WHERE name = '" + MARK + "'"));
    assertThat(seen).containsExactly(1L);
  }

  @Test
  void 테넌트가_없으면_0행이다() throws SQLException {
    List<Long> seen =
        withRuntime(
            null, c -> longs(c, "SELECT tenant_id FROM saved_query WHERE name = '" + MARK + "'"));
    assertThat(seen).isEmpty();
  }

  @Test
  void 다른_테넌트로_쓰면_거부된다() throws SQLException {
    withRuntime(
        1L,
        c -> {
          assertThatThrownBy(
                  () ->
                      exec(
                          c,
                          "INSERT INTO saved_query (name, sql_text, created_by, tenant_id) VALUES ('"
                              + MARK
                              + "-other', 'SELECT 1', "
                              + userId
                              + ", "
                              + tenant2
                              + ")"))
              .isInstanceOfSatisfying(
                  SQLException.class,
                  e -> assertThat(e.getErrorCode()).isEqualTo(POLICY_CHECK_VIOLATION));
          return null;
        });
  }

  /**
   * 다른 테넌트 행은 UPDATE·DELETE 술어에서도 걸러져 0행이다(PG USING 과 같음) — id 로 정확히 겨눠도 그렇다. 소유자 연결로 그 행이 바뀌지 않았는지
   * 확인한다.
   */
  @Test
  void 다른_테넌트_행의_UPDATE_DELETE_는_0행이다() throws SQLException {
    String rowSql =
        "SELECT id FROM saved_query WHERE name = '" + MARK + "' AND tenant_id = " + tenant2;
    long otherRow;
    try (Connection c = schema.owner().dataSource().getConnection()) {
      otherRow = queryLong(c, rowSql);
    }

    int[] counts =
        withRuntime(
            1L,
            c -> {
              try (Statement st = c.createStatement()) {
                int updated =
                    st.executeUpdate(
                        "UPDATE saved_query SET sql_text = 'SELECT 2' WHERE id = " + otherRow);
                int deleted = st.executeUpdate("DELETE FROM saved_query WHERE id = " + otherRow);
                return new int[] {updated, deleted};
              }
            });

    assertThat(counts).containsExactly(0, 0);
    try (Connection c = schema.owner().dataSource().getConnection()) {
      assertThat(longs(c, rowSql)).as("테넌트 2 행은 남아 있다").containsExactly(otherRow);
      assertThat(strings(c, "SELECT sql_text FROM saved_query WHERE id = " + otherRow))
          .as("테넌트 2 행은 바뀌지 않았다")
          .containsExactly("SELECT 1");
    }
  }

  /** 자기 행의 tenant_id 를 다른 테넌트로 바꾸는 UPDATE 는 update_check 로 거부된다(PG WITH CHECK 와 같음). */
  @Test
  void tenant_id_를_다른_테넌트로_바꾸는_UPDATE_는_거부된다() throws SQLException {
    long ownRow;
    try (Connection c = schema.owner().dataSource().getConnection()) {
      ownRow =
          queryLong(c, "SELECT id FROM saved_query WHERE name = '" + MARK + "' AND tenant_id = 1");
    }
    withRuntime(
        1L,
        c -> {
          assertThatThrownBy(
                  () ->
                      exec(
                          c,
                          "UPDATE saved_query SET tenant_id = "
                              + tenant2
                              + " WHERE id = "
                              + ownRow))
              .isInstanceOfSatisfying(
                  SQLException.class,
                  e -> assertThat(e.getErrorCode()).isEqualTo(POLICY_CHECK_VIOLATION));
          return null;
        });
    try (Connection c = schema.owner().dataSource().getConnection()) {
      assertThat(longs(c, "SELECT tenant_id FROM saved_query WHERE id = " + ownRow))
          .as("거부된 UPDATE 뒤에도 테넌트 1 행이다")
          .containsExactly(1L);
    }
  }

  @Test
  void tenant_id_를_빼면_컨텍스트_테넌트로_채운다() throws SQLException {
    String name = MARK + "-default";
    withRuntime(
        tenant2,
        c ->
            exec(
                c,
                "INSERT INTO saved_query (name, sql_text, created_by) VALUES ('"
                    + name
                    + "', 'SELECT 1', "
                    + userId
                    + ")"));
    try (Connection c = schema.owner().dataSource().getConnection()) {
      assertThat(longs(c, "SELECT tenant_id FROM saved_query WHERE name = '" + name + "'"))
          .containsExactly(tenant2);
    }
  }

  @Test
  void 컨텍스트가_없으면_tenant_id_를_뺀_INSERT_는_실패한다() throws SQLException {
    withRuntime(
        null,
        c -> {
          assertThatThrownBy(
                  () ->
                      exec(
                          c,
                          "INSERT INTO saved_query (name, sql_text, created_by) VALUES ('"
                              + MARK
                              + "-none', 'SELECT 1', "
                              + userId
                              + ")"))
              .isInstanceOfSatisfying(
                  SQLException.class,
                  e -> assertThat(e.getErrorCode()).isEqualTo(NOT_NULL_VIOLATION));
          return null;
        });
  }

  /** PG 의 IS NOT DISTINCT FROM 의미 — 테넌트가 없으면 tenant_id 가 NULL 인 행만, 있으면 그 테넌트 행만. */
  @Test
  void audit_log_는_테넌트가_없으면_tenant_id_NULL_행만_본다() throws SQLException {
    String sql = "SELECT tenant_id FROM audit_log WHERE username = '" + MARK + "'";
    List<Long> none = withRuntime(null, c -> longs(c, sql));
    List<Long> one = withRuntime(1L, c -> longs(c, sql));

    assertThat(none).containsExactly((Long) null);
    assertThat(one).containsExactly(1L);
  }

  /**
   * 앱의 흔한 경로(tenant_id·보안 등급 열을 둘 다 뺀 INSERT)에서 열 DEFAULT 가 테넌트를 채우고 트리거가 그 테넌트의 기본 등급을 채운다. 테넌트 2
   * 의 기본 등급은 테넌트 1 의 것과 달라 조회 키가 테넌트임을 증명한다. 명시 값은 그대로 둔다.
   */
  @Test
  void dataset_과_role_은_보안_등급을_빼면_테넌트_기본_등급으로_채운다() throws SQLException {
    withRuntime(
        tenant2,
        c -> {
          exec(
              c,
              "INSERT INTO dataset (name, table_name, created_by, storage_type, origin_type)"
                  + " VALUES ('d1', 'wd492_rls_d1', "
                  + userId
                  + ", 'TABLE', 'SOURCE')");
          exec(
              c,
              "INSERT INTO dataset (name, table_name, created_by, storage_type, origin_type,"
                  + " security_level_id) VALUES ('d2', 'wd492_rls_d2', "
                  + userId
                  + ", 'TABLE', 'SOURCE', "
                  + tenant2OtherLevel
                  + ")");
          exec(c, "INSERT INTO role (name) VALUES ('" + MARK + "-r1')");
          exec(
              c,
              "INSERT INTO role (name, max_security_level_id) VALUES ('"
                  + MARK
                  + "-r2', "
                  + tenant2OtherLevel
                  + ")");
          return null;
        });
    try (Connection c = schema.owner().dataSource().getConnection()) {
      assertThat(
              longs(
                  c,
                  "SELECT security_level_id FROM dataset WHERE table_name = 'wd492_rls_d1'"
                      + " AND tenant_id = "
                      + tenant2))
          .containsExactly(tenant2DefaultLevel);
      assertThat(
              longs(c, "SELECT security_level_id FROM dataset WHERE table_name = 'wd492_rls_d2'"))
          .containsExactly(tenant2OtherLevel);
      assertThat(
              longs(
                  c,
                  "SELECT max_security_level_id FROM role WHERE name = '"
                      + MARK
                      + "-r1' AND tenant_id = "
                      + tenant2))
          .containsExactly(tenant2DefaultLevel);
      assertThat(longs(c, "SELECT max_security_level_id FROM role WHERE name = '" + MARK + "-r2'"))
          .containsExactly(tenant2OtherLevel);
    }
  }

  /** 소유자 세션(Flyway·설치·백필)은 정책 함수가 NULL 을 돌려줘 컨텍스트 없이도 두 테넌트 행을 모두 본다. */
  @Test
  void 소유자_세션은_걸러지지_않는다() throws SQLException {
    try (Connection c = schema.owner().dataSource().getConnection()) {
      assertThat(longs(c, "SELECT tenant_id FROM saved_query WHERE name = '" + MARK + "'"))
          .containsExactlyInAnyOrder(1L, tenant2);
    }
  }

  /**
   * 콜백이 소유자 스키마의 모든 테이블(휴지통·Flyway 이력 제외)에 SELECT·INSERT·UPDATE·DELETE 를 줬다. 테이블 목록을 기준으로 돌아야 콜백이
   * 아무것도 주지 않았을 때 헛통과하지 않는다.
   */
  @Test
  void 런타임_계정은_모든_테이블에_DML_권한이_있다() throws SQLException {
    String grantee = schema.runtime().name();
    List<String> tables = new ArrayList<>();
    Map<String, Set<String>> granted = new HashMap<>();
    try (Connection c = schema.owner().dataSource().getConnection()) {
      try (Statement st = c.createStatement();
          ResultSet rs =
              st.executeQuery(
                  "SELECT TABLE_NAME FROM USER_TABLES WHERE TABLE_NAME NOT LIKE 'BIN$%'"
                      + " AND TABLE_NAME <> 'flyway_schema_history'")) {
        while (rs.next()) tables.add(rs.getString(1));
      }
      try (PreparedStatement ps =
          c.prepareStatement(
              "SELECT TABLE_NAME, PRIVILEGE FROM USER_TAB_PRIVS WHERE GRANTEE = ?")) {
        ps.setString(1, grantee);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            granted.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getString(2));
          }
        }
      }
    }
    Map<String, Set<String>> missing = new TreeMap<>();
    for (String t : tables) {
      Set<String> lack = new HashSet<>(DML);
      lack.removeAll(granted.getOrDefault(t, Set.of()));
      if (!lack.isEmpty()) missing.put(t, lack);
    }
    write("런타임 권한: 테이블 " + tables.size() + "개, 빠진 권한 " + missing);
    // 기준선 V155 테이블 75개 + V156(login_ip_failures) — 목록이 비면 아래 단언이 헛통과한다.
    assertThat(tables)
        .as("기준선 테이블이 있어야 헛통과하지 않는다")
        .hasSizeGreaterThanOrEqualTo(76)
        .contains("USER");
    assertThat(missing).as("런타임 계정에 DML 권한이 빠진 테이블").isEmpty();
  }

  /** IDENTITY 시퀀스(ISEQ$$_*) SELECT 권한까지 받아 런타임 계정이 id 를 빼고 넣을 수 있다 — 따옴표가 필요한 "USER" 테이블로 본다. */
  @Test
  void 런타임은_id_를_빼고_IDENTITY_테이블에_넣을_수_있다() throws SQLException {
    try (Connection c = schema.runtimeConnection();
        Statement st = c.createStatement()) {
      int rows =
          st.executeUpdate(
              "INSERT INTO \"USER\" (username, password, name) VALUES ('wd492-rt', 'x', 'x')");
      assertThat(rows).isEqualTo(1);
    } finally {
      schema.owner().execute("DELETE FROM \"USER\" WHERE username = 'wd492-rt'");
    }
  }

  /** 컨텍스트는 FH_TENANT 패키지에만 묶여 있다 — 런타임 계정이 DBMS_SESSION 을 직접 부르면 거부된다(-17004). */
  @Test
  void 런타임은_컨텍스트를_직접_바꿀_수_없다() throws SQLException {
    try (Connection c = schema.runtimeConnection();
        Statement st = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  st.execute(
                      "BEGIN DBMS_SESSION.SET_CONTEXT('"
                          + schema.contextName()
                          + "','TENANT_ID','2'); END;"))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getErrorCode()).isEqualTo(-17004));
      assertThat(tenantId(c)).isNull();
    }
  }

  /** 패키지로 테넌트를 정하고 지운다. NULL 테넌트는 거부한다(-20001). */
  @Test
  void 패키지로_테넌트를_설정하고_지운다() throws SQLException {
    try (Connection c = schema.runtimeConnection()) {
      try {
        setTenant(c, 7);
        assertThat(tenantId(c)).isEqualTo("7");

        clearTenant(c);
        assertThat(tenantId(c)).isNull();

        try (CallableStatement cs = c.prepareCall(SET_TENANT_CALL)) {
          cs.setNull(1, Types.NUMERIC);
          assertThatThrownBy(cs::execute)
              .isInstanceOfSatisfying(
                  SQLException.class, e -> assertThat(e.getErrorCode()).isEqualTo(-20001));
        }
      } finally {
        // 컨텍스트는 세션 범위다 — 공용 풀의 다음 사용자에게 남기지 않는다.
        clearTenant(c);
      }
    }
  }

  /**
   * V156.1 객체가 모두 VALID 다. Tibero 는 컴파일 오류가 있어도 객체를 INVALID 로 만들고 migrate 를 성공시킬 수 있다 — 정책 함수와 보안
   * 등급 트리거의 컴파일 오류를 여기서 잡는다(INVALID 트리거는 dataset·role INSERT 를 모두 깨뜨린다).
   */
  @Test
  void V156_1_객체는_모두_유효하다() throws SQLException {
    Map<String, String> status = new TreeMap<>();
    try (Connection c = schema.owner().dataSource().getConnection();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT OBJECT_NAME || ' ' || OBJECT_TYPE, STATUS FROM USER_OBJECTS"
                    + " WHERE OBJECT_NAME IN ('FH_TENANT', 'FH_TENANT_POLICY',"
                    + " 'FH_TENANT_POLICY_NULLABLE', 'FH_DEFAULT_SECURITY_LEVEL',"
                    + " 'TRG_DATASET_DEFAULT_SECURITY_LEVEL',"
                    + " 'TRG_ROLE_DEFAULT_SECURITY_LEVEL')")) {
      while (rs.next()) status.put(rs.getString(1), rs.getString(2));
    }
    write("V156.1 객체 상태 " + status);
    assertThat(status)
        .containsOnlyKeys(
            "FH_TENANT PACKAGE",
            "FH_TENANT PACKAGE BODY",
            "FH_TENANT_POLICY FUNCTION",
            "FH_TENANT_POLICY_NULLABLE FUNCTION",
            "FH_DEFAULT_SECURITY_LEVEL FUNCTION",
            "TRG_DATASET_DEFAULT_SECURITY_LEVEL TRIGGER",
            "TRG_ROLE_DEFAULT_SECURITY_LEVEL TRIGGER");
    assertThat(status.values()).containsOnly("VALID");
  }

  /**
   * 이미 마이그레이션한 스키마에서 같은 설정으로 다시 migrate — 적용할 것은 없고, 권한 콜백은 이미 있는 권한을 건너뛰며 오류 없이 돈다(재기동). 첫 migrate
   * 뒤 소유자가 새로 만든 테이블은 다시 migrate 할 때 빠진 DML 권한 4개를 받는다 — 콜백이 「없는 권한만 준다」면서 새 객체를 놓치지 않는지 본다. 임시
   * 테이블은 tenant_id 가 없어 정책 카탈로그 시험에 걸리지 않고, 끝나면 PURGE 로 지운다.
   */
  @Test
  void migrate_를_다시_해도_권한_콜백이_오류_없이_돈다() throws SQLException {
    String probe = "WD492_GRANT_PROBE";
    try (Connection c = schema.owner().dataSource().getConnection()) {
      exec(c, "CREATE TABLE " + probe + " (id NUMBER(19) PRIMARY KEY)");
    }
    try {
      assertThat(runtimePrivileges(probe)).as("만든 직후에는 권한이 없다").isEmpty();

      MigrateResult again = schema.flyway().migrate();

      assertThat(again.success).isTrue();
      assertThat(again.migrationsExecuted).isZero();
      Set<String> granted = runtimePrivileges(probe);
      write("다시 migrate 뒤 새 테이블 권한: " + new TreeMap<>(Map.of(probe, granted)));
      assertThat(granted).as("새 테이블에 빠진 DML 권한을 준다").isEqualTo(DML);
    } finally {
      try (Connection c = schema.owner().dataSource().getConnection()) {
        exec(c, "DROP TABLE " + probe + " PURGE");
      }
    }
  }

  /** 소유자 연결로 읽은 런타임 계정의 그 테이블 권한. */
  private static Set<String> runtimePrivileges(String table) throws SQLException {
    Set<String> out = new HashSet<>();
    try (Connection c = schema.owner().dataSource().getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT PRIVILEGE FROM USER_TAB_PRIVS WHERE GRANTEE = ? AND TABLE_NAME = ?")) {
      ps.setString(1, schema.runtime().name());
      ps.setString(2, table);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) out.add(rs.getString(1));
      }
    }
    return out;
  }

  /** 공용 하네스의 런타임 커넥션에서 테넌트를 정하고(null 이면 심지 않고) 일을 한 뒤 지운다. */
  private static <T> T withRuntime(Long tenant, RuntimeWork<T> work) throws SQLException {
    return schema.withRuntime(tenant, work);
  }

  /** 이 세션의 컨텍스트 테넌트(없으면 null). */
  private static String tenantId(Connection c) throws SQLException {
    return schema.context(c, "TENANT_ID");
  }
}
