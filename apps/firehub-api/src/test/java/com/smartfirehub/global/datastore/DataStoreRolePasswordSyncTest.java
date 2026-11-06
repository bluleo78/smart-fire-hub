package com.smartfirehub.global.datastore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.tenant.TenantPipelineRole;
import com.smartfirehub.support.DataStorePostgresTestContainer;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.PostgresTestContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 분리 모드 데이터 저장소 롤 비밀번호 동기화 검증(WD-494 스펙 4절).
 *
 * <p>동기화는 데이터 저장소 Flyway 의 migrate 직후(AFTER_MIGRATE) 소유자 연결로 돈다. 테스트는 같은 경로를 다시 태우려고 {@code
 * dataStoreFlyway} 의 migrate 를 한 번 더 부른다(대기 중 마이그레이션이 없어도 콜백은 매번 돈다).
 *
 * <p>각 테스트는 먼저 대상 롤 비밀번호를 틀린 값으로 바꿔 둔다 — 그러지 않으면 이전 기동이 이미 맞춰 둔 비밀번호 때문에 동기화가 빠져도 통과한다.
 */
class DataStoreRolePasswordSyncTest extends IntegrationTestBase {

  /** 동기화 전 틀린 비밀번호(테스트 전용 값). */
  private static final String STALE = "stale-password-before-sync";

  @DynamicPropertySource
  static void separateDataStoreProperties(DynamicPropertyRegistry registry) {
    DataStorePostgresTestContainer.registerSeparateDataStoreProperties(registry);
  }

  @Autowired private DataStoreFlywayConfig.DataStoreFlyway dataStoreFlyway;

  @Value("${app.pipeline.role-password-secret}")
  private String secret;

  @Value("${firehub.data-store.postgres.password}")
  private String dataStorePassword;

  @Value("${app.pipeline.datasource.password}")
  private String pipelineExecutorPassword;

  @Test
  void passwordSyncSkipsMissingRoles() throws SQLException {
    // ACTIVE 테넌트 둘: 테넌트 1(롤 있음) + 방금 만든 테넌트(데이터 저장소에 롤 없음).
    String withRole = TenantPipelineRole.roleName(DEFAULT_TEST_TENANT_ID);
    long bare = insertActiveTenant();
    String bareRole = TenantPipelineRole.roleName(bare);
    // 이 테스트가 롤을 새로 만들었는지 — 만들었을 때만 지운다. 분리 모드 회귀 JVM 에서는 IntegrationTestBase 가 같은 롤을 미리 만들어 두고
    // 뒤 테스트가 기대므로 무조건 지우면 안 된다.
    boolean createdWithRole = !roleExistsInDataStore(withRole);
    try {
      execDataStore(
          "DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '"
              + withRole
              + "') THEN CREATE ROLE "
              + withRole
              + " LOGIN; END IF; END $$");
      execDataStore("ALTER ROLE " + withRole + " PASSWORD '" + STALE + "'");
      execDataStore("DROP ROLE IF EXISTS " + bareRole);

      assertThatCode(dataStoreFlyway::migrate).doesNotThrowAnyException();

      assertCanLogin(withRole, TenantPipelineRole.password(DEFAULT_TEST_TENANT_ID, secret));
      assertThat(roleExistsInDataStore(bareRole)).as("없는 롤은 만들지 않고 건너뛴다").isFalse();
    } finally {
      deleteTenant(bare);
      if (createdWithRole) {
        // 공유 컨테이너 B 에 흔적을 남기지 않는다 — pg_roles 를 세는 뒤 테스트가 실행 순서에 따라 달라지지 않게.
        execDataStore("DROP ROLE IF EXISTS " + withRole);
      }
    }
  }

  /** 최종 리뷰 지적 4 — PG 플랫폼에서 두 app_tenant 비밀번호가 다를 때만 경고 대상이다. Tibero 플랫폼에는 플랫폼 쪽 app_tenant 가 없다. */
  @Test
  void 플랫폼이_PG이고_app_tenant_비밀번호가_다를_때만_경고한다() {
    assertThat(DataStoreRolePasswordSync.conflictsWithPlatformAppTenant("postgres", "a", "b"))
        .isTrue();
    assertThat(DataStoreRolePasswordSync.conflictsWithPlatformAppTenant("POSTGRES", "a", "b"))
        .isTrue();
    assertThat(DataStoreRolePasswordSync.conflictsWithPlatformAppTenant("postgres", "a", "a"))
        .isFalse();
    assertThat(DataStoreRolePasswordSync.conflictsWithPlatformAppTenant("tibero", "a", "b"))
        .isFalse();
  }

  @Test
  void appTenantPasswordMatchesConfig() throws SQLException {
    execDataStore("ALTER ROLE app_tenant PASSWORD '" + STALE + "'");
    execDataStore("ALTER ROLE pipeline_executor PASSWORD '" + STALE + "'");
    try {
      assertThatThrownBy(() -> assertCanLogin("app_tenant", dataStorePassword))
          .as("전제: 동기화 전에는 설정 비밀번호로 로그인되지 않는다")
          .isInstanceOf(SQLException.class);

      dataStoreFlyway.migrate();

      assertCanLogin("app_tenant", dataStorePassword);
      assertCanLogin("pipeline_executor", pipelineExecutorPassword);
    } finally {
      // 실패해도 공유 컨테이너의 런타임 롤을 설정값으로 되돌린다 — 같은 JVM 의 다른 분리 모드 컨텍스트 풀이 이 롤로 접속한다.
      dataStoreFlyway.migrate();
    }
  }

  // ── 헬퍼 ──────────────────────────────────────────────────────────────────────────────

  private static void assertCanLogin(String role, String password) throws SQLException {
    try (Connection c =
            DriverManager.getConnection(
                DataStorePostgresTestContainer.INSTANCE.getJdbcUrl(), role, password);
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT current_user")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString(1)).isEqualTo(role);
    }
  }

  private static void execDataStore(String sql) throws SQLException {
    try (Connection c = DataStorePostgresTestContainer.openSuperuserConnection();
        Statement st = c.createStatement()) {
      st.execute(sql);
    }
  }

  private static boolean roleExistsInDataStore(String role) throws SQLException {
    try (Connection c = DataStorePostgresTestContainer.openSuperuserConnection();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery("SELECT count(*) FROM pg_roles WHERE rolname = '" + role + "'")) {
      rs.next();
      return rs.getInt(1) > 0;
    }
  }

  /** 플랫폼 DB(첫 컨테이너)에 ACTIVE 스크래치 테넌트를 넣는다 — 동기화는 플랫폼 리포지토리로 테넌트를 읽는다. */
  private static long insertActiveTenant() throws SQLException {
    try (Connection c = platformSuperuser();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "INSERT INTO tenant (slug, name, status) VALUES ('wd494-sync-'"
                    + " || floor(random() * 1000000000)::bigint, 'wd494 sync', 'ACTIVE')"
                    + " RETURNING id")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static void deleteTenant(long id) throws SQLException {
    try (Connection c = platformSuperuser();
        Statement st = c.createStatement()) {
      st.execute("DELETE FROM tenant WHERE id = " + id);
    }
  }

  private static Connection platformSuperuser() throws SQLException {
    return DriverManager.getConnection(
        PostgresTestContainer.INSTANCE.getJdbcUrl(),
        PostgresTestContainer.INSTANCE.getUsername(),
        PostgresTestContainer.INSTANCE.getPassword());
  }
}
