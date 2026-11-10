package com.smartfirehub.global.datastore;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.DataStorePostgresTestContainer;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.PostgresTestContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 데이터 저장소 마이그레이션({@code db/datastore-postgres} 기준선 V1 과 그 뒤 판)이 PG 설치본의 플랫폼 마이그레이션(V2~최신, 예: V159 의
 * PYTHON 읽기 미확인 표식 표)이 만든 데이터 저장소 객체와 같은 상태를 만드는지 대조한다(WD-494 스펙 4·5절).
 *
 * <p>컨테이너 A(플랫폼 마이그레이션 전부)와 컨테이너 B(데이터 저장소 기준선만)는 <b>다른 서버</b>다 — PG 롤은 서버 전체 객체라 같은 서버의 두 DB 로
 * 비교하면 기준선이 빠뜨린 롤·권한을 플랫폼 마이그레이션이 만든 것으로 착각한다. 두 컨테이너 모두 분리 모드 컨텍스트가 기동하면서 실제 경로(플랫폼 Flyway → 데이터
 * 저장소 Flyway)로 마이그레이션된다.
 *
 * <p>테넌트별 롤({@code pipeline_executor_t{id}}·{@code pipeline_py_t{id}_s{k}})은 비교에서 뺀다 — A 의 V111 이
 * 시드한 {@code pipeline_executor_t1} 은 기준선이 일부러 넣지 않는다(테넌트 롤은 {@code TenantPipelineRoleProvisioner} 가
 * 만든다).
 *
 * <p>이후 PG 마이그레이션이 데이터 저장소 객체를 바꾸면 이 테스트가 빨개진다 — 그때 {@code db/datastore-postgres} 에도 같은 변경을 둔다.
 */
class DataStoreBaselineParityTest extends IntegrationTestBase {

  /** 비교에서 빼는 테넌트별 롤 이름 패턴(정규식). */
  private static final String TENANT_ROLE_PATTERN = "^pipeline_(executor|py)_t[0-9]+";

  /** 기준선이 반드시 갖춰야 하는 확장 — 행 검색(pg_trgm)·공간(postgis)·임베딩(vector)은 데이터 저장소 기능이다. */
  private static final Set<String> REQUIRED_EXTENSIONS = Set.of("postgis", "pg_trgm", "vector");

  /** 기준선이 옮기는 함수(V123). */
  private static final List<String> FUNCTIONS =
      List.of("public.fh_touch_updated_at()", "public.fh_incremental_cursor_candidate()");

  /** 기준선이 만드는 테넌트 무관 롤. */
  private static final List<String> ROLES = List.of("app_tenant", "pipeline_executor");

  @DynamicPropertySource
  static void separateDataStoreProperties(DynamicPropertyRegistry registry) {
    DataStorePostgresTestContainer.registerSeparateDataStoreProperties(registry);
  }

  @Test
  void data_스키마가_두_쪽_모두에_있다() throws SQLException {
    String sql = "SELECT count(*) FROM pg_namespace WHERE nspname = 'data'";
    assertThat(single(platform(), sql)).isEqualTo("1");
    assertThat(single(dataStore(), sql)).isEqualTo("1");
  }

  @Test
  void 데이터_저장소_확장이_기준선에_있다() throws SQLException {
    String sql = "SELECT extname FROM pg_extension";
    assertThat(rows(platform(), sql)).containsAll(REQUIRED_EXTENSIONS);
    assertThat(rows(dataStore(), sql)).containsAll(REQUIRED_EXTENSIONS);
  }

  @Test
  void fh_함수_정의가_같다() throws SQLException {
    for (String fn : FUNCTIONS) {
      String sql = "SELECT pg_get_functiondef('" + fn + "'::regprocedure)";
      assertThat(single(dataStore(), sql)).as(fn).isEqualTo(single(platform(), sql));
    }
  }

