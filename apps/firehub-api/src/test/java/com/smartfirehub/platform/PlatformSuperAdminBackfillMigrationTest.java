package com.smartfirehub.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.PostgresTestContainer;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V166: 운영자(platform_user_role)가 하나도 없는 기존 설치본에서 기본 워크스페이스(테넌트 1)의 가장 먼저 만든 활성 ADMIN 한 명에게만 플랫폼
 * SUPER_ADMIN 을 주는지 본다(WD-757).
 *
 * <p><b>왜 새 DB 인가.</b> 공유 테스트 DB 는 부팅 때 V166 까지 적용됐고 다른 시험의 가입으로 platform_user_role 이 이미 차 있을 수 있어
 * "운영자 없음" 전제를 만들 수 없다. 같은 컨테이너에 일회용 DB 를 만들어 Flyway 를 target=165 까지 돌리고, 소유자 롤(app)로 픽스처를 심은
 * 뒤 V166 을 적용한다(EmbeddingVectorTablesMigrationTest 와 같은 방식). 운영 Flyway 도 소유자 롤로 돈다.
 */
class PlatformSuperAdminBackfillMigrationTest {

  private String dbName;
  private String url;

  @BeforeEach
  void createDatabase() throws Exception {
    dbName = "v166_mig_" + System.nanoTime();
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
  void 운영자가_없으면_조건에_맞는_가장_이른_ADMIN_한_명만_SUPER_ADMIN_을_받는다() throws Exception {
    flyway("165").migrate();
    long expected;
    try (Connection c = connect()) {
      assertThat(platformOperators(c)).as("사전 상태 — 새 설치본(사용자 0명)에서 V82 승격은 아무도 올리지 않았다").isEmpty();

      // 가장 작은 id 지만 더 늦게 만든 활성 ADMIN — 정렬이 id 가 아니라 created_at 먼저임을 드러낸다.
      long late = user(c, "late", "2020-01-05 00:00:00", true);
      tenantRole(c, late, 1, "ADMIN");
      membership(c, late, 1, "ACTIVE");

      // 가장 이른 ADMIN 이지만 계정 비활성 — 콘솔에 들어갈 수 없어 제외.
      long inactive = user(c, "inactive", "2020-01-01 00:00:00", false);
      tenantRole(c, inactive, 1, "ADMIN");
      membership(c, inactive, 1, "ACTIVE");

      // 기본 테넌트 멤버십 정지 — 제외.
      long suspended = user(c, "suspended", "2020-01-02 00:00:00", true);
      tenantRole(c, suspended, 1, "ADMIN");
      membership(c, suspended, 1, "SUSPENDED");

      // ADMIN 역할은 있으나 기본 테넌트 멤버십이 없음 — 제외.
      long noMembership = user(c, "nomember", "2020-01-02 12:00:00", true);
      tenantRole(c, noMembership, 1, "ADMIN");

      // 다른 테넌트(2)의 ADMIN — 기본 테넌트에서는 일반 멤버라 제외.
      c.createStatement().execute("INSERT INTO tenant (id, slug, name) VALUES (2, 'other', 'Other')");
      c.createStatement().execute("SELECT provision_tenant_defaults(2)");
      long otherTenantAdmin = user(c, "otheradmin", "2020-01-03 00:00:00", true);
      tenantRole(c, otherTenantAdmin, 2, "ADMIN");
      membership(c, otherTenantAdmin, 2, "ACTIVE");
      tenantRole(c, otherTenantAdmin, 1, "USER");
      membership(c, otherTenantAdmin, 1, "ACTIVE");

      // 기본 테넌트의 USER 역할만 — 제외.
      long plainUser = user(c, "plain", "2020-01-03 12:00:00", true);
      tenantRole(c, plainUser, 1, "USER");
      membership(c, plainUser, 1, "ACTIVE");

      // created_at 이 같은 두 사람 — id 가 작은 쪽이 받는다.
      expected = user(c, "tieA", "2020-01-04 00:00:00", true);
      tenantRole(c, expected, 1, "ADMIN");
      membership(c, expected, 1, "ACTIVE");
      long tieB = user(c, "tieB", "2020-01-04 00:00:00", true);
      tenantRole(c, tieB, 1, "ADMIN");
      membership(c, tieB, 1, "ACTIVE");
      assertThat(expected).isLessThan(tieB);
    }

    flyway("166").migrate();

    try (Connection c = connect()) {
      assertThat(platformOperators(c)).containsExactly(List.of(expected, superAdminId(c)));

      // 다시 돌려도 운영자가 이미 있으므로 늘지 않는다(조건부 — 멱등).
      c.createStatement().execute(loadMigration());
      assertThat(platformOperators(c)).containsExactly(List.of(expected, superAdminId(c)));
    }
  }

  @Test
  void 운영자가_이미_있으면_아무것도_바꾸지_않는다() throws Exception {
    flyway("165").migrate();
    long operator;
    try (Connection c = connect()) {
      long admin = user(c, "admin", "2020-01-01 00:00:00", true);
      tenantRole(c, admin, 1, "ADMIN");
      membership(c, admin, 1, "ACTIVE");
      // 기존 운영자는 ADMIN 보다 늦게 만든 일반 사용자 — 그래도 보정은 건너뛴다.
      operator = user(c, "operator", "2021-01-01 00:00:00", true);
      tenantRole(c, operator, 1, "USER");
      membership(c, operator, 1, "ACTIVE");
      c.createStatement()
          .execute(
              "INSERT INTO platform_user_role (user_id, platform_role_id)"
                  + " SELECT "
                  + operator
                  + ", id FROM platform_role WHERE name = 'SUPER_ADMIN'");
    }

    flyway("166").migrate();

    try (Connection c = connect()) {
      assertThat(platformOperators(c)).containsExactly(List.of(operator, superAdminId(c)));
    }
  }

  @Test
  void 기본_테넌트에_ADMIN_이_없으면_아무것도_바꾸지_않는다() throws Exception {
    flyway("165").migrate();
    try (Connection c = connect()) {
      long plain = user(c, "plain", "2020-01-01 00:00:00", true);
      tenantRole(c, plain, 1, "USER");
      membership(c, plain, 1, "ACTIVE");
    }

    flyway("166").migrate();

    try (Connection c = connect()) {
      assertThat(platformOperators(c)).isEmpty();
    }
  }

  @Test
  void SUPER_ADMIN_시드가_없어도_실패하지_않고_아무것도_바꾸지_않는다() throws Exception {
    flyway("165").migrate();
    try (Connection c = connect()) {
      long admin = user(c, "admin", "2020-01-01 00:00:00", true);
      tenantRole(c, admin, 1, "ADMIN");
      membership(c, admin, 1, "ACTIVE");
      c.createStatement().execute("DELETE FROM platform_role WHERE name = 'SUPER_ADMIN'");
    }

    flyway("166").migrate();

    try (Connection c = connect()) {
      assertThat(platformOperators(c)).isEmpty();
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

  /** platform_user_role 전체 (user_id, platform_role_id). */
  private static List<List<Long>> platformOperators(Connection c) throws Exception {
    List<List<Long>> out = new ArrayList<>();
    try (Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery(
                "SELECT user_id, platform_role_id FROM platform_user_role ORDER BY user_id")) {
      while (rs.next()) out.add(List.of(rs.getLong(1), rs.getLong(2)));
    }
    return out;
  }

  private static long superAdminId(Connection c) throws Exception {
    try (Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT id FROM platform_role WHERE name = 'SUPER_ADMIN'")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  /** 재실행 검사용 V166 본문. */
  private String loadMigration() throws Exception {
    try (InputStream in =
        getClass()
            .getResourceAsStream("/db/migration/V166__backfill_platform_super_admin.sql")) {
      assertThat(in).as("V166 파일을 클래스패스에서 찾지 못했다").isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
