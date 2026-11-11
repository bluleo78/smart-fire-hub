package com.smartfirehub.user.repository;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** {@link AccessFixtures} 의 PG 구현 — jOOQ 평문 SQL. RLS 테이블은 그 테넌트 트랜잭션 안에서만 만지고 지운다(WD-706). */
public class PgAccessFixtures implements AccessFixtures {

  private final DSLContext dsl;
  private final TransactionTemplate tx;
  private final List<Long> tenants = new ArrayList<>();
  private final List<Long> users = new ArrayList<>();
  private final List<Long> permissions = new ArrayList<>();
  private final List<String> systemSettingKeys = new ArrayList<>();
  private final List<Long> platformRoles = new ArrayList<>();
  private final List<Long> auditLogs = new ArrayList<>();

  /** 정책을 우회하는 소유자 커넥션(테넌트와 무관한 실제 행 확인용). */
  private final JdbcTemplate ownerJdbc;

  public PgAccessFixtures(DSLContext dsl, TransactionTemplate tx, DataSource schemaOwner) {
    this.dsl = dsl;
    this.tx = tx;
    this.ownerJdbc = new JdbcTemplate(schemaOwner);
  }

  @Override
  public long createTenant() {
    long id = TenantRlsTestSupport.createActiveTenant(dsl, "acc");
    tenants.add(id);
    return id;
  }

  @Override
  public long createUser(String username, String email) {
    long id =
        dsl.fetchOne(
                "insert into \"user\" (username, email, password, name) values (?, ?, 'x', 'n') returning id",
                username,
                email)
            .get(0, Long.class);
    users.add(id);
    return id;
  }

  @Override
  public void trackUser(long userId) {
    users.add(userId);
  }

  @Override
  public void setUserActive(long userId, boolean active) {
    dsl.execute("update \"user\" set is_active = ? where id = ?", active, userId);
  }

  @Override
  public void createMembership(long userId, long tenantId, String status) {
    dsl.execute(
        "insert into membership (user_id, tenant_id, role, status) values (?, ?, 'MEMBER', ?)",
        userId,
        tenantId,
        status);
  }

  @Override
  public void setMembershipRole(long userId, long tenantId, String role) {
    dsl.execute(
        "update membership set role = ? where user_id = ? and tenant_id = ?",
        role,
        userId,
        tenantId);
  }

  @Override
  public long createRole(long tenantId, String name, String description) {
    return inTenant(
        tenantId,
        () ->
            dsl.fetchOne(
                    "insert into role (name, description) values (?, ?) returning id",
                    name,
                    description)
                .get(0, Long.class));
  }

  @Override
  public void grantRole(long tenantId, long userId, long roleId) {
    inTenant(
        tenantId,
        () ->
            dsl.execute("insert into user_role (user_id, role_id) values (?, ?)", userId, roleId));
  }

  @Override
  public void grantPermission(long tenantId, long roleId, long permissionId) {
    inTenant(
        tenantId,
        () ->
            dsl.execute(
                "insert into role_permission (role_id, permission_id) values (?, ?)",
                roleId,
                permissionId));
  }

  @Override
  public long createPermission(String code, String category, String description) {
    long id =
        dsl.fetchOne(
                "insert into permission (code, description, category) values (?, ?, ?) returning id",
                code,
                description,
                category)
            .get(0, Long.class);
    permissions.add(id);
    return id;
  }

  @Override
  public void clearRoleUpdatedAt(long tenantId, long roleId) {
    inTenant(tenantId, () -> dsl.execute("update role set updated_at = null where id = ?", roleId));
  }

  @Override
  public List<Long> permissionIdsOfRole(long tenantId, long roleId) {
    return inTenant(
        tenantId,
        () ->
            dsl.fetch(
                    "select permission_id from role_permission where role_id = ? order by permission_id",
                    roleId)
                .getValues(0, Long.class));
  }

  @Override
  public void setTenantStatus(long tenantId, String status) {
    dsl.execute("update tenant set status = ? where id = ?", status, tenantId);
  }

  @Override
  public String tenantSlug(long tenantId) {
    return dsl.fetchValue("select slug from tenant where id = ?", tenantId).toString();
  }

  @Override
  public String tenantName(long tenantId) {
    return dsl.fetchValue("select name from tenant where id = ?", tenantId).toString();
  }

  @Override
  public void provisionDefaults(long tenantId) {
    // SECURITY DEFINER 함수라 테넌트 트랜잭션 안에서 불러도 원본 테넌트(1) 행을 읽는다.
    inTenant(tenantId, () -> dsl.fetch("select provision_tenant_defaults(?)", tenantId));
  }

  @Override
  public long roleIdByName(long tenantId, String name) {
    Long id =
        inTenant(
            tenantId,
            () ->
                dsl.fetchOptional("select id from role where name = ?", name)
                    .map(r -> r.get(0, Long.class))
                    .orElseThrow(
                        () ->
                            new IllegalStateException(
                                "테넌트 " + tenantId + " 에 역할 " + name + " 없음")));
    return id;
  }

