package com.smartfirehub.user.repository;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/**
 * {@link AccessFixtures} 의 Tibero 구현(WD-706) — 정책을 우회하는 소유자 계정 JDBC 로 픽스처를 만들고 지운다. 테넌트는 예약
 * 대역(900,000,000+)의 id 로 만들고, 리포지토리 호출은 {@code TenantContext} 만 세운다(운영 계정 + DBMS_RLS 는 리포지토리 프록시가
 * 쓴다). 사용자 표는 따옴표 대문자 {@code "USER"}.
 */
public class TiberoAccessFixtures implements AccessFixtures {

  private final TiberoPlatformSchema poc;
  private final List<Long> tenants = new ArrayList<>();
  private final List<Long> users = new ArrayList<>();
  private final List<Long> permissions = new ArrayList<>();
  private final List<String> systemSettingKeys = new ArrayList<>();
  private final List<Long> platformRoles = new ArrayList<>();
  private final List<Long> auditLogs = new ArrayList<>();

  public TiberoAccessFixtures(TiberoPlatformSchema poc) {
    this.poc = poc;
  }

  @Override
  public long createTenant() {
    long id = 900_000_000L + ThreadLocalRandom.current().nextLong(1_000_000L);
    poc.jdbc()
        .update(
            "INSERT INTO tenant (id, slug, name, status) VALUES (?, ?, ?, 'ACTIVE')",
            id,
            "acc-" + id,
            "acc-" + id);
    // 역할의 max_security_level_id 트리거가 테넌트 기본 등급을 찾으므로 하나 심는다.
    poc.jdbc()
        .update(
            "INSERT INTO security_level (tenant_id, rank, name, is_default) VALUES (?, 2, 'internal', 1)",
            id);
    tenants.add(id);
    return id;
  }

  @Override
  public long createUser(String username, String email) {
    long id =
        email == null
            ? poc.insertReturningId(
                "INSERT INTO \"USER\" (username, password, name) VALUES (?, 'x', 'n')", username)
            : poc.insertReturningId(
                "INSERT INTO \"USER\" (username, email, password, name) VALUES (?, ?, 'x', 'n')",
                username,
                email);
    users.add(id);
    return id;
  }

  @Override
  public void trackUser(long userId) {
    users.add(userId);
  }

  @Override
  public void setUserActive(long userId, boolean active) {
    poc.jdbc().update("UPDATE \"USER\" SET is_active = ? WHERE id = ?", active ? 1 : 0, userId);
  }

  @Override
  public void createMembership(long userId, long tenantId, String status) {
    poc.jdbc()
        .update(
            "INSERT INTO membership (user_id, tenant_id, role, status) VALUES (?, ?, 'MEMBER', ?)",
            userId,
            tenantId,
            status);
  }

  @Override
  public void setMembershipRole(long userId, long tenantId, String role) {
    poc.jdbc()
        .update(
            "UPDATE membership SET role = ? WHERE user_id = ? AND tenant_id = ?",
            role,
            userId,
            tenantId);
  }

  @Override
  public long createRole(long tenantId, String name, String description) {
    return poc.insertReturningId(
        "INSERT INTO role (tenant_id, name, description) VALUES (?, ?, ?)",
        tenantId,
        name,
        description);
  }

  @Override
  public void grantRole(long tenantId, long userId, long roleId) {
    poc.jdbc()
        .update(
            "INSERT INTO user_role (user_id, role_id, tenant_id) VALUES (?, ?, ?)",
            userId,
            roleId,
            tenantId);
  }

  @Override
  public void grantPermission(long tenantId, long roleId, long permissionId) {
    poc.jdbc()
        .update(
            "INSERT INTO role_permission (role_id, permission_id, tenant_id) VALUES (?, ?, ?)",
            roleId,
            permissionId,
            tenantId);
  }

  @Override
  public long createPermission(String code, String category, String description) {
    long id =
        poc.insertReturningId(
            "INSERT INTO permission (code, description, category) VALUES (?, ?, ?)",
            code,
            description,
            category);
    permissions.add(id);
    return id;
  }

  @Override
  public void clearRoleUpdatedAt(long tenantId, long roleId) {
    poc.jdbc().update("UPDATE role SET updated_at = NULL WHERE id = ?", roleId);
  }

