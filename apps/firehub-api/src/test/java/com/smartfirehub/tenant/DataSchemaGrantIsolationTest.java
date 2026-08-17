package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.tenant.TenantPipelineRole;
import com.smartfirehub.pipeline.exception.UnsafeSqlException;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import com.smartfirehub.support.IntegrationTestBase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Value;

/**
 * <b>이중 방어 증명(P3-b1 R4)</b> — 테넌트의 파이프라인 실행 롤이 <b>다른 테넌트의 data 스키마</b>에
 * 접근할 수 없음을 <b>권한(grant) 계층 단독</b>으로, 그리고 <b>검증기(SqlValidator) 계층 단독</b>으로
 * 각각 따로 증명한다. 두 계층이 실제로 둘인지(하나가 무너져도 다른 하나가 막는지) 확인하는 것이
 * 이 테스트의 목적이며, 이 밴드 전체의 존재 이유다.
 *
 * <p><b>왜 물리 스키마 리네임 없이 증명할 수 있는가.</b> 현재 물리 스키마는 {@code data} 하나뿐이고
 * (리네임은 P3-b2) 모든 테넌트 롤이 같은 {@code data} 를 본다 — 그래서 공유 스키마에 기대는 증명은
 * 공허하다. 대신 이 테스트는 <b>충돌하지 않는 신규 스키마 {@code data_t9901} 을 스스로 만들고</b>
 * (가산적 — 공유 test DB 의 다른 세션에 영향 0) 그 스키마에 대해 테넌트 1 의 롤이 거부되는지 본다.
 * 스키마 이름은 P3-b2 가 채택할 규약({@code data_t{tenantId}})과 동일한 형태이므로, 리네임이 켜지는
 * 순간 이 증명이 곧 실제 격리의 증명이 된다.
 *
 * <p><b>클래스 레벨 {@code @Transactional} 을 쓰지 않는다.</b> 픽스처가 DDL(스키마·테이블·롤 생성)
 * 이고, 그것이 <b>커밋</b>돼야 별도 커넥션(= 다른 롤)이 볼 수 있다. 트랜잭션 안에 넣으면 프로브
 * 커넥션에 아무것도 보이지 않아 "거부"가 "존재하지 않음"과 구별되지 않는다. 그래서 {@code @BeforeAll}
 * 에서 자기 커밋 DDL 로 만들고 {@code @AfterAll} 에서 <b>자기가 만든 것만</b> 지운다.
 *
 * <p><b>공유 DB 안전 규칙.</b> 이 테스트는 {@code data} 스키마, {@code pipeline_executor},
 * {@code pipeline_executor_t1} 을 <b>절대 변경·회수하지 않는다</b> — 읽기(프로브)만 한다. 생성·삭제
 * 대상은 {@code data_t9901} 스키마와 {@code pipeline_executor_t9901} 롤 둘뿐이다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DataSchemaGrantIsolationTest extends IntegrationTestBase {

  /**
   * 프로브용 "다른 테넌트" id. 실제 테넌트 테이블에는 없는 높은 번호를 쓴다 — 공유 test DB 에서
   * 다른 세션의 테넌트 픽스처(1, 2, ...)와 스키마·롤 이름이 겹치지 않게 하려는 것이다.
   */
  private static final long OTHER_TENANT_ID = 9901L;

  /** P3-b2 가 채택할 규약과 같은 형태의 스키마명 — 이 테스트가 직접 만들고 직접 지운다. */
  private static final String OTHER_SCHEMA = "data_t" + OTHER_TENANT_ID;

  private static final String PROBE_TABLE = "isolation_probe_tbl";

  /** 테넌트 1 의 파이프라인 실행 롤 — V111 이 만든 것. 여기서는 <b>읽기 프로브로만</b> 쓴다. */
  private static final long TENANT_ID = DEFAULT_TEST_TENANT_ID;

  @Value("${spring.flyway.url}")
  private String jdbcUrl;

  @Value("${spring.flyway.user}")
  private String ownerUser;

  @Value("${spring.flyway.password}")
  private String ownerPassword;

  /**
   * 롤 비밀번호 파생 HMAC 키. 비밀번호를 테스트에 하드코딩하지 않고 {@link TenantPipelineRole}
   * 로 파생한다 — 파생 규약이 바뀌면 이 테스트가 같이 따라가야 하고(하드코딩이면 조용히 인증
   * 실패로만 드러난다), 롤 이름·비밀번호 조립점이 하나라는 T1 의 규약을 테스트도 지켜야 한다.
   */
  @Value("${app.pipeline.role-password-secret}")
  private String rolePasswordSecret;

  @BeforeAll
  void createOwnFixtureOnly() throws SQLException {
    // 이전 실행이 비정상 종료해 픽스처가 남아 있을 수 있으므로 시작 시에도 멱등하게 정리한다.
    dropOwnFixtureOnly();
    try (Connection owner = ownerConnection();
        Statement stmt = owner.createStatement()) {
      stmt.execute("CREATE SCHEMA " + OTHER_SCHEMA);
      stmt.execute("CREATE TABLE " + OTHER_SCHEMA + "." + PROBE_TABLE + " (id bigint, v text)");
      stmt.execute("INSERT INTO " + OTHER_SCHEMA + "." + PROBE_TABLE + " VALUES (1, 'secret')");

      // 롤 생성. 비밀번호는 T1 의 파생 규약으로 계산한 값(하드코딩 금지).
      stmt.execute(
          "CREATE ROLE "
              + otherRoleName()
              + " LOGIN PASSWORD '"
              + TenantPipelineRole.password(OTHER_TENANT_ID, rolePasswordSecret)
              + "'");
      // CONNECT 를 명시적으로 준다 — V111 이 테넌트 1 롤에도 명시적으로 GRANT CONNECT 를 하는 것과
      // 같은 이유다(이 DB 는 PUBLIC 의 CONNECT 를 신뢰하지 않는다).
      stmt.execute(
          "GRANT CONNECT ON DATABASE " + quoteIdentifier(currentDatabase(owner)) + " TO "
              + otherRoleName());
      // 자기 스키마에만 권한을 준다. search_path 는 설정하지 않는다 — 이 테스트는 모든 참조를
      // 스키마로 한정하므로 롤 레벨 설정(클러스터 전역이 되기 쉬운 ALTER ROLE)이 아예 필요 없다.
      stmt.execute("GRANT USAGE ON SCHEMA " + OTHER_SCHEMA + " TO " + otherRoleName());
      stmt.execute(
          "GRANT SELECT ON " + OTHER_SCHEMA + "." + PROBE_TABLE + " TO " + otherRoleName());
    }
  }

  @AfterAll
  void dropOwnFixtureOnly() throws SQLException {
    // 삭제 대상은 이 테스트가 만든 두 객체뿐이다. 순서가 중요하다: 롤은 소유 객체나 남은 권한이
    // 있으면 DROP 되지 않으므로 스키마 → DB CONNECT 회수 → DROP OWNED BY → DROP ROLE 순으로 간다.
    // DROP OWNED BY 는 지정한 롤 하나에만 작용한다(다른 롤을 겨냥해서는 절대 쓰지 말 것).
    try (Connection owner = ownerConnection();
        Statement stmt = owner.createStatement()) {
      stmt.execute("DROP SCHEMA IF EXISTS " + OTHER_SCHEMA + " CASCADE");
      String db = quoteIdentifier(currentDatabase(owner));
      stmt.execute(
          "DO $$ BEGIN"
              + " IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '"
              + otherRoleNameLiteral()
              + "') THEN"
              + "   REVOKE ALL ON DATABASE "
              + db
              + " FROM "
              + otherRoleName()
              + ";"
              + "   DROP OWNED BY "
              + otherRoleName()
              + ";"
              + "   DROP ROLE "
              + otherRoleName()
              + ";"
              + " END IF; END $$");
    }
  }

  // ---------------------------------------------------------------------------
  // 단언 1 — 권한(grant) 계층 단독
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("권한 계층 단독: 테넌트 1 롤은 다른 테넌트 스키마를 읽을 수 없다(42501)")
  void grantLayerAloneDeniesCrossTenantSchemaRead() throws SQLException {
    // 레지스트리도, SqlValidator 도 경로에 없다 — 롤 자격증명으로 직접 JDBC 접속해 DB 가 스스로
    // 거부하는지만 본다. 이것이 "검증기를 빼도 막힌다"는 이중 방어의 아래쪽 절반이다.
    //
    // 단언은 예외 클래스가 아니라 SQLSTATE 로 한다. 42501(insufficient_privilege)은 SQLSTATE
    // 클래스 42(문법 오류 계열)에 묶여 있어 Spring 의 예외 번역기가 이것을
    // PermissionDeniedDataAccessException 이 아니라 BadSqlGrammarException 으로 바꾼다(P2-g 실측).
    // 예외 클래스로 단언하면 "권한 거부"와 "문법 오류"를 구별하지 못해, 스키마 이름이 오타여도
    // 초록색으로 통과하는 공허한 테스트가 된다.
    String sqlState =
        sqlStateOf(tenantRoleUser(), tenantRolePassword(),
            "SELECT * FROM " + OTHER_SCHEMA + "." + PROBE_TABLE);

    assertThat(sqlState).as("다른 테넌트 스키마 읽기는 권한 부족(42501)으로 거부돼야 한다").isEqualTo("42501");
  }

  @Test
  @DisplayName("권한 계층 단독: 유니코드 이스케이프로 쓴 스키마 이름도 거부된다(42501)")
  void grantLayerDeniesUnicodeEscapedCrossTenantSchemaRead() throws SQLException {
    // #385 가 기록한 함정: U&"..." 유니코드 이스케이프 식별자는 "이름 문자열 대조"를 무력화할 수
    // 있다. 그 형태를 DB 에 그대로 던져 본다 — PostgreSQL 은 이스케이프를 디코드해 같은 스키마로
    // 해석하므로 grant 계층에서는 우회가 성립하지 않는다. 이 단언이 있는 이유는, 검증기의 이름
    // 대조가 언젠가 뚫려도(또는 검증기가 경로에서 빠져도) 아래쪽 계층이 여전히 막는다는 것을
    // 코드로 못 박아 두는 것이다.
    String sqlState =
        sqlStateOf(tenantRoleUser(), tenantRolePassword(),
            "SELECT * FROM U&\"data_t9901\"." + PROBE_TABLE);

    assertThat(sqlState).as("유니코드 이스케이프 식별자도 같은 스키마로 해석되어 거부돼야 한다").isEqualTo("42501");
  }

  // ---------------------------------------------------------------------------
  // 단언 2 — 검증기(SqlValidator) 계층 단독
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("검증 계층 단독: 다른 테넌트 스키마 참조는 SqlValidator 가 거부한다")
  void validatorAloneRejectsCrossTenantSchemaReference() {
    // 허용 스키마를 'data' 로 고정한 검증기(= 테넌트 1 문맥에서 DataSchema.current() 가 주는 값)에
    // 다른 테넌트 스키마 참조를 넣는다. DB 에 닿지 않고 애플리케이션 계층에서 끊기는지만 본다.
    SqlValidator validator = new SqlValidator("data", false);

    assertThatThrownBy(
            () -> validator.validate("SELECT * FROM " + OTHER_SCHEMA + "." + PROBE_TABLE))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("허용되지 않는 스키마 참조");

    // 큰따옴표 인용 형태도 같은 경로로 거부된다(인용이 화이트리스트를 우회하지 않는다).
    assertThatThrownBy(
            () -> validator.validate("SELECT * FROM \"" + OTHER_SCHEMA + "\"." + PROBE_TABLE))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("허용되지 않는 스키마 참조");
  }

  @Test
  @DisplayName("검증 계층 특성화: 유니코드 이스케이프 식별자는 파싱 실패로 fail-closed 거부된다")
  void validatorRejectsUnicodeEscapedIdentifierByFailingClosed() {
    // 실측 결과를 그대로 고정한다(계획서가 예상한 "검증기 통과"와 다르다).
    // JSqlParser 5.0 은 U&"..." 구문을 아예 파싱하지 못하고(ParseException: unexpected token "&")
    // SqlValidator 는 파싱 실패에 폴백을 두지 않으므로 UnsafeSqlException 으로 fail-closed 거부한다.
    // 즉 이 형태로 "이름 대조를 우회해 통과"하는 일은 이 검증기에서는 일어나지 않는다 —
    // 화이트리스트(허용 목록) 방식이라, 이름을 알아볼 수 없게 만드는 것은 통과가 아니라 거부로
    // 귀결되기 때문이다(블랙리스트라면 반대였을 것이다. #385 의 함정은 그쪽 방향이었다).
    //
    // 그렇다면 이 단언의 값은 무엇인가: (1) 우회가 존재하지 않는다는 사실을 회귀 가드로 고정하고,
    // (2) 파서 버전업 등으로 U&"..." 가 파싱되기 시작하면 이 단언이 빨개져 "그때 이름 대조가
    // 디코드된 이름으로 이뤄지는지" 재검토하도록 만든다. 어느 쪽이든 grant 계층
    // (grantLayerDeniesUnicodeEscapedCrossTenantSchemaRead) 이 독립적으로 막는다.
    SqlValidator validator = new SqlValidator("data", false);

    assertThatThrownBy(() -> validator.validate("SELECT * FROM U&\"data_t9901\"." + PROBE_TABLE))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("SQL 파싱 실패");
  }

  // ---------------------------------------------------------------------------
  // 단언 3 — public 스키마 차단의 진짜 기계장치(테이블 grant 의 부재)
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("public 메타데이터 테이블은 테넌트 롤에게 거부된다(42501)")
  void tenantRoleCannotReadPublicMetadataTables() throws SQLException {
    // ⚠ 여기서 스키마 USAGE 를 단언하지 않는 이유를 반드시 남긴다.
    // V32/V111 의 `REVOKE ALL ON SCHEMA public FROM <role>` 은 USAGE 를 실제로 빼지 못한다 —
    // public 스키마 ACL 에 `=U/pg_database_owner`(= PUBLIC 에게 USAGE)가 있어서, 롤에서 REVOKE 해도
    // PUBLIC 경유 권한이 남는다. 실측: has_schema_privilege('pipeline_executor_t1','public','USAGE')
    // = true. 따라서 그 값이 false 이길 기대하는 단언은 즉시 깨지거나, 더 나쁘게는 미래의 누군가가
    // "REVOKE 를 고쳐야 한다"고 잘못된 방향으로 손대게 만든다(회수 대상은 CREATE 뿐이고 그건 이미
    // 회수된다).
    //
    // 실제로 읽기를 막는 것은 **테이블 레벨 grant 의 부재**다. 그래서 단언도 테이블 접근으로 한다.
    // 이 단언이 여기 있는 이유: 누군가 `GRANT SELECT ON ALL TABLES IN SCHEMA public TO ...` 류를
    // 넓게 주면 파이프라인 실행 롤이 전 테넌트의 메타데이터(user/dataset 등)를 통째로 읽게 되는데,
    // 그때 빨개지는 것은 이 단언뿐이다.
    //
    // "user" 는 예약어라 이중인용이 필요하다. 두 테이블 모두 public 에 실재함을 확인했고(실측),
    // 42P01(undefined_table)이 아니라 42501 이 나오는 것까지 단언해야 "테이블이 없어서 통과"하는
    // 공허한 성공을 배제할 수 있다.
    assertThat(sqlStateOf(tenantRoleUser(), tenantRolePassword(), "SELECT * FROM public.\"user\""))
        .as("public.user 는 테이블 grant 가 없어 42501 로 거부돼야 한다")
        .isEqualTo("42501");
    assertThat(sqlStateOf(tenantRoleUser(), tenantRolePassword(), "SELECT * FROM public.dataset"))
        .as("public.dataset 는 테이블 grant 가 없어 42501 로 거부돼야 한다")
        .isEqualTo("42501");
  }

  // ---------------------------------------------------------------------------
  // 단언 4 — 역방향(공허한 통과 배제)
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("역방향: 9901 롤은 자기 스키마를 읽을 수 있다")
  void otherTenantRoleCanReadItsOwnSchema() throws SQLException {
    // 이 단언이 없으면 단언 1 은 "롤이 애초에 아무것도 못 읽는다" 또는 "스키마·테이블이 만들어지지
    // 않았다"로도 통과하는 공허한 테스트가 된다. 같은 테이블을, 자기 롤로는 실제 행까지 읽어
    // 픽스처가 살아 있고 권한 모델이 "전부 거부"가 아님을 증명한다.
    try (Connection roleConn =
            DriverManager.getConnection(
                jdbcUrl,
                otherRoleNameLiteral(),
                TenantPipelineRole.password(OTHER_TENANT_ID, rolePasswordSecret));
        Statement stmt = roleConn.createStatement();
        ResultSet rs =
            stmt.executeQuery("SELECT v FROM " + OTHER_SCHEMA + "." + PROBE_TABLE + " ORDER BY id")) {
      assertThat(rs.next()).as("자기 스키마의 행이 보여야 한다").isTrue();
      assertThat(rs.getString(1)).isEqualTo("secret");
    }
  }

  @Test
  @DisplayName("역방향: 테넌트 1 롤은 자기 data 스키마에는 USAGE 가 있다")
  void tenantRoleStillHasUsageOnItsOwnDataSchema() throws SQLException {
    // 단언 1 의 공허성 배제를 테넌트 1 롤 쪽에서도 한 번 더 한다 — "이 롤은 어떤 스키마에도 접근할
    // 수 없는 무력한 롤"이 아니라, 자기 스키마에는 접근할 수 있고 남의 스키마에만 거부된다는
    // 대조를 같은 커넥션에서 확인한다. data 스키마에 프로브 테이블을 만들지 않는 것은 의도적이다
    // (공유 스키마를 변경하지 않는다는 이 테스트의 안전 규칙).
    try (Connection roleConn =
            DriverManager.getConnection(jdbcUrl, tenantRoleUser(), tenantRolePassword());
        Statement stmt = roleConn.createStatement();
        ResultSet rs =
            stmt.executeQuery(
                "SELECT has_schema_privilege('data', 'USAGE'),"
                    + " has_schema_privilege('"
                    + OTHER_SCHEMA
                    + "', 'USAGE')")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getBoolean(1)).as("자기 data 스키마에는 USAGE 가 있어야 한다").isTrue();
      assertThat(rs.getBoolean(2)).as("다른 테넌트 스키마에는 USAGE 가 없어야 한다").isFalse();
    }
  }

  // ---------------------------------------------------------------------------
  // 헬퍼
  // ---------------------------------------------------------------------------

  private Connection ownerConnection() throws SQLException {
    return DriverManager.getConnection(jdbcUrl, ownerUser, ownerPassword);
  }

  private static String currentDatabase(Connection conn) throws SQLException {
    try (Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT current_database()")) {
      rs.next();
      return rs.getString(1);
    }
  }

  private static String quoteIdentifier(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  /** 롤 이름은 절대 손으로 이어 붙이지 않는다 — T1 의 조립점을 거친다. */
  private static String otherRoleNameLiteral() {
    return TenantPipelineRole.roleName(OTHER_TENANT_ID);
  }

  private static String otherRoleName() {
    return quoteIdentifier(otherRoleNameLiteral());
  }

  private static String tenantRoleUser() {
    return TenantPipelineRole.roleName(TENANT_ID);
  }

  private String tenantRolePassword() {
    return TenantPipelineRole.password(TENANT_ID, rolePasswordSecret);
  }

  /**
   * {@code sql} 을 주어진 자격증명으로 <b>직접 JDBC 접속</b>해 실행하고, 거부된 경우의 SQLSTATE 를
   * 돌려준다. 성공하면 {@code null} — 호출부의 단언이 "42501 이어야 한다"이므로 성공은 곧 실패로
   * 드러난다.
   *
   * <p>jOOQ/Spring 을 거치지 않고 raw JDBC 를 쓰는 것은 의도적이다. "레지스트리도 검증기도 경로에
   * 없다"가 코드만 봐서 자명해야 하고, 예외 번역 계층이 없으면 {@link SQLException#getSQLState()}
   * 를 그대로 읽을 수 있어 단언이 DB 가 실제로 낸 코드에 직접 걸린다.
   */
  private String sqlStateOf(String user, String password, String sql) throws SQLException {
    try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password);
        Statement stmt = conn.createStatement()) {
      stmt.executeQuery(sql);
      return null;
    } catch (SQLException e) {
      return e.getSQLState();
    }
  }
}
