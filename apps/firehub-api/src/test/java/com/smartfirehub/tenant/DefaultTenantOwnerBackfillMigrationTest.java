package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.PostgresTestContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * V169: 기본 워크스페이스(테넌트 1)에 OWNER 멤버가 하나도 없는 기존 설치본에서, 조건에 맞는 가장 먼저 만든 활성 ADMIN 한 명만 OWNER 로
 * 올리는지 본다(WD-1100).
 *
 * <p><b>왜 새 DB 인가.</b> 공유 테스트 DB 는 이미 V169 까지 적용돼 "OWNER 없음" 전제를 만들 수 없다. 같은 컨테이너에 일회용 DB 를 만들어
 * Flyway 를 target=168 까지 돌리고 소유자 롤(app)로 픽스처를 심은 뒤 V169 을 적용한다(PlatformSuperAdminBackfillMigrationTest 와 같은
 * 방식).
 */
class DefaultTenantOwnerBackfillMigrationTest {

  private String dbName;
  private String url;

  @BeforeEach
  void createDatabase() throws Exception {
    dbName = "v169_mig_" + System.nanoTime();
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
  void OWNER_가_없으면_가장_이른_활성_ADMIN_한_명만_OWNER_가_된다() throws Exception {
    flyway("168").migrate();
    long late;
    long inactive;
    long suspended;
    long plainUser;
    long otherTenantAdmin;
    try (Connection c = connect()) {
      assertThat(roles(c, 1)).as("사전 상태 — 새 설치본(사용자 0명)").isEmpty();

      // 조건에 맞는 ADMIN 중 가장 이른 사람 — 더 이른 사용자들은 아래 이유로 모두 제외된다.
      late = user(c, "late", "2020-01-05 00:00:00", true);
      tenantRole(c, late, 1, "ADMIN");
      membership(c, late, 1, "ACTIVE");

      // 더 이른 ADMIN 이지만 계정 비활성 — 제외.
      inactive = user(c, "inactive", "2020-01-01 00:00:00", false);
      tenantRole(c, inactive, 1, "ADMIN");
      membership(c, inactive, 1, "ACTIVE");

      // 더 이른 ADMIN 이지만 멤버십 정지 — 제외.
      suspended = user(c, "suspended", "2020-01-02 00:00:00", true);
      tenantRole(c, suspended, 1, "ADMIN");
      membership(c, suspended, 1, "SUSPENDED");

      // 가장 이른 활성 비ADMIN 멤버 — 제외.
      plainUser = user(c, "plain", "2019-12-31 00:00:00", true);
      tenantRole(c, plainUser, 1, "USER");
      membership(c, plainUser, 1, "ACTIVE");

      // 테넌트 2 멤버십 — 행이 변하지 않아야 한다.
      try (Statement s = c.createStatement()) {
        s.execute("INSERT INTO tenant (id, slug, name) VALUES (2, 'other', 'Other')");
        s.execute("SELECT provision_tenant_defaults(2)");
      }
      otherTenantAdmin = user(c, "otheradmin", "2020-01-03 00:00:00", true);
      tenantRole(c, otherTenantAdmin, 2, "ADMIN");
      membership(c, otherTenantAdmin, 2, "ACTIVE");
    }

    flyway("169").migrate();

    try (Connection c = connect()) {
      Map<Long, String> expected =
          Map.of(
              late, "OWNER",
              inactive, "MEMBER",
              suspended, "MEMBER",
              plainUser, "MEMBER");
      assertThat(roles(c, 1)).containsExactlyInAnyOrderEntriesOf(expected);
      assertThat(roles(c, 2)).containsExactly(Map.entry(otherTenantAdmin, "MEMBER"));
    }
  }

  /**
   * 이미 OWNER 가 있으면 보정을 건너뛴다. 정지된 OWNER 도 OWNER 행으로 센다(SQL 주석의 「상태 무관」 결정) — 더 이른 활성 ADMIN 을 새로
   * 올리지 않는다.
   */
  @ParameterizedTest(name = "소유자 상태 = {0}")
  @ValueSource(strings = {"ACTIVE", "SUSPENDED"})
  @DisplayName("이미 OWNER 가 있으면(상태 무관) 아무것도 바꾸지 않는다")
  void 이미_OWNER_가_있으면_상태무관_아무것도_바꾸지_않는다(String ownerStatus) throws Exception {
    flyway("168").migrate();
    long owner;
    long admin;
    try (Connection c = connect()) {
      admin = user(c, "admin", "2020-01-01 00:00:00", true);
      tenantRole(c, admin, 1, "ADMIN");
      membership(c, admin, 1, "ACTIVE");
      // 더 늦게 만든 비ADMIN 멤버가 이미 OWNER — 그래도 보정은 건너뛴다.
      owner = user(c, "owner", "2021-01-01 00:00:00", true);
      tenantRole(c, owner, 1, "USER");
      membership(c, owner, 1, ownerStatus);
      try (Statement s = c.createStatement()) {
        s.execute("UPDATE membership SET role = 'OWNER' WHERE user_id = " + owner);
      }
    }

    flyway("169").migrate();

    try (Connection c = connect()) {
      assertThat(roles(c, 1))
          .containsExactlyInAnyOrderEntriesOf(Map.of(owner, "OWNER", admin, "MEMBER"));
    }
  }

  @Test
  void 조건에_맞는_ADMIN_이_없으면_OWNER_를_만들지_않는다() throws Exception {
    flyway("168").migrate();
    long plain;
    try (Connection c = connect()) {
      plain = user(c, "plain", "2020-01-01 00:00:00", true);
      tenantRole(c, plain, 1, "USER");
      membership(c, plain, 1, "ACTIVE");
    }

    flyway("169").migrate();

    try (Connection c = connect()) {
      assertThat(roles(c, 1)).containsExactly(Map.entry(plain, "MEMBER"));
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

  /** 사용자를 만들고 id 를 돌려준다. created_at·is_active 를 명시해 정렬·필터 조건을 고정한다. */
  private static long user(Connection c, String username, String createdAt, boolean active)
      throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO \"user\" (username, password, name, email, is_active, created_at)"
                + " VALUES (?, 'x', ?, ?, ?, ?::timestamp) RETURNING id")) {
      ps.setString(1, username);
      ps.setString(2, username);
      ps.setString(3, username + "@e.com");
      ps.setBoolean(4, active);
      ps.setString(5, createdAt);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  /** 테넌트 역할(role.name)을 그 테넌트의 user_role 로 붙인다. */
  private static void tenantRole(Connection c, long userId, long tenantId, String roleName)
      throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO user_role (user_id, role_id, tenant_id)"
                + " SELECT ?, id, tenant_id FROM role WHERE tenant_id = ? AND name = ?")) {
      ps.setLong(1, userId);
      ps.setLong(2, tenantId);
      ps.setString(3, roleName);
      assertThat(ps.executeUpdate()).as("테넌트 %d 의 %s 역할", tenantId, roleName).isEqualTo(1);
    }
  }

  private static void membership(Connection c, long userId, long tenantId, String status)
      throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO membership (user_id, tenant_id, status) VALUES (?, ?, ?)")) {
      ps.setLong(1, userId);
      ps.setLong(2, tenantId);
      ps.setString(3, status);
      ps.executeUpdate();
    }
  }

  /** 테넌트의 멤버십 (user_id → role). */
  private static Map<Long, String> roles(Connection c, long tenantId) throws Exception {
    Map<Long, String> out = new LinkedHashMap<>();
    try (Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery(
                "SELECT user_id, role FROM membership WHERE tenant_id = "
                    + tenantId
                    + " ORDER BY user_id")) {
      while (rs.next()) out.put(rs.getLong(1), rs.getString(2));
    }
    return out;
  }
}