  @Override
  public List<Long> permissionIdsOfRole(long tenantId, long roleId) {
    return poc.jdbc()
        .queryForList(
            "SELECT permission_id FROM role_permission WHERE role_id = ? ORDER BY permission_id",
            Long.class,
            roleId);
  }

  @Override
  public void setTenantStatus(long tenantId, String status) {
    poc.jdbc().update("UPDATE tenant SET status = ? WHERE id = ?", status, tenantId);
  }

  @Override
  public String tenantSlug(long tenantId) {
    return poc.jdbc()
        .queryForObject("SELECT slug FROM tenant WHERE id = ?", String.class, tenantId);
  }

  @Override
  public String tenantName(long tenantId) {
    return poc.jdbc()
        .queryForObject("SELECT name FROM tenant WHERE id = ?", String.class, tenantId);
  }

  @Override
  public void provisionDefaults(long tenantId) {
    // 소유자 계정으로 부른다 — 소유자 세션은 정책이 거르지 않아 우회 표시와 무관하게 같은 결과다.
    poc.jdbc()
        .execute(
            (org.springframework.jdbc.core.ConnectionCallback<Void>)
                c -> {
                  try (var cs = c.prepareCall("{call FH_TENANT.provision_tenant_defaults(?)}")) {
                    cs.setLong(1, tenantId);
                    cs.execute();
                  }
                  return null;
                });
  }

  @Override
  public long roleIdByName(long tenantId, String name) {
    return poc.jdbc()
        .queryForObject(
            "SELECT id FROM role WHERE tenant_id = ? AND name = ?", Long.class, tenantId, name);
  }

  @Override
  public List<String> systemRoleNames(long tenantId) {
    return poc.jdbc()
        .queryForList(
            "SELECT name FROM role WHERE tenant_id = ? AND is_system = 1 ORDER BY name",
            String.class,
            tenantId);
  }

  @Override
  public int countUserRoles(long tenantId, long userId) {
    return poc.jdbc()
        .queryForObject(
            "SELECT COUNT(*) FROM user_role WHERE tenant_id = ? AND user_id = ?",
            Integer.class,
            tenantId,
            userId);
  }

  @Override
  public int countTenantRows(String table, long tenantId) {
    if (!PgAccessFixtures.COUNTABLE_TABLES.contains(table)) {
      throw new IllegalArgumentException(table);
    }
    return poc.jdbc()
        .queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE tenant_id = ?", Integer.class, tenantId);
  }

  @Override
  public void insertSystemSetting(
      String key, String value, String description, LocalDateTime updatedAt) {
    poc.jdbc()
        .update(
            "INSERT INTO system_settings (key, value, description, updated_at) VALUES (?, ?, ?, ?)",
            key,
            value,
            description,
            Timestamp.valueOf(updatedAt));
    systemSettingKeys.add(key);
  }

  @Override
  public Long tenantSettingUpdatedBy(long tenantId, String key) {
    return poc.jdbc()
        .queryForObject(
            "SELECT updated_by FROM tenant_settings WHERE tenant_id = ? AND key = ?",
            Long.class,
            tenantId,
            key);
  }

  @Override
  public void trackTenant(long tenantId) {
    tenants.add(tenantId);
  }

  @Override
  public void setTenantCreatedAt(long tenantId, LocalDateTime createdAt) {
    poc.jdbc()
        .update(
            "UPDATE tenant SET created_at = ? WHERE id = ?",
            Timestamp.valueOf(createdAt),
            tenantId);
  }

  @Override
  public void setUserName(long userId, String name) {
    poc.jdbc().update("UPDATE \"USER\" SET name = ? WHERE id = ?", name, userId);
  }

  @Override
  public void setUserCreatedAt(long userId, LocalDateTime createdAt) {
    poc.jdbc()
        .update(
            "UPDATE \"USER\" SET created_at = ? WHERE id = ?",
            Timestamp.valueOf(createdAt),
            userId);
  }

  @Override
  public long createPlatformRole(String name) {
    long id = poc.insertReturningId("INSERT INTO platform_role (name) VALUES (?)", name);
    platformRoles.add(id);
    return id;
  }