  @Override
  public List<String> systemRoleNames(long tenantId) {
    return inTenant(
        tenantId,
        () ->
            dsl.fetch(
                    "select name from role where tenant_id = ? and is_system = true order by name",
                    tenantId)
                .getValues(0, String.class));
  }

  @Override
  public int countUserRoles(long tenantId, long userId) {
    return inTenant(
        tenantId,
        () ->
            Integer.parseInt(
                dsl.fetchValue(
                        "select count(*) from user_role where tenant_id = ? and user_id = ?",
                        tenantId,
                        userId)
                    .toString()));
  }

  /** 행 수를 셀 수 있는 테넌트 테이블(고정 목록) — 이름을 SQL 에 이어 붙이므로 목록 밖은 거절한다. */
  static final Set<String> COUNTABLE_TABLES =
      Set.of(
          "role",
          "role_permission",
          "security_level",
          "dataset_category",
          "report_template",
          "tenant_settings");

  @Override
  public int countTenantRows(String table, long tenantId) {
    if (!COUNTABLE_TABLES.contains(table)) {
      throw new IllegalArgumentException(table);
    }
    return inTenant(
        tenantId,
        () ->
            Integer.parseInt(
                dsl.fetchValue("select count(*) from " + table + " where tenant_id = ?", tenantId)
                    .toString()));
  }

  @Override
  public void insertSystemSetting(
      String key, String value, String description, LocalDateTime updatedAt) {
    dsl.execute(
        "insert into system_settings (key, value, description, updated_at) values (?, ?, ?, ?)",
        key,
        value,
        description,
        updatedAt);
    systemSettingKeys.add(key);
  }

  @Override
  public Long tenantSettingUpdatedBy(long tenantId, String key) {
    return inTenant(
        tenantId,
        () ->
            dsl.fetchOptional(
                    "select updated_by from tenant_settings where tenant_id = ? and key = ?",
                    tenantId,
                    key)
                .orElseThrow()
                .get(0, Long.class));
  }

  @Override
  public void trackTenant(long tenantId) {
    tenants.add(tenantId);
  }

  @Override
  public void setTenantCreatedAt(long tenantId, LocalDateTime createdAt) {
    dsl.execute("update tenant set created_at = ? where id = ?", createdAt, tenantId);
  }

  @Override
  public void setUserName(long userId, String name) {
    dsl.execute("update \"user\" set name = ? where id = ?", name, userId);
  }

  @Override
  public void setUserCreatedAt(long userId, LocalDateTime createdAt) {
    dsl.execute("update \"user\" set created_at = ? where id = ?", createdAt, userId);
  }

  @Override
  public long createPlatformRole(String name) {
    long id =
        dsl.fetchOne("insert into platform_role (name) values (?) returning id", name)
            .get(0, Long.class);
    platformRoles.add(id);
    return id;
  }

  @Override
  public void grantPlatformPermission(long platformRoleId, long permissionId) {
    dsl.execute(
        "insert into platform_role_permission (platform_role_id, permission_id) values (?, ?)",
        platformRoleId,
        permissionId);
  }

  @Override
  public void grantPlatformRole(long userId, long platformRoleId) {
    dsl.execute(
        "insert into platform_user_role (user_id, platform_role_id) values (?, ?)",
        userId,
        platformRoleId);
  }

  @Override
  public long createSecurityLevel(long tenantId, int rank, String name) {
    return inTenant(
        tenantId,
        () ->
            dsl.fetchOne(
                    "insert into security_level (tenant_id, rank, name) values (?, ?, ?) returning id",
                    tenantId,
                    rank,
                    name)
                .get(0, Long.class));
  }

  @Override
  public long createDataset(long tenantId, long createdBy, String tableName, Long securityLevelId) {
    // security_level_id 는 null 이면 열 DEFAULT(테넌트 기본 등급)가 채우도록 아예 빼고 넣는다.
    return inTenant(
        tenantId,
        () ->
            securityLevelId == null
                ? dsl.fetchOne(
                        "insert into dataset (name, table_name, created_by, storage_type, origin_type)"
                            + " values (?, ?, ?, 'TABLE', 'SOURCE') returning id",
                        tableName,
                        tableName,
                        createdBy)
                    .get(0, Long.class)
                : dsl.fetchOne(
                        "insert into dataset (name, table_name, created_by, storage_type, origin_type,"
                            + " security_level_id) values (?, ?, ?, 'TABLE', 'SOURCE', ?) returning id",
                        tableName,
                        tableName,
                        createdBy,
                        securityLevelId)
                    .get(0, Long.class));
  }

