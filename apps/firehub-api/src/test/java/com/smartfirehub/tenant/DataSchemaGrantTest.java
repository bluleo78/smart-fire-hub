package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;

import com.smartfirehub.support.IntegrationTestBase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;

/**
 * 런타임 롤 전환이 data 스키마를 깨지 않는지 검증한다.
 *
 * <p>연쇄가 세 겹이다. (1) DataTableService 는 런타임에 data 스키마에 CREATE TABLE 을 하므로
 * app_tenant 에게 CREATE 권한이 있어야 한다. (2) 새 테이블의 소유자가 app_tenant 가 되므로 V32 의
 * `ALTER DEFAULT PRIVILEGES FOR ROLE app` 이 적용되지 않아, 대응 기본 권한이 없으면
 * pipeline_executor 가 신규 데이터셋 테이블을 보지 못한다. (3) V83 "이전"부터 존재하던(=소유자가
 * app 인) data 테이블은 DML 권한만으로는 부족하다 — DROP/ALTER/RENAME/TRUNCATE 는 소유자만
 * 실행할 수 있으므로, V86 이 소유권을 app_tenant 로 이전해야 한다.
 *
 * <p>{@link #runtimeCanAlterAndTruncatePreExistingOwnerOwnedTable()} 가 바로 (3)의 회귀 가드다.
 * 기존 버전은 프로브 테이블을 "런타임 롤(app_tenant)"로 생성했기 때문에 프로브가 처음부터
 * app_tenant 소유가 되어, V86 이 고치는 버그(소유자가 app 인 기존 테이블) 를 전혀 검증하지
 * 못했다. 이 테스트는 프로브를 Flyway 소유자 자격증명(app)으로 직접 JDBC 접속해 생성함으로써
 * "V86 이전부터 존재하던 테이블"의 조건을 재현한다.
 *
 * <p>주의: Flyway 는 컨텍스트 부팅 시 한 번만 실행되므로, 이 테스트가 만드는 프로브는 그 시점
 * 이후에 생겨 V86 이 이미 손대지 못한 테이블이다. 소유권 이전을 "직접 SQL로 재현"하면 V86 파일이
 * 삭제되거나 깨져도 테스트가 계속 통과하는(=가드가 아닌) 상태가 되므로, 재현이 아니라 V86
 * 마이그레이션 리소스 파일을 그대로 읽어 실행한다 — 그래야 V86 자체가 회귀 검증 대상이 된다.
 */
class DataSchemaGrantTest extends IntegrationTestBase {

  private static final String TEST_TABLE = "grant_probe_tbl";
  private static final String OWNER_OWNED_TABLE = "grant_probe_owner_tbl";

  @Autowired private DSLContext dsl;

  @Value("${spring.flyway.url}")
  private String ownerUrl;

  @Value("${spring.flyway.user}")
  private String ownerUser;

  @Value("${spring.flyway.password}")
  private String ownerPassword;

  @BeforeEach
  void dropProbeBeforeEach() throws SQLException {
    // 이전 실행이 비정상 종료해 프로브가 남아 있으면 CREATE TABLE 이 충돌하므로, 시작 시에도
    // 멱등하게 정리한다. OWNER_OWNED_TABLE 은 소유권 이전 재현이 실패한 채로 남으면 여전히
    // app 소유일 수 있어, app_tenant(dsl) 로는 DROP 이 안 될 수 있다 — 소유자 자격증명으로 지운다.
    dropAsOwner();
  }

  @AfterEach
  void dropProbe() throws SQLException {
    dropAsOwner();
  }

  private void dropAsOwner() throws SQLException {
    try (Connection ownerConn = DriverManager.getConnection(ownerUrl, ownerUser, ownerPassword);
        Statement stmt = ownerConn.createStatement()) {
      stmt.execute("DROP TABLE IF EXISTS data." + TEST_TABLE);
      stmt.execute("DROP TABLE IF EXISTS data." + OWNER_OWNED_TABLE);
    }
  }

  @Test
  @DisplayName("런타임 롤이 data 스키마에 테이블을 만들 수 있다")
  void runtimeCanCreateTableInDataSchema() {
    dsl.execute("CREATE TABLE data." + TEST_TABLE + " (id bigint primary key, v text)");

    boolean exists =
        dsl.fetchExists(
            dsl.selectOne()
                .from("pg_tables")
                .where(field("schemaname", String.class).eq("data"))
                .and(field("tablename", String.class).eq(TEST_TABLE)));

    assertThat(exists).isTrue();
  }

