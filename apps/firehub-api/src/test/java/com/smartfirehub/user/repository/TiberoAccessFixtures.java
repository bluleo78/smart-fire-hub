package com.smartfirehub.user.repository;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/**
 * {@link AccessFixtures} 의 Tibero 구현(WD-706) — 정책을 우회하는 소유자 계정 JDBC 로 픽스처를 만들고 지운다. 테넌트는 예약
 * 대역(900,000,000+)의 id 로 만들고, 리포지토리 호출은 {@code TenantContext} 만 세운다(운영 계정 + DBMS_RLS 는 리포지토리 프록시가
 * 쓴다). 사용자 표는 따옴표 대문자 {@code "USER"}.
 */
public class TiberoAccessFixtures implements AccessFixtures {

  /** Tibero DB 시계(UTC 벽시계) — PlatformSql.xml 의 {@code dbNow}(tibero) 와 같은 식이다. */
  private static final String DB_NOW = "SYS_EXTRACT_UTC(SYSTIMESTAMP)";

  /** DB 시계 + ? 초(바인드 1개) — PlatformSql.xml 의 {@code dbNowPlusSeconds}(tibero) 와 같은 식이다. */
  private static final String DB_NOW_PLUS_SECONDS = DB_NOW + " + NUMTODSINTERVAL(?, 'SECOND')";

  private final TiberoPlatformSchema poc;
  private final List<Long> tenants = new ArrayList<>();
  private final List<Long> users = new ArrayList<>();
  private final List<Long> permissions = new ArrayList<>();
  private final List<String> systemSettingKeys = new ArrayList<>();
  private final List<Long> platformRoles = new ArrayList<>();
  private final List<Long> auditLogs = new ArrayList<>();

  /** 픽스처 데이터셋의 만든 이(처음 쓸 때 만든다). */
  private Long datasetCreatorId;

  public TiberoAccessFixtures(TiberoPlatformSchema poc) {
    this.poc = poc;
  }