  @Test
  void 함수_실행_권한이_같다() throws SQLException {
    for (String role : ROLES) {
      for (String fn : FUNCTIONS) {
        String sql = "SELECT has_function_privilege('" + role + "', '" + fn + "', 'EXECUTE')";
        assertThat(single(dataStore(), sql)).as(role + " " + fn).isEqualTo(single(platform(), sql));
      }
    }
    // 증분 책갈피 후보 함수는 런타임 롤이 반드시 부를 수 있어야 한다(V123 GRANT).
    assertThat(
            single(
                dataStore(),
                "SELECT has_function_privilege('app_tenant',"
                    + " 'public.fh_incremental_cursor_candidate()', 'EXECUTE')"))
        .isEqualTo("t");
  }

  @Test
  void 롤과_속성이_같다() throws SQLException {
    for (String role : ROLES) {
      String sql =
          "SELECT concat_ws(',', rolsuper, rolinherit, rolcreaterole, rolcreatedb, rolcanlogin,"
              + " rolbypassrls) FROM pg_roles WHERE rolname = '"
              + role
              + "'";
      String expected = single(platform(), sql);
      assertThat(expected).as("A 에 " + role + " 이 있어야 한다").isNotNull();
      assertThat(single(dataStore(), sql)).as(role).isEqualTo(expected);
    }
  }

  @Test
  void 소유자가_런타임_롤의_멤버다() throws SQLException {
    // V83: ALTER DEFAULT PRIVILEGES FOR ROLE app_tenant 와 app_tenant 소유 객체 ALTER 에 소유자의 멤버십이 필요하다.
    String sql =
        "SELECT count(*) FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.roleid"
            + " JOIN pg_roles u ON u.oid = m.member"
            + " WHERE r.rolname = 'app_tenant' AND u.rolname = current_user";
    assertThat(single(dataStore(), sql)).isEqualTo(single(platform(), sql)).isEqualTo("1");
  }

  @Test
  void data_스키마_권한이_같다() throws SQLException {
    for (String role : ROLES) {
      for (String priv : List.of("USAGE", "CREATE")) {
        String sql = "SELECT has_schema_privilege('" + role + "', 'data', '" + priv + "')";
        assertThat(single(dataStore(), sql))
            .as(role + " " + priv)
            .isEqualTo(single(platform(), sql));
      }
      // has_database_privilege(..., 'CONNECT') 는 PUBLIC 기본 CONNECT 때문에 GRANT 가 빠져도 늘 참이다 — 기준선의 명시
      // GRANT 를
      // datacl 에서 직접 확인한다(최종 리뷰 지적 8).
      String connect =
          "SELECT count(*) FROM pg_database d CROSS JOIN LATERAL aclexplode(d.datacl) a"
              + " JOIN pg_roles g ON g.oid = a.grantee"
              + " WHERE d.datname = current_database() AND a.privilege_type = 'CONNECT'"
              + " AND g.rolname = '"
              + role
              + "'";
      assertThat(single(dataStore(), connect))
          .as(role + " 명시 CONNECT")
          .isEqualTo(single(platform(), connect))
          .isEqualTo("1");
    }
    assertThat(single(dataStore(), "SELECT has_schema_privilege('app_tenant', 'data', 'CREATE')"))
        .isEqualTo("t");
  }

  @Test
  void data_스키마_기본_권한이_같다() throws SQLException {
    String sql =
        "SELECT r.rolname || '|' || d.defaclobjtype::text || '|' || coalesce(g.rolname, 'PUBLIC') || '|'"
            + " || a.privilege_type"
            + " FROM pg_default_acl d"
            + " JOIN pg_namespace n ON n.oid = d.defaclnamespace"
            + " JOIN pg_roles r ON r.oid = d.defaclrole"
            + " CROSS JOIN LATERAL aclexplode(d.defaclacl) a"
            + " LEFT JOIN pg_roles g ON g.oid = a.grantee"
            + " WHERE n.nspname = 'data'"
            + " AND coalesce(g.rolname, 'PUBLIC') !~ '"
            + TENANT_ROLE_PATTERN
            + "' ORDER BY 1";
    List<String> expected = rows(platform(), sql);
    assertThat(expected).as("A 의 data 기본 권한(V32·V83)").isNotEmpty();
    assertThat(rows(dataStore(), sql)).containsExactlyElementsOf(expected);
  }