  @Test
  @DisplayName("런타임 롤이 만든 data 테이블을 pipeline_executor 가 읽을 수 있다")
  void pipelineExecutorSeesNewlyCreatedDataTable() {
    dsl.execute("CREATE TABLE data." + TEST_TABLE + " (id bigint primary key, v text)");

    Boolean canSelect =
        dsl.select(
                field(
                    "has_table_privilege('pipeline_executor', 'data." + TEST_TABLE + "', 'SELECT')",
                    Boolean.class))
            .fetchOne(0, Boolean.class);

    assertThat(canSelect).isTrue();
  }

  @Test
  @DisplayName("V86 이전부터 존재한(app 소유) data 테이블도 런타임 롤이 ALTER/TRUNCATE 할 수 있다")
  void runtimeCanAlterAndTruncatePreExistingOwnerOwnedTable() throws SQLException, IOException {
    // Flyway 는 컨텍스트 부팅 시 딱 한 번 실행되므로, 이 테스트 메서드 안에서 만드는 프로브는
    // 애초에 V86 이 손댈 수 없는 "V86 적용 이후 새로 생긴" 테이블이다. "V86 적용 이전부터
    // 존재하던 테이블" 상황을 이 안에서 재현하려면, 소유자 자격증명(Flyway 와 동일한 app/app)으로
    // 직접 테이블을 만든 뒤 — V86 SQL 문장을 손으로 다시 쓰지 않고 — 실제 마이그레이션 리소스
    // 파일을 읽어 그대로 실행한다. 문장을 손으로 재현하면 V86 파일이 삭제되거나 로직이 깨져도
    // 테스트가 계속 통과해 회귀 가드가 되지 못하므로, V86 자체를 실행 대상으로 삼는다.
    String v86Sql =
        new String(
            new ClassPathResource("db/migration/V86__data_schema_ownership.sql")
                .getInputStream()
                .readAllBytes(),
            StandardCharsets.UTF_8);

    try (Connection ownerConn = DriverManager.getConnection(ownerUrl, ownerUser, ownerPassword);
        Statement stmt = ownerConn.createStatement()) {
      stmt.execute("CREATE TABLE data." + OWNER_OWNED_TABLE + " (id bigint primary key, v text)");

      // --- V86 적용 전 상태: app_tenant 는 이 app 소유 테이블에 DDL/TRUNCATE 를 할 수 없다 ---
      Boolean canTruncateBefore =
          dsl.select(
                  field(
                      "has_table_privilege('app_tenant', 'data."
                          + OWNER_OWNED_TABLE
                          + "', 'TRUNCATE')",
                      Boolean.class))
              .fetchOne(0, Boolean.class);
      assertThat(canTruncateBefore).as("V86 실행 전에는 TRUNCATE 권한이 없어야 한다").isFalse();

      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "ALTER TABLE data." + OWNER_OWNED_TABLE + " ADD COLUMN tmp_col text"))
          .as("V86 실행 전에는 소유자가 아니므로 ALTER 가 실패해야 한다")
          .isInstanceOf(DataAccessException.class);

      // --- V86 마이그레이션 파일을 그대로 실행한다(재현이 아니라 실제 파일) ---
      stmt.execute(v86Sql);
    }

    // --- V86 적용 후 상태: 런타임 롤이 DDL/TRUNCATE 를 모두 수행할 수 있다 ---
    dsl.execute("ALTER TABLE data." + OWNER_OWNED_TABLE + " ADD COLUMN tmp_col text");
    dsl.execute("ALTER TABLE data." + OWNER_OWNED_TABLE + " DROP COLUMN tmp_col");
    dsl.execute("TRUNCATE TABLE data." + OWNER_OWNED_TABLE);

    Boolean canTruncateAfter =
        dsl.select(
                field(
                    "has_table_privilege('app_tenant', 'data."
                        + OWNER_OWNED_TABLE
                        + "', 'TRUNCATE')",
                    Boolean.class))
            .fetchOne(0, Boolean.class);
    assertThat(canTruncateAfter).isTrue();
  }
}