  @Override
  public long createTenant() {
    // 공유 서버의 다른 행과 id 가 겹치면 새 난수로 다시 시도한다(WD-736).
    long id = poc.insertTenantWithRandomId("acc");
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
  public long createDataset(long tenantId, String name) {
    // 소유자 세션이라 tenant_id 는 직접 넣는다. security_level_id 는 빼서 기본 등급 트리거가 채운다. created_by 는 "USER" FK 라
    // 픽스처 전용 사용자를 한 번 만들어 쓰고, table_name 은 (테넌트, table_name) 유일 색인용 무작위 ASCII.
    long creator = datasetCreator();
    return poc.insertReturningId(
        "INSERT INTO dataset (tenant_id, name, table_name, created_by, storage_type, origin_type)"
            + " VALUES (?, ?, ?, ?, 'TABLE', 'SOURCE')",
        tenantId,
        name,
        PgAccessFixtures.randomTableName(),
        creator);
  }

  @Override
  public void grantDatasetAccess(long tenantId, long datasetId, Long roleId, Long userId) {
    // 소유자 세션이라 tenant_id 를 직접 넣는다. null 바인드는 타입을 밝혀야 드라이버가 받는다.
    insertWithBinder(
        // granted_at 은 운영 문장과 같은 DB 시계(UTC 벽시계) — 열 DEFAULT(세션 시간대)에 기대면 리포지토리가 넣은 행과 정렬이 어긋난다.
        "INSERT INTO dataset_access_grant (tenant_id, dataset_id, user_id, role_id, granted_at)"
            + " VALUES (?, ?, ?, ?, "
            + DB_NOW
            + ")",
        ps -> {
          ps.setLong(1, tenantId);
          ps.setLong(2, datasetId);
          if (userId == null) ps.setNull(3, Types.BIGINT);
          else ps.setLong(3, userId);
          if (roleId == null) ps.setNull(4, Types.BIGINT);
          else ps.setLong(4, roleId);
        });
  }

  @Override
  public void setOntologyUpdatedAt(long ontologyId, java.time.Instant at) {
    // Tibero 플랫폼 테이블은 UTC 벽시계 TIMESTAMP 다.
    poc.jdbc()
        .update(
            "UPDATE ontology SET updated_at = ? WHERE id = ?",
            Timestamp.valueOf(LocalDateTime.ofInstant(at, java.time.ZoneOffset.UTC)),
            ontologyId);
  }

  @Override
  public void bindDatasetOntology(long tenantId, long datasetId, long ontologyId) {
    poc.jdbc()
        .update(
            "INSERT INTO dataset_ontology (tenant_id, dataset_id, ontology_id) VALUES (?, ?, ?)",
            tenantId,
            datasetId,
            ontologyId);
  }

  @Override
  public void createDatasetMapping(long tenantId, long datasetId, long ontologyId) {
    poc.jdbc()
        .update(
            "INSERT INTO dataset_mapping (tenant_id, dataset_id, ontology_id, spec)"
                + " VALUES (?, ?, ?, '{}')",
            tenantId,
            datasetId,
            ontologyId);
  }

  @Override
  public List<DatasetOntologyRow> datasetOntologyRows(long tenantId, long datasetId) {
    return poc.jdbc()
        .query(
            "SELECT ontology_id, bound_by, bound_at FROM dataset_ontology"
                + " WHERE tenant_id = ? AND dataset_id = ?",
            (rs, i) ->
                new DatasetOntologyRow(
                    rs.getLong("ontology_id"),
                    rs.getObject("bound_by") == null ? null : rs.getLong("bound_by"),
                    rs.getTimestamp("bound_at")
                        .toLocalDateTime()
                        .toInstant(java.time.ZoneOffset.UTC)),
            tenantId,
            datasetId);
  }

  @Override
  public void setDatasetOntologyBoundAt(long tenantId, long datasetId, java.time.Instant at) {
    poc.jdbc()
        .update(
            "UPDATE dataset_ontology SET bound_at = ? WHERE tenant_id = ? AND dataset_id = ?",
            Timestamp.valueOf(LocalDateTime.ofInstant(at, java.time.ZoneOffset.UTC)),
            tenantId,
            datasetId);
  }

  @Override
  public void setRoleMaxLevel(long roleId, long levelId) {
    poc.jdbc().update("UPDATE role SET max_security_level_id = ? WHERE id = ?", levelId, roleId);
  }

  @Override
  public void setSecurityLevelFlags(long levelId, boolean allowlistRequired, boolean adminBypass) {
    // 불리언 열은 NUMBER(1) — 1/0 으로 쓴다.
    poc.jdbc()
        .update(
            "UPDATE security_level SET allowlist_required = ?, admin_bypass = ? WHERE id = ?",
            allowlistRequired ? 1 : 0,
            adminBypass ? 1 : 0,
            levelId);
  }

  @Override
  public List<LocalDateTime> securityLevelCreatedAndUpdatedAt(long levelId) {
    return poc.jdbc()
        .queryForObject(
            "SELECT created_at, updated_at FROM security_level WHERE id = ?",
            (rs, i) ->
                List.of(rs.getTimestamp(1).toLocalDateTime(), rs.getTimestamp(2).toLocalDateTime()),
            levelId);
  }

  @Override
  public LocalDateTime dbWallClockNow() {
    // 플랫폼 TIMESTAMP 열의 저장 기준(UTC 벽시계) — 운영 dbNow 와 같은 식.
    return poc.jdbc().queryForObject("SELECT " + DB_NOW + " FROM DUAL", LocalDateTime.class);
  }

  @Override
  public long createOntology(long tenantId, String name) {
    return poc.insertReturningId(
        "INSERT INTO ontology (tenant_id, domain) VALUES (?, ?)", tenantId, name);
  }

  @Override
  public long createCategory(long tenantId, String name) {
    return poc.insertReturningId(
        "INSERT INTO dataset_category (tenant_id, name) VALUES (?, ?)", tenantId, name);
  }

  @Override
  public void assignCategory(long datasetId, long categoryId) {
    poc.jdbc().update("UPDATE dataset SET category_id = ? WHERE id = ?", categoryId, datasetId);
  }

  @Override
  public long createColumn(long datasetId, String name, int order) {
    // 호출자는 테넌트를 넘기지 않는다 — 데이터셋 행의 테넌트를 소유자로 읽어 그대로 넣는다(소유자 세션에는 DEFAULT 가 없다).
    long tenantId =
        poc.jdbc()
            .queryForObject("SELECT tenant_id FROM dataset WHERE id = ?", Long.class, datasetId);
    return poc.insertReturningId(
        "INSERT INTO dataset_column (tenant_id, dataset_id, column_name, data_type, column_order)"
            + " VALUES (?, ?, ?, 'TEXT', ?)",
        tenantId,
        datasetId,
        name,
        order);
  }

  @Override
  public void setColumnText(long columnId, String displayName, String description) {
    // null 바인드는 타입을 밝혀야 드라이버가 받는다.
    poc.jdbc()
        .update(
            "UPDATE dataset_column SET display_name = ?, description = ? WHERE id = ?",
            new Object[] {displayName, description, columnId},
            new int[] {java.sql.Types.VARCHAR, java.sql.Types.VARCHAR, java.sql.Types.BIGINT});
  }

  @Override
  public void setDatasetField(long datasetId, String column, Object value) {
    String col = PgAccessFixtures.datasetField(column);
    if (value == null) {
      poc.jdbc().update("UPDATE dataset SET " + col + " = NULL WHERE id = ?", datasetId);
      return;
    }
    // 시각 열(created_at·updated_at)은 Timestamp 로 바꿔 바인딩한다 — 실행 행 픽스처와 같은 방식(저장 TZ 벽시계 그대로).
    Object bound = value instanceof LocalDateTime t ? Timestamp.valueOf(t) : value;
    poc.jdbc().update("UPDATE dataset SET " + col + " = ? WHERE id = ?", bound, datasetId);
  }

  @Override
  public long datasetSecurityLevel(long datasetId) {
    return poc.jdbc()
        .queryForObject(
            "SELECT security_level_id FROM dataset WHERE id = ?", Long.class, datasetId);
  }

  @Override
  public void addTag(long datasetId, String tagName) {
    long tenantId = datasetTenantOf(datasetId);
    poc.jdbc()
        .update(
            "INSERT INTO dataset_tag (tenant_id, dataset_id, tag_name) VALUES (?, ?, ?)",
            tenantId,
            datasetId,
            tagName);
  }

  @Override
  public void addFavorite(long userId, long datasetId) {
    long tenantId = datasetTenantOf(datasetId);
    poc.jdbc()
        .update(
            "INSERT INTO dataset_favorite (tenant_id, user_id, dataset_id) VALUES (?, ?, ?)",
            tenantId,
            userId,
            datasetId);
  }

  @Override
  public long createPipeline(long tenantId, String name, boolean active) {
    return poc.insertReturningId(
        "INSERT INTO pipeline (tenant_id, name, is_active, created_by) VALUES (?, ?, ?, ?)",
        tenantId,
        name,
        active ? 1 : 0,
        datasetCreator());
  }

  @Override
  public long createStep(
      long pipelineId,
      String name,
      String scriptType,
      String scriptContent,
      Long outputDatasetId,
      String loadStrategy) {
    // 소유자 세션에는 tenant_id DEFAULT 가 없어 파이프라인 행의 테넌트를 읽어 넣는다. null 바인드는 타입을 밝혀야 드라이버가 받는다
    // (script_content 는 CLOB — 긴 본문도 문자열 바인드로 넣는다).
    long tenantId = ownerTenantOf("pipeline", pipelineId);
    return insertWithBinder(
        "INSERT INTO pipeline_step (tenant_id, pipeline_id, name, script_type, script_content,"
            + " output_dataset_id, step_order, load_strategy) VALUES (?, ?, ?, ?, ?, ?, 0, ?)",
        ps -> {
          ps.setLong(1, tenantId);
          ps.setLong(2, pipelineId);
          ps.setString(3, name);
          ps.setString(4, scriptType);
          if (scriptContent == null) ps.setNull(5, Types.CLOB);
          else ps.setString(5, scriptContent);
          if (outputDatasetId == null) ps.setNull(6, Types.BIGINT);
          else ps.setLong(6, outputDatasetId);
          ps.setString(7, loadStrategy);
        });
  }

  @Override
  public void addStepInput(long stepId, long datasetId) {
    poc.jdbc()
        .update(
            "INSERT INTO pipeline_step_input (tenant_id, step_id, dataset_id) VALUES (?, ?, ?)",
            ownerTenantOf("pipeline_step", stepId),
            stepId,
            datasetId);
  }

  @Override
  public long createTrigger(long pipelineId, String name, String triggerType, String configJson) {
    return poc.insertReturningId(
        "INSERT INTO pipeline_trigger (tenant_id, pipeline_id, trigger_type, name, config, created_by)"
            + " VALUES (?, ?, ?, ?, ?, ?)",
        ownerTenantOf("pipeline", pipelineId),
        pipelineId,
        triggerType,
        name,
        configJson,
        datasetCreator());
  }

  @Override
  public long createSavedQuery(long tenantId, Long datasetId, String sqlText) {
    long creator = datasetCreator();
    return insertWithBinder(
        "INSERT INTO saved_query (tenant_id, name, sql_text, dataset_id, created_by)"
            + " VALUES (?, 'fx-q', ?, ?, ?)",
        ps -> {
          ps.setLong(1, tenantId);
          ps.setString(2, sqlText);
          if (datasetId == null) ps.setNull(3, Types.BIGINT);
          else ps.setLong(3, datasetId);
          ps.setLong(4, creator);
        });
  }

  @Override
  public long createChart(long savedQueryId) {
    return poc.insertReturningId(
        "INSERT INTO chart (tenant_id, name, saved_query_id, chart_type, created_by)"
            + " VALUES (?, 'fx-c', ?, 'BAR', ?)",
        ownerTenantOf("saved_query", savedQueryId),
        savedQueryId,
        datasetCreator());
  }

  @Override
  public long createDashboard(long tenantId, String name, long createdBy, boolean shared) {
    return poc.insertReturningId(
        "INSERT INTO dashboard (tenant_id, name, is_shared, created_by) VALUES (?, ?, ?, ?)",
        tenantId,
        name,
        shared ? 1 : 0,
        createdBy);
  }

  @Override
  public void addWidget(long dashboardId, long chartId) {
    poc.jdbc()
        .update(
            "INSERT INTO dashboard_widget (tenant_id, dashboard_id, chart_id) VALUES (?, ?, ?)",
            ownerTenantOf("dashboard", dashboardId),
            dashboardId,
            chartId);
  }

  @Override
  public String searchIndexConfigHash(long datasetId) {
    return poc.jdbc()
        .queryForObject(
            "SELECT config_hash FROM dataset_search_index WHERE dataset_id = ?",
            String.class,
            datasetId);
  }

  @Override
  public void setSearchLeaseFromDbNow(long datasetId, int seconds) {
    // 플랫폼 TIMESTAMP 는 UTC 벽시계 — 운영 문장(dbNowPlusSeconds)과 같은 식으로 맞춘다.
    poc.jdbc()
        .update(
            "UPDATE dataset_search_index SET sync_lease_until ="
                + " "
                + DB_NOW_PLUS_SECONDS
                + " WHERE dataset_id = ?",
            seconds,
            datasetId);
  }

  @Override
  public java.time.Duration searchLeaseRemaining(long datasetId) {
    // 둘 다 UTC 벽시계 TIMESTAMP 라 LocalDateTime 으로 읽어 그대로 뺀다(JVM 시간대와 무관).
    return poc.jdbc()
        .queryForObject(
            "SELECT sync_lease_until, "
                + DB_NOW
                + " AS db_now"
                + " FROM dataset_search_index WHERE dataset_id = ?",
            (rs, i) -> {
              LocalDateTime lease = rs.getObject(1, LocalDateTime.class);
              LocalDateTime now = rs.getObject(2, LocalDateTime.class);
              return lease == null ? null : java.time.Duration.between(now, lease);
            },
            datasetId);
  }

  @Override
  public void setSecurityLevelAiPolicy(long levelId, String aiPolicy) {
    poc.jdbc().update("update security_level set ai_policy = ? where id = ?", aiPolicy, levelId);
  }

  @Override
  public void setEmbeddingMarker(long datasetId, Integer dim, String model) {
    poc.jdbc()
        .update(
            "update dataset_embedding set embedded_dim = ?, embedded_model = ?, embedded_hash = "
                + (dim == null ? "NULL" : "source_hash")
                + " where dataset_id = ?",
            dim,
            model,
            datasetId);
  }

  @Override
  public void setEmbeddingKeywordMarker(long datasetId, String marker) {
    poc.jdbc()
        .update(
            "update dataset_embedding set keyword_marker = ? where dataset_id = ?",
            marker,
            datasetId);
  }

  @Override
  public void setChunkKeywordMarker(long chunkId, String marker) {
    poc.jdbc().update("update document_chunk set keyword_marker = ? where id = ?", marker, chunkId);
  }

  @Override
  public void setChunkMarker(long chunkId, Integer dim, String model) {
    poc.jdbc()
        .update(
            "update document_chunk set embedded_dim = ?, embedded_model = ? where id = ?",
            dim,
            model,
            chunkId);
  }

  @Override
  public String embeddingSourceText(long datasetId) {
    return poc
        .jdbc()
        .queryForList(
            "select source_text from dataset_embedding where dataset_id = ?",
            String.class,
            datasetId)
        .stream()
        .findFirst()
        .orElse(null);
  }

  @Override
  public Long embeddingTenantId(long datasetId) {
    return poc
        .jdbc()
        .queryForList(
            "select tenant_id from dataset_embedding where dataset_id = ?", Long.class, datasetId)
        .stream()
        .findFirst()
        .orElse(null);
  }

  @Override
  public void setEmbeddingUpdatedAt(long datasetId, LocalDateTime updatedAt) {
    poc.jdbc()
        .update(
            "update dataset_embedding set updated_at = ? where dataset_id = ?",
            java.sql.Timestamp.valueOf(updatedAt),
            datasetId);
  }

  @Override
  public LocalDateTime embeddingUpdatedAt(long datasetId) {
    return poc.jdbc()
        .queryForObject(
            "select updated_at from dataset_embedding where dataset_id = ?",
            java.sql.Timestamp.class,
            datasetId)
        .toLocalDateTime();
  }

  @Override
  public long createDocumentFile(long tenantId, long datasetId, String name, String status) {
    // 소유자 세션이라 정책이 거르지 않아 tenant_id 를 직접 넣는다. 정리는 데이터셋 삭제의 FK CASCADE 가 맡는다.
    return poc.insertReturningId(
        "INSERT INTO document_file (tenant_id, dataset_id, original_name, mime_type, file_size,"
            + " storage_path, status, uploaded_by) VALUES (?, ?, ?, 'text/plain', 3, ?, ?, ?)",
        tenantId,
        datasetId,
        name,
        "/tmp/" + name,
        status,
        datasetCreator());
  }

  @Override
  public void seedEmbeddingRow(long tenantId, long datasetId, String sourceText) {
    poc.jdbc()
        .update(
            "INSERT INTO dataset_embedding (tenant_id, dataset_id, source_text) VALUES (?, ?, ?)",
            tenantId,
            datasetId,
            sourceText);
  }

  @Override
  public long insertChunk(
      long tenantId, long documentFileId, long datasetId, int index, String content) {
    return poc.insertReturningId(
        "INSERT INTO document_chunk (tenant_id, document_file_id, dataset_id, chunk_index, content)"
            + " VALUES (?, ?, ?, ?, ?)",
        tenantId,
        documentFileId,
        datasetId,
        index,
        content);
  }

  @Override
  public List<ChunkRow> chunkRows(long documentFileId) {
    return poc.jdbc()
        .query(
            "select dataset_id, chunk_index, content, token_count, tenant_id from document_chunk"
                + " where document_file_id = ? order by chunk_index",
            (rs, n) ->
                new ChunkRow(
                    rs.getLong("dataset_id"),
                    rs.getInt("chunk_index"),
                    rs.getString("content"),
                    rs.getObject("token_count") == null ? null : rs.getInt("token_count"),
                    rs.getLong("tenant_id")),
            documentFileId);
  }

  /**
   * PreparedStatement 바인드를 직접 거는 IDENTITY 삽입 — null 바인드에 타입(Types)을 밝혀야 할 때 쓴다(insertReturningId 는
   * setObject).
   */
  private long insertWithBinder(String sql, StatementBinder binder) {
    GeneratedKeyHolder keys = new GeneratedKeyHolder();
    poc.jdbc()
        .update(
            con -> {
              PreparedStatement ps = con.prepareStatement(sql, new String[] {"id"});
              binder.bind(ps);
              return ps;
            },
            keys);
    Number key = keys.getKey();
    if (key == null) throw new IllegalStateException("생성 키 없음: " + sql);
    return key.longValue();
  }

  /** {@link #insertWithBinder} 의 바인드 콜백. */
  @FunctionalInterface
  private interface StatementBinder {
    void bind(PreparedStatement ps) throws java.sql.SQLException;
  }

  /** 부모 행의 테넌트(소유자 시점). table 은 시험 코드의 고정 이름만 넘긴다(pipeline·pipeline_step·saved_query·dashboard). */
  private long ownerTenantOf(String table, long id) {
    return poc.jdbc()
        .queryForObject("SELECT tenant_id FROM " + table + " WHERE id = ?", Long.class, id);
  }

  /** 데이터셋의 테넌트(소유자 시점). */
  private long datasetTenantOf(long datasetId) {
    return poc.jdbc()
        .queryForObject("SELECT tenant_id FROM dataset WHERE id = ?", Long.class, datasetId);
  }

  /** 픽스처 데이터셋의 만든 이 — 처음 부를 때 한 번 만들고 정리 대상(users)에 올린다. */
  @Override
  public long datasetCreator() {
    if (datasetCreatorId == null) {
      datasetCreatorId = createUser("dsfx" + UUID.randomUUID().toString().substring(0, 12), null);
    }
    return datasetCreatorId;
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
  public void setDataStoreUpdatedAt(long tenantId, LocalDateTime updatedAt) {
    poc.jdbc()
        .update(
            "update tenant_data_store set updated_at = ? where tenant_id = ?",
            java.sql.Timestamp.valueOf(updatedAt),
            tenantId);
  }

  @Override
  public LocalDateTime dataStoreUpdatedAt(long tenantId) {
    return poc.jdbc()
        .queryForObject(
            "select updated_at from tenant_data_store where tenant_id = ?",
            java.sql.Timestamp.class,
            tenantId)
        .toLocalDateTime();
  }

  @Override
  public String dataStoreHost(long tenantId) {
    return poc.jdbc()
        .queryForObject(
            "select host from tenant_data_store where tenant_id = ?", String.class, tenantId);
  }

  @Override
  public <T> T withDataStoreRowLocked(long tenantId, Supplier<T> call) {
    return poc.jdbc()
        .execute(
            (java.sql.Connection c) -> {
              c.setAutoCommit(false);
              try (java.sql.PreparedStatement ps =
                  c.prepareStatement(
                      "select tenant_id from tenant_data_store where tenant_id = ? for update")) {
                ps.setLong(1, tenantId);
                ps.executeQuery().close();
                return call.get();
              } finally {
                // 문장 시간 제한으로 취소되면 Tibero 는 커넥션을 닫아 롤백이 실패할 수 있다 — 정리 실패는 무시한다.
                try {
                  c.rollback();
                  c.setAutoCommit(true);
                } catch (java.sql.SQLException closed) {
                  // 이미 닫힌 커넥션
                }
              }
            });
  }

  @Override
  public boolean isDataStoreRowLockedByOther(long tenantId) {
    return poc.jdbc()
        .execute(
            (java.sql.Connection c) -> {
              c.setAutoCommit(false);
              try (java.sql.PreparedStatement ps =
                  c.prepareStatement(
                      "select tenant_id from tenant_data_store where tenant_id = ? for update nowait")) {
                ps.setLong(1, tenantId);
                ps.executeQuery().close();
                return false;
              } catch (java.sql.SQLException timedOut) {
                return true;
              } finally {
                // 문장 시간 제한으로 취소되면 Tibero 는 커넥션을 닫아 롤백이 실패할 수 있다 — 정리 실패는 무시한다.
                try {
                  c.rollback();
                  c.setAutoCommit(true);
                } catch (java.sql.SQLException closed) {
                  // 이미 닫힌 커넥션
                }
              }
            });
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
  public void setAiSessionUpdatedAt(long sessionId, LocalDateTime updatedAt) {
    poc.jdbc()
        .update(
            "UPDATE ai_session SET updated_at = ? WHERE id = ?",
            Timestamp.valueOf(updatedAt),
            sessionId);
  }

  @Override
  public String aiSessionTitle(long sessionId) {
    return poc.jdbc()
        .queryForObject("SELECT title FROM ai_session WHERE id = ?", String.class, sessionId);
  }

  @Override
  public void setReembedLeaseFromDbNow(long tenantId, int seconds) {
    // 플랫폼 TIMESTAMP 는 UTC 벽시계 — 운영 문장(dbNowPlusSeconds)과 같은 식으로 맞춘다.
    poc.jdbc()
        .update(
            "UPDATE embedding_reembed_state SET lease_until ="
                + " "
                + DB_NOW_PLUS_SECONDS
                + " WHERE tenant_id = ?",
            seconds,
            tenantId);
  }

  @Override
  public java.time.Duration reembedLeaseRemaining(long tenantId) {
    // 둘 다 UTC 벽시계 TIMESTAMP 라 LocalDateTime 으로 읽어 그대로 뺀다(JVM 시간대와 무관).
    return poc.jdbc()
        .queryForObject(
            "SELECT lease_until, "
                + DB_NOW
                + " AS db_now"
                + " FROM embedding_reembed_state WHERE tenant_id = ?",
            (rs, i) -> {
              LocalDateTime lease = rs.getObject(1, LocalDateTime.class);
              LocalDateTime now = rs.getObject(2, LocalDateTime.class);
              return lease == null ? null : java.time.Duration.between(now, lease);
            },
            tenantId);
  }

  @Override
  public void setReembedUpdatedAtFromDbNow(long tenantId, int seconds) {
    poc.jdbc()
        .update(
            "UPDATE embedding_reembed_state SET updated_at ="
                + " "
                + DB_NOW_PLUS_SECONDS
                + " WHERE tenant_id = ?",
            seconds,
            tenantId);
  }

  @Override
  public void putTenantSettingFromDbNow(long tenantId, String key, String value, int seconds) {
    // 운영 MyBatis 쓰기(dbNow)와 같은 UTC 벽시계로 명시한다 — 열 DEFAULT LOCALTIMESTAMP 는 서버 지역 시각이라 쓰지 않는다.
    poc.jdbc().update("DELETE FROM tenant_settings WHERE tenant_id = ? AND key = ?", tenantId, key);
    poc.jdbc()
        .update(
            "INSERT INTO tenant_settings (tenant_id, key, value, updated_at)"
                + " VALUES (?, ?, ?, "
                + DB_NOW_PLUS_SECONDS
                + ")",
            tenantId,
            key,
            value,
            seconds);
  }

  @Override
  public java.time.Instant dbNow() {
    // UTC 벽시계를 LocalDateTime 으로 읽어 UTC 순간으로 바꾼다(JVM 시간대와 무관).
    return poc.jdbc()
        .queryForObject("SELECT " + DB_NOW + " FROM DUAL", LocalDateTime.class)
        .toInstant(java.time.ZoneOffset.UTC);
  }

  @Override
  public boolean emptyStringIsNull() {
    return true;
  }

  @Override
  public void deleteDataset(long datasetId) {
    poc.jdbc().update("DELETE FROM dataset WHERE id = ?", datasetId);
  }

  @Override
  public void setOntologySchemaVersion(long ontologyId, int version) {
    poc.jdbc().update("UPDATE ontology SET schema_version = ? WHERE id = ?", version, ontologyId);
  }

  @Override
  public void insertGraphSource(long tenantId, long ontologyId, long datasetId) {
    poc.jdbc()
        .update(
            "INSERT INTO graph_ontology_source (tenant_id, ontology_id, dataset_id) VALUES (?, ?, ?)",
            tenantId,
            ontologyId,
            datasetId);
  }

  @Override
  public int countGraphSources(long tenantId, long ontologyId, long datasetId) {
    return poc.jdbc()
        .queryForObject(
            "SELECT COUNT(*) FROM graph_ontology_source"
                + " WHERE tenant_id = ? AND ontology_id = ? AND dataset_id = ?",
            Integer.class,
            tenantId,
            ontologyId,
            datasetId);
  }

  @Override
  public long insertGraphIngest(
      long tenantId, long datasetId, int schemaVersion, LocalDateTime ingestedAt) {
    return poc.insertReturningId(
        "INSERT INTO dataset_graph_ingest (tenant_id, dataset_id, ingested_at, schema_version_at_ingest)"
            + " VALUES (?, ?, ?, ?)",
        tenantId,
        datasetId,
        Timestamp.valueOf(ingestedAt),
        schemaVersion);
  }

  @Override
  public long insertTriggerEvent(
      long triggerId,
      Long executionId,
      String eventType,
      String detailJson,
      LocalDateTime createdAt) {
    Long pipelineId =
        poc.jdbc()
            .queryForObject(
                "SELECT pipeline_id FROM pipeline_trigger WHERE id = ?", Long.class, triggerId);
    long tenantId = ownerTenantOf("pipeline", pipelineId);
    return insertWithBinder(
        "INSERT INTO trigger_event (tenant_id, trigger_id, pipeline_id, execution_id, event_type,"
            + " detail, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
        ps -> {
          ps.setLong(1, tenantId);
          ps.setLong(2, triggerId);
          ps.setLong(3, pipelineId);
          if (executionId == null) ps.setNull(4, Types.BIGINT);
          else ps.setLong(4, executionId);
          ps.setString(5, eventType);
          if (detailJson == null) ps.setNull(6, Types.VARCHAR);
          else ps.setString(6, detailJson);
          ps.setTimestamp(7, Timestamp.valueOf(createdAt));
        });
  }

  @Override
  public List<LocalDateTime> triggerEventCreatedAts(long triggerId) {
    return poc.jdbc()
        .query(
            "SELECT created_at FROM trigger_event WHERE trigger_id = ? ORDER BY id",
            (rs, i) -> rs.getTimestamp(1).toLocalDateTime(),
            triggerId);
  }

  @Override
  public int countTriggerEvents(long pipelineId) {
    return poc.jdbc()
        .queryForObject(
            "SELECT COUNT(*) FROM trigger_event WHERE pipeline_id = ?", Integer.class, pipelineId);
  }

  @Override
  public void setTriggerEnabled(long triggerId, boolean enabled) {
    poc.jdbc()
        .update(
            "UPDATE pipeline_trigger SET is_enabled = ? WHERE id = ?", enabled ? 1 : 0, triggerId);
  }

  @Override
  public void setTriggerState(long triggerId, String stateJson) {
    poc.jdbc()
        .update("UPDATE pipeline_trigger SET trigger_state = ? WHERE id = ?", stateJson, triggerId);
  }

  @Override
  public void setTriggerUpdatedAt(long triggerId, LocalDateTime updatedAt) {
    poc.jdbc()
        .update(
            "UPDATE pipeline_trigger SET updated_at = ? WHERE id = ?",
            updatedAt == null ? null : Timestamp.valueOf(updatedAt),
            triggerId);
  }

  @Override
  public TriggerStored triggerStored(long triggerId) {
    List<TriggerStored> rows =
        poc.jdbc()
            .query(
                "SELECT config, trigger_state, is_enabled, description, updated_by, created_at,"
                    + " updated_at FROM pipeline_trigger WHERE id = ?",
                (rs, i) ->
                    new TriggerStored(
                        rs.getString("config"),
                        rs.getString("trigger_state"),
                        rs.getInt("is_enabled") == 1,
                        rs.getString("description"),
                        rs.getObject("updated_by") == null ? null : rs.getLong("updated_by"),
                        wall(rs, "created_at"),
                        wall(rs, "updated_at")),
                triggerId);
    return rows.isEmpty() ? null : rows.get(0);
  }

  @Override
  public void setPipelineUpdatedAt(long pipelineId, LocalDateTime updatedAt) {
    poc.jdbc()
        .update(
            "UPDATE pipeline SET updated_at = ? WHERE id = ?",
            updatedAt == null ? null : Timestamp.valueOf(updatedAt),
            pipelineId);
  }

  @Override
  public int countAiInferenceCache(long tenantId, String rowHash, String promptVersion) {
    return poc.jdbc()
        .queryForObject(
            "SELECT COUNT(*) FROM ai_inference_cache WHERE tenant_id = ? AND row_hash = ?"
                + " AND prompt_version = ?",
            Integer.class,
            tenantId,
            rowHash,
            promptVersion);
  }

  @Override
  public LocalDateTime aiInferenceCacheCreatedAt(
      long tenantId, String rowHash, String promptVersion) {
    return poc
        .jdbc()
        .query(
            "SELECT created_at FROM ai_inference_cache WHERE tenant_id = ? AND row_hash = ?"
                + " AND prompt_version = ?",
            (rs, i) -> rs.getTimestamp(1).toLocalDateTime(),
            tenantId,
            rowHash,
            promptVersion)
        .stream()
        .findFirst()
        .orElse(null);
  }

  @Override
  public ApiConnectionStored apiConnectionStored(long id) {
    return poc
        .jdbc()
        .query(
            "SELECT base_url, health_check_path, last_status, last_latency_ms, last_error_message,"
                + " auth_config, created_at, updated_at, last_checked_at FROM api_connection"
                + " WHERE id = ?",
            (rs, i) ->
                new ApiConnectionStored(
                    rs.getString("base_url"),
                    rs.getString("health_check_path"),
                    rs.getString("last_status"),
                    rs.getObject("last_latency_ms") == null ? null : rs.getLong("last_latency_ms"),
                    rs.getString("last_error_message"),
                    rs.getString("auth_config"),
                    toLocal(rs.getTimestamp("created_at")),
                    toLocal(rs.getTimestamp("updated_at")),
                    toLocal(rs.getTimestamp("last_checked_at"))),
            id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private static LocalDateTime toLocal(Timestamp t) {
    return t == null ? null : t.toLocalDateTime();
  }

  @Override
  public void setApiConnectionUpdatedAt(long id, LocalDateTime at) {
    poc.jdbc()
        .update("UPDATE api_connection SET updated_at = ? WHERE id = ?", Timestamp.valueOf(at), id);
  }

  @Override
  public long createApiCallStep(long pipelineId, String name, long apiConnectionId) {
    return poc.insertReturningId(
        "INSERT INTO pipeline_step (tenant_id, pipeline_id, name, script_type, step_order,"
            + " api_connection_id) VALUES (?, ?, ?, 'API_CALL', 0, ?)",
        ownerTenantOf("pipeline", pipelineId),
        pipelineId,
        name,
        apiConnectionId);
  }

  @Override
  public List<DatasetMappingStored> datasetMappingRows(long tenantId, long datasetId) {
    return poc.jdbc()
        .query(
            "SELECT ontology_id, status, updated_by, updated_at FROM dataset_mapping"
                + " WHERE tenant_id = ? AND dataset_id = ?",
            (rs, i) ->
                new DatasetMappingStored(
                    rs.getLong("ontology_id"),
                    rs.getString("status"),
                    rs.getObject("updated_by") == null ? null : rs.getLong("updated_by"),
                    rs.getTimestamp("updated_at")
                        .toLocalDateTime()
                        .toInstant(java.time.ZoneOffset.UTC)),
            tenantId,
            datasetId);
  }

  @Override
  public void setDatasetMappingUpdatedAt(long tenantId, long datasetId, java.time.Instant at) {
    poc.jdbc()
        .update(
            "UPDATE dataset_mapping SET updated_at = ? WHERE tenant_id = ? AND dataset_id = ?",
            Timestamp.valueOf(LocalDateTime.ofInstant(at, java.time.ZoneOffset.UTC)),
            tenantId,
            datasetId);
  }

  @Override
  public LocalDateTime stepCreatedAt(long stepId) {
    return poc.jdbc()
        .queryForObject(
            "SELECT created_at FROM pipeline_step WHERE id = ?",
            (rs, i) -> rs.getTimestamp(1).toLocalDateTime(),
            stepId);
  }

  // ---- 묶음 ③ Task 11(WD-708: 파이프라인 실행) ----

  /** null 허용 시각 바인드 — 드라이버가 타입 없는 NULL 을 거절하지 않게 TIMESTAMP 로 밝힌다. */
  private static void setTimestamp(PreparedStatement ps, int i, LocalDateTime t)
      throws java.sql.SQLException {
    if (t == null) {
      ps.setNull(i, Types.TIMESTAMP);
    } else {
      ps.setTimestamp(i, Timestamp.valueOf(t));
    }
  }

  @Override
  public long createExecution(
      long pipelineId,
      long executedBy,
      String status,
      LocalDateTime heartbeatAt,
      LocalDateTime startedAt) {
    long tenantId = ownerTenantOf("pipeline", pipelineId);
    return insertWithBinder(
        "INSERT INTO pipeline_execution (tenant_id, pipeline_id, status, executed_by, heartbeat_at,"
            + " started_at) VALUES (?, ?, ?, ?, ?, ?)",
        ps -> {
          ps.setLong(1, tenantId);
          ps.setLong(2, pipelineId);
          ps.setString(3, status);
          ps.setLong(4, executedBy);
          setTimestamp(ps, 5, heartbeatAt);
          setTimestamp(ps, 6, startedAt);
        });
  }

  @Override
  public long createStepExecution(
      long executionId,
      Long stepId,
      String stepName,
      String status,
      LocalDateTime startedAt,
      LocalDateTime completedAt) {
    long tenantId = ownerTenantOf("pipeline_execution", executionId);
    return insertWithBinder(
        "INSERT INTO pipeline_step_execution (tenant_id, execution_id, step_id, step_name, status,"
            + " started_at, completed_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
        ps -> {
          ps.setLong(1, tenantId);
          ps.setLong(2, executionId);
          if (stepId == null) ps.setNull(3, Types.BIGINT);
          else ps.setLong(3, stepId);
          if (stepName == null) ps.setNull(4, Types.VARCHAR);
          else ps.setString(4, stepName);
          ps.setString(5, status);
          setTimestamp(ps, 6, startedAt);
          setTimestamp(ps, 7, completedAt);
        });
  }

  @Override
  public void setExecutionField(long executionId, String column, Object value) {
    String col = AccessFixtures.executionField(column);
    if (value == null) {
      poc.jdbc()
          .update("UPDATE pipeline_execution SET " + col + " = NULL WHERE id = ?", executionId);
      return;
    }
    Object bound = value instanceof LocalDateTime t ? Timestamp.valueOf(t) : value;
    poc.jdbc()
        .update("UPDATE pipeline_execution SET " + col + " = ? WHERE id = ?", bound, executionId);
  }

  /** TIMESTAMP 열을 저장된 벽시계 그대로 읽는다(null 허용). */
  private static LocalDateTime wall(java.sql.ResultSet rs, String column)
      throws java.sql.SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toLocalDateTime();
  }

  @Override
  public ExecutionStored executionStored(long executionId) {
    List<ExecutionStored> rows =
        poc.jdbc()
            .query(
                "SELECT pipeline_id, status, executed_by, triggered_by, trigger_id, error_message,"
                    + " started_at, completed_at, created_at, heartbeat_at, cancel_requested_at,"
                    + " cancel_requested_by FROM pipeline_execution WHERE id = ?",
                (rs, i) ->
                    new ExecutionStored(
                        rs.getLong("pipeline_id"),
                        rs.getString("status"),
                        rs.getLong("executed_by"),
                        rs.getString("triggered_by"),
                        rs.getObject("trigger_id") == null ? null : rs.getLong("trigger_id"),
                        rs.getString("error_message"),
                        wall(rs, "started_at"),
                        wall(rs, "completed_at"),
                        wall(rs, "created_at"),
                        wall(rs, "heartbeat_at"),
                        wall(rs, "cancel_requested_at"),
                        rs.getObject("cancel_requested_by") == null
                            ? null
                            : rs.getLong("cancel_requested_by")),
                executionId);
    return rows.isEmpty() ? null : rows.get(0);
  }

  @Override
  public StepExecutionStored stepExecutionStored(long stepExecId) {
    List<StepExecutionStored> rows =
        poc.jdbc()
            .query(
                "SELECT step_id, step_name, status, output_rows, log, error_message, started_at,"
                    + " completed_at, injected_last_run_at FROM pipeline_step_execution"
                    + " WHERE id = ?",
                (rs, i) -> {
                  // injected_last_run_at 은 UTC 벽시계 TIMESTAMP 다(운영 UtcInstantTypeHandler 와 같은 해석).
                  LocalDateTime injected = wall(rs, "injected_last_run_at");
                  return new StepExecutionStored(
                      rs.getObject("step_id") == null ? null : rs.getLong("step_id"),
                      rs.getString("step_name"),
                      rs.getString("status"),
                      rs.getObject("output_rows") == null ? null : rs.getInt("output_rows"),
                      rs.getString("log"),
                      rs.getString("error_message"),
                      wall(rs, "started_at"),
                      wall(rs, "completed_at"),
                      injected == null ? null : injected.toInstant(java.time.ZoneOffset.UTC));
                },
                stepExecId);
    return rows.isEmpty() ? null : rows.get(0);
  }

  @Override
  public void withFailingStepExecutionUpdate(String marker, Runnable body) {
    String cond =
        marker != null ? "NEW.error_message = '" + marker + "'" : "NEW.status = 'SKIPPED'";
    // 소유자 계정(= 실행마다 새 스키마)에 임시 트리거를 건다 — 공유 서버의 다른 스키마에는 닿지 않는다. WHEN 절의 NEW 는 콜론 없이 쓴다.
    poc.jdbc()
        .execute(
            "CREATE OR REPLACE TRIGGER wd230_step_boom_trg BEFORE UPDATE ON pipeline_step_execution"
                + " FOR EACH ROW WHEN ("
                + cond
                + ") BEGIN RAISE_APPLICATION_ERROR(-20001, 'wd230 boom'); END;");
    try {
      body.run();
    } finally {
      poc.jdbc().execute("DROP TRIGGER wd230_step_boom_trg");
    }
  }

  @Override
  public long createSlackWorkspace(long tenantId, String teamId, boolean revoked) {
    return poc.insertReturningId(
        "INSERT INTO slack_workspace (tenant_id, team_id, team_name, bot_user_id, bot_token_enc,"
            + " signing_secret_enc, revoked_at) VALUES (?, ?, 'fx-team', 'UBOT', 'enc-bot',"
            + " 'enc-sign', CASE WHEN ? = 1 THEN SYS_EXTRACT_UTC(SYSTIMESTAMP) ELSE NULL END)",
        tenantId,
        teamId,
        revoked ? 1 : 0);
  }

  @Override
  public long createProactiveJob(
      long tenantId,
      long userId,
      String name,
      boolean enabled,
      String triggerType,
      String configJson) {
    // JSON 열은 문자열 바인드를 그대로 받는다. config 가 null 이면 열 DEFAULT('{}')에 맡긴다.
    if (configJson == null) {
      return poc.insertReturningId(
          "INSERT INTO proactive_job (tenant_id, user_id, name, prompt, cron_expression, enabled,"
              + " trigger_type) VALUES (?, ?, ?, 'fx prompt', '0 0 9 * * *', ?, ?)",
          tenantId,
          userId,
          name,
          enabled ? 1 : 0,
          triggerType);
    }
    return poc.insertReturningId(
        "INSERT INTO proactive_job (tenant_id, user_id, name, prompt, cron_expression, enabled,"
            + " trigger_type, config) VALUES (?, ?, ?, 'fx prompt', '0 0 9 * * *', ?, ?, ?)",
        tenantId,
        userId,
        name,
        enabled ? 1 : 0,
        triggerType,
        configJson);
  }

  @Override
  public long createProactiveJob(long tenantId, long userId, String name) {
    return poc.insertReturningId(
        "INSERT INTO proactive_job (tenant_id, user_id, name, prompt, cron_expression, enabled)"
            + " VALUES (?, ?, ?, 'fx prompt', '0 0 9 * * *', 1)",
        tenantId,
        userId,
        name);
  }

  @Override
  public long createReportTemplate(long tenantId, Long userId, String name) {
    return poc.insertReturningId(
        "INSERT INTO report_template (tenant_id, name, user_id) VALUES (?, ?, ?)",
        tenantId,
        name,
        userId);
  }

  @Override
  public ProactiveJobStored proactiveJobStored(long id) {
    return poc
        .jdbc()
        .query(
            "SELECT name, prompt, template_id, cron_expression, timezone, enabled, trigger_type,"
                + " config, last_executed_at, next_execute_at, created_at, updated_at FROM"
                + " proactive_job WHERE id = ?",
            (rs, i) ->
                new ProactiveJobStored(
                    rs.getString("name"),
                    rs.getString("prompt"),
                    rs.getObject("template_id") == null ? null : rs.getLong("template_id"),
                    rs.getString("cron_expression"),
                    rs.getString("timezone"),
                    rs.getInt("enabled") == 1,
                    rs.getString("trigger_type"),
                    rs.getString("config"),
                    wall(rs, "last_executed_at"),
                    wall(rs, "next_execute_at"),
                    wall(rs, "created_at"),
                    wall(rs, "updated_at")),
            id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  @Override
  public void setProactiveJobUpdatedAt(long id, LocalDateTime updatedAt) {
    poc.jdbc()
        .update(
            "UPDATE proactive_job SET updated_at = ? WHERE id = ?",
            updatedAt == null ? null : Timestamp.valueOf(updatedAt),
            id);
  }

  @Override
  public ReportTemplateStored reportTemplateStored(long id) {
    return poc
        .jdbc()
        .query(
            "SELECT name, description, sections, style, user_id, created_at, updated_at FROM"
                + " report_template WHERE id = ?",
            (rs, i) ->
                new ReportTemplateStored(
                    rs.getString("name"),
                    rs.getString("description"),
                    rs.getString("sections"),
                    rs.getString("style"),
                    rs.getObject("user_id") == null ? null : rs.getLong("user_id"),
                    wall(rs, "created_at"),
                    wall(rs, "updated_at")),
            id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  @Override
  public void setReportTemplateUpdatedAt(long id, LocalDateTime updatedAt) {
    poc.jdbc()
        .update(
            "UPDATE report_template SET updated_at = ? WHERE id = ?",
            updatedAt == null ? null : Timestamp.valueOf(updatedAt),
            id);
  }

  @Override
  public void setReportTemplateSectionsJson(long id, String sectionsJson) {
    poc.jdbc().update("UPDATE report_template SET sections = ? WHERE id = ?", sectionsJson, id);
  }

  @Override
  public long createProactiveJobExecution(long tenantId, long jobId, String status) {
    return poc.insertReturningId(
        "INSERT INTO proactive_job_execution (tenant_id, job_id, status) VALUES (?, ?, ?)",
        tenantId,
        jobId,
        status);
  }

  @Override
  public ProactiveExecutionStored proactiveExecutionStored(long id) {
    return poc
        .jdbc()
        .query(
            "SELECT job_id, status, started_at, completed_at, error_message, result,"
                + " delivered_channels, security_level_id, heartbeat_at, created_at"
                + " FROM proactive_job_execution WHERE id = ?",
            (rs, i) ->
                new ProactiveExecutionStored(
                    rs.getLong("job_id"),
                    rs.getString("status"),
                    wall(rs, "started_at"),
                    wall(rs, "completed_at"),
                    rs.getString("error_message"),
                    rs.getString("result"),
                    rs.getString("delivered_channels"),
                    rs.getObject("security_level_id") == null
                        ? null
                        : rs.getLong("security_level_id"),
                    wall(rs, "heartbeat_at"),
                    wall(rs, "created_at")),
            id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  @Override
  public void setProactiveExecutionField(long id, String column, Object value) {
    String col = AccessFixtures.proactiveExecutionField(column);
    if (value == null) {
      poc.jdbc().update("UPDATE proactive_job_execution SET " + col + " = NULL WHERE id = ?", id);
      return;
    }
    Object bound = value instanceof LocalDateTime t ? Timestamp.valueOf(t) : value;
    poc.jdbc().update("UPDATE proactive_job_execution SET " + col + " = ? WHERE id = ?", bound, id);
  }

  @Override
  public void setProactiveExecutionResultJson(long id, String resultJson) {
    poc.jdbc().update("UPDATE proactive_job_execution SET result = ? WHERE id = ?", resultJson, id);
  }

  @Override
  public OAuthStateStored oauthStateStored(String state) {
    return poc
        .jdbc()
        .query(
            "SELECT user_id, channel_type, tenant_id,"
                + " CASE WHEN consumed_at IS NULL THEN 0 ELSE 1 END AS consumed"
                + " FROM oauth_state WHERE state = ?",
            (rs, i) ->
                new OAuthStateStored(
                    rs.getLong("user_id"),
                    rs.getString("channel_type"),
                    rs.getLong("tenant_id"),
                    rs.getInt("consumed") == 1),
            state)
        .stream()
        .findFirst()
        .orElse(null);
  }

  @Override
  public Boolean userChannelPreferenceEnabled(long tenantId, long userId, String channelType) {
    return poc
        .jdbc()
        .query(
            "SELECT enabled FROM user_channel_preference"
                + " WHERE tenant_id = ? AND user_id = ? AND channel_type = ?",
            (rs, i) -> rs.getInt(1) == 1,
            tenantId,
            userId,
            channelType)
        .stream()
        .findFirst()
        .orElse(null);
  }

  @Override
  public SlackWorkspaceStored slackWorkspaceStored(long id) {
    return poc
        .jdbc()
        .query(
            "SELECT team_id, team_name, bot_user_id, bot_token_enc, signing_secret_enc,"
                + " installed_by_user_id,"
                + " CASE WHEN revoked_at IS NULL THEN 0 ELSE 1 END AS revoked"
                + " FROM slack_workspace WHERE id = ?",
            (rs, i) ->
                new SlackWorkspaceStored(
                    rs.getString("team_id"),
                    rs.getString("team_name"),
                    rs.getString("bot_user_id"),
                    rs.getString("bot_token_enc"),
                    rs.getString("signing_secret_enc"),
                    rs.getObject("installed_by_user_id") == null
                        ? null
                        : rs.getLong("installed_by_user_id"),
                    rs.getInt("revoked") == 1),
            id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  @Override
  public void setSlackSigningSecret(long id, String enc) {
    poc.jdbc().update("UPDATE slack_workspace SET signing_secret_enc = ? WHERE id = ?", enc, id);
  }

  @Override
  public void setSlackPreviousSigningSecret(long id, String enc, java.time.Instant expiresAt) {
    poc.jdbc()
        .update(
            "UPDATE slack_workspace SET previous_signing_secret_enc = ?,"
                + " previous_signing_secret_expires_at = ? WHERE id = ?",
            enc,
            Timestamp.valueOf(LocalDateTime.ofInstant(expiresAt, java.time.ZoneOffset.UTC)),
            id);
  }

  @Override
  public long createSavedQuery(
      long tenantId, long userId, String name, boolean shared, Long datasetId) {
    // 생성·수정 시각은 DB UTC 시계로 명시한다 — 열 DEFAULT(LOCALTIMESTAMP)는 세션 시간대에 기대 리포지토리가 넣은 행과 정렬이 어긋날 수 있다.
    return insertWithBinder(
        "INSERT INTO saved_query (tenant_id, name, sql_text, dataset_id, is_shared, created_by,"
            + " created_at, updated_at) VALUES (?, ?, 'SELECT 1', ?, ?, ?,"
            + " SYS_EXTRACT_UTC(SYSTIMESTAMP), SYS_EXTRACT_UTC(SYSTIMESTAMP))",
        ps -> {
          ps.setLong(1, tenantId);
          ps.setString(2, name);
          if (datasetId == null) ps.setNull(3, Types.BIGINT);
          else ps.setLong(3, datasetId);
          ps.setInt(4, shared ? 1 : 0);
          ps.setLong(5, userId);
        });
  }

  @Override
  public long createChart(long tenantId, long userId, long savedQueryId, boolean shared) {
    return poc.insertReturningId(
        "INSERT INTO chart (tenant_id, name, saved_query_id, chart_type, is_shared, created_by,"
            + " created_at, updated_at) VALUES (?, 'fx-c', ?, 'BAR', ?, ?,"
            + " SYS_EXTRACT_UTC(SYSTIMESTAMP), SYS_EXTRACT_UTC(SYSTIMESTAMP))",
        tenantId,
        savedQueryId,
        shared ? 1 : 0,
        userId);
  }

  @Override
  public long createDashboard(long tenantId, long userId, boolean shared) {
    return poc.insertReturningId(
        "INSERT INTO dashboard (tenant_id, name, is_shared, created_by, created_at, updated_at)"
            + " VALUES (?, 'fx-d', ?, ?, SYS_EXTRACT_UTC(SYSTIMESTAMP),"
            + " SYS_EXTRACT_UTC(SYSTIMESTAMP))",
        tenantId,
        shared ? 1 : 0,
        userId);
  }

  @Override
  public long addWidget(long tenantId, long dashboardId, long chartId) {
    return poc.insertReturningId(
        "INSERT INTO dashboard_widget (tenant_id, dashboard_id, chart_id, created_at, updated_at)"
            + " VALUES (?, ?, ?, SYS_EXTRACT_UTC(SYSTIMESTAMP), SYS_EXTRACT_UTC(SYSTIMESTAMP))",
        tenantId,
        dashboardId,
        chartId);
  }

  @Override
  public void setSavedQueryUpdatedAt(long id, LocalDateTime updatedAt) {
    poc.jdbc()
        .update(
            "UPDATE saved_query SET updated_at = ? WHERE id = ?",
            updatedAt == null ? null : Timestamp.valueOf(updatedAt),
            id);
  }

  @Override
  public Long savedQueryUpdatedBy(long id) {
    List<Long> rows =
        poc.jdbc()
            .query(
                "SELECT updated_by FROM saved_query WHERE id = ?",
                (rs, i) -> {
                  long v = rs.getLong(1);
                  return rs.wasNull() ? null : v;
                },
                id);
    return rows.isEmpty() ? null : rows.get(0);
  }

  @Override
  public void ageAnalyticsQueryRun(UUID id, int minutesAgo) {
    // DB UTC 시계로 옮긴다 — 리포지토리의 보존 기간 비교도 같은 시계다. id 는 VARCHAR2(36) 문자열.
    poc.jdbc()
        .update(
            "UPDATE analytics_query_run SET created_at = SYS_EXTRACT_UTC(SYSTIMESTAMP)"
                + " - NUMTODSINTERVAL(?, 'MINUTE') WHERE id = ?",
            minutesAgo,
            id.toString());
  }

  @Override
  public boolean analyticsQueryRunExists(UUID id) {
    Integer n =
        poc.jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM analytics_query_run WHERE id = ?",
                Integer.class,
                id.toString());
    return n != null && n > 0;
  }

  @Override
  public void setChartUpdatedAt(long chartId, LocalDateTime updatedAt) {
    poc.jdbc()
        .update(
            "UPDATE chart SET updated_at = ? WHERE id = ?",
            updatedAt == null ? null : Timestamp.valueOf(updatedAt),
            chartId);
  }

  @Override
  public Long chartUpdatedBy(long chartId) {
    List<Long> rows =
        poc.jdbc()
            .query(
                "SELECT updated_by FROM chart WHERE id = ?",
                (rs, i) -> {
                  long v = rs.getLong(1);
                  return rs.wasNull() ? null : v;
                },
                chartId);
    return rows.isEmpty() ? null : rows.get(0);
  }

  @Override
  public boolean widgetExists(long widgetId) {
    Integer n =
        poc.jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM dashboard_widget WHERE id = ?", Integer.class, widgetId);
    return n != null && n > 0;
  }

  @Override
  public void setWidgetUpdatedAt(long widgetId, LocalDateTime updatedAt) {
    poc.jdbc()
        .update(
            "UPDATE dashboard_widget SET updated_at = ? WHERE id = ?",
            updatedAt == null ? null : Timestamp.valueOf(updatedAt),
            widgetId);
  }

  @Override
  public LocalDateTime widgetUpdatedAt(long widgetId) {
    return poc.jdbc()
        .queryForObject(
            "SELECT updated_at FROM dashboard_widget WHERE id = ?",
            java.sql.Timestamp.class,
            widgetId)
        .toLocalDateTime();
  }

  @Override
  public void setDashboardUpdatedAt(long dashboardId, LocalDateTime updatedAt) {
    poc.jdbc()
        .update(
            "UPDATE dashboard SET updated_at = ? WHERE id = ?",
            updatedAt == null ? null : Timestamp.valueOf(updatedAt),
            dashboardId);
  }

  @Override
  public Long dashboardUpdatedBy(long dashboardId) {
    List<Long> rows =
        poc.jdbc()
            .query(
                "SELECT updated_by FROM dashboard WHERE id = ?",
                (rs, i) -> {
                  long v = rs.getLong(1);
                  return rs.wasNull() ? null : v;
                },
                dashboardId);
    return rows.isEmpty() ? null : rows.get(0);
  }

  @Override
  public void setAuditLogResult(long id, String result) {
    poc.jdbc().update("UPDATE audit_log SET result = ? WHERE id = ?", result, id);
  }

  @Override
  public void cleanup() {
    for (long t : tenants) {
      // 적재 이력은 테넌트 FK 만 있어(온톨로지 CASCADE 밖) 테넌트 정리 전에 지운다.
      poc.jdbc().update("DELETE FROM dataset_graph_ingest WHERE tenant_id = ?", t);
    }
    cleanupRows();
  }

  private void cleanupRows() {
    for (String k : systemSettingKeys) {
      poc.jdbc().update("DELETE FROM system_settings WHERE key = ?", k);
    }
    // 테넌트 없는(플랫폼) 감사 행까지 id 로 지운다 — 사용자 FK 를 잡으므로 사용자보다 먼저.
    for (long a : auditLogs) {
      poc.jdbc().update("DELETE FROM audit_log WHERE id = ?", a);
    }
    // 프로비저닝·리포지토리가 만든 행까지 FK 순서로 지운다(PG deleteRbacCascade 와 같은 순서) — 소유자 계정이라 정책이 거르지 않는다.
    // 감사·작업·데이터셋은 사용자·보안 등급을 참조하므로 먼저 지운다.
    // 바인딩은 다른 테넌트의 슬랙 워크스페이스를 참조할 수 있어(WD-709) 모든 테넌트의 바인딩을 워크스페이스보다 먼저 지운다.
    for (long t : tenants) {
      poc.jdbc().update("DELETE FROM user_channel_binding WHERE tenant_id = ?", t);
    }
    for (long t : tenants) {
      poc.jdbc().update("DELETE FROM audit_log WHERE tenant_id = ?", t);
      poc.jdbc().update("DELETE FROM async_job WHERE tenant_id = ?", t);
      // ai_session 은 사용자 FK 가 CASCADE 가 아니라 사용자보다 먼저 지운다(WD-712).
      poc.jdbc().update("DELETE FROM ai_session WHERE tenant_id = ?", t);
      poc.jdbc().update("DELETE FROM dataset_access_grant WHERE tenant_id = ?", t);
      poc.jdbc().update("DELETE FROM dataset_mapping WHERE tenant_id = ?", t);
      // 온톨로지 하위(엔티티 타입·속성·관계·데이터셋 연결)는 FK CASCADE 로 함께 사라진다.
      poc.jdbc().update("DELETE FROM ontology WHERE tenant_id = ?", t);
      // 대시보드·저장 쿼리·파이프라인은 사용자(created_by)를 참조하고, 스텝의 output_dataset_id 는 ON DELETE 없는 데이터셋 FK
      // 라 데이터셋보다 먼저 지운다. 위젯·차트·스텝·스텝 입력·트리거는 FK CASCADE 로 함께 사라진다.
      // uploaded_files 는 사용자 FK 가 CASCADE 가 아니라 사용자보다 먼저 지운다(WD-712).
      poc.jdbc().update("DELETE FROM uploaded_files WHERE tenant_id = ?", t);
      poc.jdbc().update("DELETE FROM ai_inference_cache WHERE tenant_id = ?", t);
      // 쿼리 실행 기록은 테넌트 FK 가 CASCADE 가 아니라 테넌트보다 먼저, 위젯·차트는 대시보드·저장 쿼리보다 먼저(자식 먼저) 지운다(WD-710).
      poc.jdbc().update("DELETE FROM analytics_query_run WHERE tenant_id = ?", t);
      poc.jdbc().update("DELETE FROM dashboard_widget WHERE tenant_id = ?", t);
      poc.jdbc().update("DELETE FROM chart WHERE tenant_id = ?", t);
      poc.jdbc().update("DELETE FROM dashboard WHERE tenant_id = ?", t);
      poc.jdbc().update("DELETE FROM saved_query WHERE tenant_id = ?", t);
      poc.jdbc().update("DELETE FROM pipeline WHERE tenant_id = ?", t);
      // API 연결은 파이프라인 스텝이 참조하므로 파이프라인 뒤, 사용자(created_by) 앞에서 지운다(WD-708).
      poc.jdbc().update("DELETE FROM api_connection WHERE tenant_id = ?", t);
      poc.jdbc().update("DELETE FROM dataset WHERE tenant_id = ?", t);
      poc.jdbc().update("DELETE FROM tenant_settings WHERE tenant_id = ?", t);
      poc.jdbc().update("DELETE FROM embedding_reembed_state WHERE tenant_id = ?", t);
      // 선제 작업(실행 이력 CASCADE)은 사용자·템플릿을 참조하고, 슬랙 워크스페이스는 테넌트·사용자를 참조한다(WD-709·710).
      poc.jdbc().update("DELETE FROM proactive_job WHERE tenant_id = ?", t);
      // 채널 선호·oauth_state 는 사용자 FK 가 CASCADE 가 아니라 사용자보다 먼저 지운다(WD-709).
      poc.jdbc().update("DELETE FROM user_channel_preference WHERE tenant_id = ?", t);
      poc.jdbc().update("DELETE FROM oauth_state WHERE tenant_id = ?", t);
      // 바인딩의 workspace_id FK 는 CASCADE 가 아니라 워크스페이스보다 먼저 지운다(WD-709).
      poc.jdbc().update("DELETE FROM user_channel_binding WHERE tenant_id = ?", t);
      poc.jdbc().update("DELETE FROM slack_workspace WHERE tenant_id = ?", t);
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