  @Override
  public void grantPlatformPermission(long platformRoleId, long permissionId) {
    poc.jdbc()
        .update(
            "INSERT INTO platform_role_permission (platform_role_id, permission_id) VALUES (?, ?)",
            platformRoleId,
            permissionId);
  }

  @Override
  public void grantPlatformRole(long userId, long platformRoleId) {
    poc.jdbc()
        .update(
            "INSERT INTO platform_user_role (user_id, platform_role_id) VALUES (?, ?)",
            userId,
            platformRoleId);
  }

  @Override
  public long createSecurityLevel(long tenantId, int rank, String name) {
    return poc.insertReturningId(
        "INSERT INTO security_level (tenant_id, rank, name) VALUES (?, ?, ?)",
        tenantId,
        rank,
        name);
  }

  @Override
  public long createDataset(long tenantId, long createdBy, String tableName, Long securityLevelId) {
    // 소유자 세션이라 tenant_id 는 직접 넣는다. security_level_id 가 null 이면 기본 등급 트리거가 채운다.
    if (securityLevelId == null) {
      return poc.insertReturningId(
          "INSERT INTO dataset (tenant_id, name, table_name, created_by, storage_type, origin_type)"
              + " VALUES (?, ?, ?, ?, 'TABLE', 'SOURCE')",
          tenantId,
          tableName,
          tableName,
          createdBy);
    }
    return poc.insertReturningId(
        "INSERT INTO dataset (tenant_id, name, table_name, created_by, storage_type, origin_type,"
            + " security_level_id) VALUES (?, ?, ?, ?, 'TABLE', 'SOURCE', ?)",
        tenantId,
        tableName,
        tableName,
        createdBy,
        securityLevelId);
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
    // 소유자 세션에는 컨텍스트가 없어 DEFAULT 가 NULL 이므로 tenant_id 를 직접 넣는다(null 이면 플랫폼 행).
    // NULL 이 될 수 있는 값은 형을 밝혀 setNull 로 묶는다(형 없는 setObject(null) 에 기대지 않는다).
    Object[] args = {
      tenantId,
      username,
      actionType,
      resource,
      resourceId,
      description,
      Timestamp.valueOf(actionTime),
      metadataJson
    };
    int[] types = {
      Types.BIGINT,
      Types.VARCHAR,
      Types.VARCHAR,
      Types.VARCHAR,
      Types.VARCHAR,
      Types.VARCHAR,
      Types.TIMESTAMP,
      Types.VARCHAR
    };
    GeneratedKeyHolder keys = new GeneratedKeyHolder();
    poc.jdbc()
        .update(
            con -> {
              PreparedStatement ps =
                  con.prepareStatement(
                      "INSERT INTO audit_log (tenant_id, username, action_type, \"RESOURCE\","
                          + " resource_id, description, action_time, result, metadata)"
                          + " VALUES (?, ?, ?, ?, ?, ?, ?, 'SUCCESS', ?)",
                      new String[] {"id"});
              for (int i = 0; i < args.length; i++) {
                if (args[i] == null) ps.setNull(i + 1, types[i]);
                else ps.setObject(i + 1, args[i]);
              }
              return ps;
            },
            keys);
    long id = keys.getKey().longValue();
    auditLogs.add(id);
    return id;
  }

  @Override
  public void trackAuditLog(long id) {
    auditLogs.add(id);
  }