  @Test
  void pipeline_executor_의_유효_search_path_가_같다() throws SQLException {
    // A 는 V32 의 DB 한정 없는 설정(setdatabase=0), B 는 IN DATABASE 한정 — 유효 값만 비교한다.
    String sql =
        "SELECT array_to_string(s.setconfig, ',') FROM pg_db_role_setting s"
            + " JOIN pg_roles r ON r.oid = s.setrole"
            + " LEFT JOIN pg_database d ON d.oid = s.setdatabase"
            + " WHERE r.rolname = 'pipeline_executor'"
            + " AND (s.setdatabase = 0 OR d.datname = current_database())"
            + " ORDER BY s.setdatabase DESC LIMIT 1";
    assertThat(single(dataStore(), sql)).isEqualTo(single(platform(), sql)).isNotNull();
  }

  /**
   * PYTHON 읽기 슬롯 미확인 표식 표(플랫폼 V159 / 데이터 저장소 V2, WD-701 4차) — 두 쪽의 열 정의·제약·권한이 같다. 분리 설치는 데이터 저장소
   * 쪽만 쓴다(표식 시각을 pg_stat_activity 와 같은 서버 시계로 적으려고).
   */
  @Test
  void python_읽기_미확인_표식_표가_같다() throws SQLException {
    String columns =
        "SELECT string_agg(column_name || ' ' || data_type || ' ' || is_nullable || ' '"
            + " || coalesce(column_default, '-'), ',' ORDER BY ordinal_position)"
            + " FROM information_schema.columns"
            + " WHERE table_schema = 'public' AND table_name = 'python_read_unconfirmed_slot'";
    String expected = single(platform(), columns);
    assertThat(expected).as("A 에 표가 있어야 한다").isNotNull();
    assertThat(single(dataStore(), columns)).isEqualTo(expected);
    String constraints =
        "SELECT string_agg(contype::text || ' ' || pg_get_constraintdef(oid), ','"
            + " ORDER BY contype, pg_get_constraintdef(oid))"
            + " FROM pg_constraint WHERE conrelid = 'public.python_read_unconfirmed_slot'::regclass";
    assertThat(single(dataStore(), constraints)).isEqualTo(single(platform(), constraints));
    for (String priv : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
      String sql =
          "SELECT has_table_privilege('app_tenant', 'public.python_read_unconfirmed_slot', '"
              + priv
              + "')";
      assertThat(single(dataStore(), sql)).as("app_tenant " + priv).isEqualTo("t");
      assertThat(single(platform(), sql)).as("app_tenant " + priv).isEqualTo("t");
    }
    // 표식 전 세션을 소유자 권한으로 세는 함수(리뷰 2) — 정의·실행 권한이 같고, 런타임 롤이 부를 수 있다.
    String fn = "public.fh_python_read_sessions_before(text[], timestamptz[])";
    String def = "SELECT pg_get_functiondef('" + fn + "'::regprocedure)";
    assertThat(single(dataStore(), def))
        .isEqualTo(single(platform(), def))
        .contains("SECURITY DEFINER");
    String exec = "SELECT has_function_privilege('app_tenant', '" + fn + "', 'EXECUTE')";
    assertThat(single(dataStore(), exec)).isEqualTo("t");
    assertThat(single(platform(), exec)).isEqualTo("t");
  }

  // ── 헬퍼 ──────────────────────────────────────────────────────────────────────────────

  private static Connection platform() throws SQLException {
    return DriverManager.getConnection(
        PostgresTestContainer.INSTANCE.getJdbcUrl(),
        PostgresTestContainer.INSTANCE.getUsername(),
        PostgresTestContainer.INSTANCE.getPassword());
  }

  private static Connection dataStore() throws SQLException {
    return DataStorePostgresTestContainer.openSuperuserConnection();
  }

  /** 첫 행 첫 열(없으면 null). 연결은 닫는다. */
  private static String single(Connection connection, String sql) throws SQLException {
    List<String> r = rows(connection, sql);
    return r.isEmpty() ? null : r.get(0);
  }

  /** 첫 열 전체. 연결은 닫는다. */
  private static List<String> rows(Connection connection, String sql) throws SQLException {
    try (Connection c = connection;
        PreparedStatement ps = c.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      List<String> out = new ArrayList<>();
      while (rs.next()) {
        out.add(rs.getString(1));
      }
      return out;
    }
  }
}