  @Override
  public long insertAuditLog(
      Long tenantId,
      String username,
      String actionType,
      String resource,
      String resourceId,
      String description,
      LocalDateTime actionTime,
      String metadataJson) {
    // tenant_id 는 DEFAULT(GUC)가 채운다 — tenantId null 이면 GUC 없는 트랜잭션이라 NULL(플랫폼 행)이 된다.
    long id =
        inTenant(
            tenantId,
            () ->
                dsl.fetchOne(
                        "insert into audit_log (username, action_type, resource, resource_id,"
                            + " description, action_time, result, metadata)"
                            + " values (?, ?, ?, ?, ?, ?, 'SUCCESS', cast(? as jsonb)) returning id",
                        username,
                        actionType,
                        resource,
                        resourceId,
                        description,
                        actionTime,
                        metadataJson)
                    .get(0, Long.class));
    auditLogs.add(id);
    return id;
  }

  @Override
  public void trackAuditLog(long id) {
    auditLogs.add(id);
  }

  @Override
  public int countAuditLog(Long tenantId, long id) {
    // 정책이 그 테넌트(null 이면 NULL) 행만 보이게 하므로 같은 컨텍스트로 세면 실제 tenant_id 를 확인하는 셈이다.
    return inTenant(
        tenantId,
        () ->
            Integer.parseInt(
                (tenantId == null
                        ? dsl.fetchValue(
                            "select count(*) from audit_log where id = ? and tenant_id is null", id)
                        : dsl.fetchValue(
                            "select count(*) from audit_log where id = ? and tenant_id = ?",
                            id,
                            tenantId))
                    .toString()));
  }

  @Override
  public void setAsyncJobState(
      long tenantId, String id, String stage, LocalDateTime createdAt, LocalDateTime updatedAt) {
    inTenant(
        tenantId,
        () ->
            dsl.execute(
                "update async_job set stage = ?, created_at = ?, updated_at = ? where id = ?",
                stage,
                createdAt,
                updatedAt,
                id));
  }

  @Override
  public int countAsyncJob(long tenantId, String id) {
    return inTenant(
        tenantId,
        () ->
            Integer.parseInt(
                dsl.fetchValue(
                        "select count(*) from async_job where id = ? and tenant_id = ?",
                        id,
                        tenantId)
                    .toString()));
  }

  @Override
  public int countAsyncJobAnyTenant(String id) {
    // 앱 계정(app_tenant)은 정책이 걸러 테넌트를 모르면 셀 수 없다 — 소유자 커넥션으로 테넌트와 무관하게 센다.
    Integer n =
        ownerJdbc.queryForObject("select count(*) from async_job where id = ?", Integer.class, id);
    return n == null ? 0 : n;
  }

  @Override
  public <T> T inTenant(Long tenantId, Supplier<T> call) {
    return TenantRlsTestSupport.runInTenantTransaction(tx, tenantId, call);
  }

  @Override
  public <T> T noTenant(Supplier<T> call) {
    TenantContext.clear();
    return call.get();
  }

  @Override
  public boolean emptyStringIsNull() {
    return false;
  }

  @Override
  public void cleanup() {
    for (String k : systemSettingKeys) {
      dsl.execute("delete from system_settings where key = ?", k);
    }
    // 테넌트 없는(플랫폼) 감사 행은 GUC 없는 트랜잭션에서만 보인다 — 사용자 FK 를 잡으므로 사용자보다 먼저 지운다.
    for (long a : auditLogs) {
      inTenant(null, () -> dsl.execute("delete from audit_log where id = ?", a));
    }
    for (long t : tenants) {
      // 프로비저닝·리포지토리가 만든 RBAC·시드 행과 설정 오버라이드를 그 테넌트 컨텍스트 안에서 지운다. 감사·작업·데이터셋은
      // 사용자·보안 등급을 참조하므로 먼저 지운다.
      inTenant(
          t,
          () -> {
            dsl.execute("delete from audit_log where tenant_id = ?", t);
            dsl.execute("delete from async_job where tenant_id = ?", t);
            dsl.execute("delete from dataset_access_grant where tenant_id = ?", t);
            dsl.execute("delete from dataset where tenant_id = ?", t);
            dsl.execute("delete from tenant_settings where tenant_id = ?", t);
            TenantRlsTestSupport.deleteRbacCascade(dsl, t);
            return null;
          });
    }
    // 플랫폼 역할을 지우면 역할-권한·사용자-역할 연결이 FK CASCADE 로 함께 사라진다. 사용자 연결은 사용자 단위로도 아래에서 지운다.
    for (long r : platformRoles) {
      dsl.execute("delete from platform_role where id = ?", r);
    }
    for (long p : permissions) {
      dsl.execute("delete from permission where id = ?", p);
    }
    for (long t : tenants) {
      dsl.execute("delete from membership where tenant_id = ?", t);
    }
    for (long u : users) {
      // 기본 테넌트(1) 가입처럼 이 픽스처 밖 테넌트의 멤버십도 사용자 단위로 지운다.
      dsl.execute("delete from membership where user_id = ?", u);
      dsl.execute("delete from platform_user_role where user_id = ?", u);
      dsl.execute("delete from \"user\" where id = ?", u);
    }
    TenantRlsTestSupport.deleteTenants(dsl, tenants.toArray(new Long[0]));
  }
}