  @Override
  public int countAuditLog(Long tenantId, long id) {
    return tenantId == null
        ? poc.jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE id = ? AND tenant_id IS NULL",
                Integer.class,
                id)
        : poc.jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE id = ? AND tenant_id = ?",
                Integer.class,
                id,
                tenantId);
  }

  @Override
  public void setAsyncJobState(
      long tenantId, String id, String stage, LocalDateTime createdAt, LocalDateTime updatedAt) {
    poc.jdbc()
        .update(
            "UPDATE async_job SET stage = ?, created_at = ?, updated_at = ? WHERE id = ? AND tenant_id = ?",
            stage,
            Timestamp.valueOf(createdAt),
            Timestamp.valueOf(updatedAt),
            id,
            tenantId);
  }

  @Override
  public int countAsyncJob(long tenantId, String id) {
    return poc.jdbc()
        .queryForObject(
            "SELECT COUNT(*) FROM async_job WHERE id = ? AND tenant_id = ?",
            Integer.class,
            id,
            tenantId);
  }

  @Override
  public int countAsyncJobAnyTenant(String id) {
    return poc.jdbc()
        .queryForObject("SELECT COUNT(*) FROM async_job WHERE id = ?", Integer.class, id);
  }

  @Override
  public <T> T inTenant(Long tenantId, Supplier<T> call) {
    // 컨텍스트를 먼저 세운 뒤 트랜잭션을 연다(매니저가 시작 시점에 읽는다). 구현 클래스에 @Transactional 이 없으므로(jOOQ 와 같음 — 호출자 트랜잭션에
    // 참여) 호출자 역할을 하는 이 테스트가 트랜잭션을 연다.
    if (tenantId == null) {
      TenantContext.clear();
      return poc.tx().execute(s -> call.get());
    }
    return TenantContext.runScopedGet(tenantId, () -> poc.tx().execute(s -> call.get()));
  }

  @Override
  public <T> T noTenant(Supplier<T> call) {
    TenantContext.clear();
    return call.get();
  }

  @Override
  public boolean emptyStringIsNull() {
    return true;
  }

  @Override
  public void cleanup() {
    for (String k : systemSettingKeys) {
      poc.jdbc().update("DELETE FROM system_settings WHERE key = ?", k);
    }
    // 테넌트 없는(플랫폼) 감사 행까지 id 로 지운다 — 사용자 FK 를 잡으므로 사용자보다 먼저.
    for (long a : auditLogs) {
      poc.jdbc().update("DELETE FROM audit_log WHERE id = ?", a);
    }
    // 프로비저닝·리포지토리가 만든 행까지 FK 순서로 지운다(PG deleteRbacCascade 와 같은 순서) — 소유자 계정이라 정책이 거르지 않는다.
    // 감사·작업·데이터셋은 사용자·보안 등급을 참조하므로 먼저 지운다.
    for (long t : tenants) {
      poc.jdbc().update("DELETE FROM audit_log WHERE tenant_id = ?", t);
      poc.jdbc().update("DELETE FROM async_job WHERE tenant_id = ?", t);
      poc.jdbc().update("DELETE FROM dataset_access_grant WHERE tenant_id = ?", t);
      poc.jdbc().update("DELETE FROM dataset WHERE tenant_id = ?", t);
      poc.jdbc().update("DELETE FROM tenant_settings WHERE tenant_id = ?", t);
      poc.jdbc().update("DELETE FROM role_permission WHERE tenant_id = ?", t);
      poc.jdbc().update("DELETE FROM user_role WHERE tenant_id = ?", t);
      poc.jdbc().update("DELETE FROM report_template WHERE tenant_id = ?", t);
      poc.jdbc().update("DELETE FROM dataset_category WHERE tenant_id = ?", t);
      poc.jdbc().update("DELETE FROM role WHERE tenant_id = ?", t);
    }
    // 플랫폼 역할을 지우면 역할-권한·사용자-역할 연결이 FK CASCADE 로 함께 사라진다(이 실행이 만든 역할만).
    for (long r : platformRoles) {
      poc.jdbc().update("DELETE FROM platform_role WHERE id = ?", r);
    }
    for (long p : permissions) {
      poc.jdbc().update("DELETE FROM permission WHERE id = ?", p);
    }
    for (long t : tenants) {
      poc.jdbc().update("DELETE FROM membership WHERE tenant_id = ?", t);
    }
    for (long u : users) {
      // 기본 테넌트(1) 가입처럼 이 픽스처 밖 테넌트의 멤버십도 사용자 단위로 지운다.
      poc.jdbc().update("DELETE FROM membership WHERE user_id = ?", u);
      poc.jdbc().update("DELETE FROM platform_user_role WHERE user_id = ?", u);
      poc.jdbc().update("DELETE FROM \"USER\" WHERE id = ?", u);
    }
    for (long t : tenants) {
      poc.jdbc().update("DELETE FROM security_level WHERE tenant_id = ?", t);
      poc.jdbc().update("DELETE FROM tenant WHERE id = ?", t);
    }
  }
}
