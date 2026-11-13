package com.smartfirehub.user.repository;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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

  /** 픽스처 데이터셋의 만든 이(처음 쓸 때 만든다). */
  private Long datasetCreatorId;

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
          "tenant_settings",
          "uploaded_files",
          "document_file",
          "file_dataset_config",
          "ai_session");

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
  public long createDataset(long tenantId, String name) {
    // created_by 는 "USER" FK(NOT NULL)라 픽스처 전용 사용자를 한 번 만들어 쓴다. table_name 은 유일 색인용 무작위 ASCII,
    // security_level_id 는 빼서 열 DEFAULT(테넌트 기본 등급)가 채운다.
    long creator = datasetCreator();
    return inTenant(
        tenantId,
        () ->
            dsl.fetchOne(
                    "insert into dataset (name, table_name, created_by, storage_type, origin_type)"
                        + " values (?, ?, ?, 'TABLE', 'SOURCE') returning id",
                    name,
                    randomTableName(),
                    creator)
                .get(0, Long.class));
  }

  @Override
  public void grantDatasetAccess(long tenantId, long datasetId, Long roleId, Long userId) {
    // tenant_id 는 DEFAULT(GUC)가 채운다. 사용자 XOR 역할은 열 CHECK 가 막는다.
    inTenant(
        tenantId,
        () ->
            dsl.execute(
                "insert into dataset_access_grant (dataset_id, user_id, role_id) values (?, ?, ?)",
                datasetId,
                userId,
                roleId));
  }

  @Override
  public void setOntologyUpdatedAt(long ontologyId, java.time.Instant at) {
    ownerJdbc.update(
        "update ontology set updated_at = ? where id = ?",
        java.time.OffsetDateTime.ofInstant(at, java.time.ZoneOffset.UTC),
        ontologyId);
  }

  @Override
  public void bindDatasetOntology(long tenantId, long datasetId, long ontologyId) {
    // 소유자 커넥션에는 GUC 가 없어 tenant_id DEFAULT 가 비므로 명시로 넣는다.
    ownerJdbc.update(
        "insert into dataset_ontology (tenant_id, dataset_id, ontology_id) values (?, ?, ?)",
        tenantId,
        datasetId,
        ontologyId);
  }

  @Override
  public void createDatasetMapping(long tenantId, long datasetId, long ontologyId) {
    ownerJdbc.update(
        "insert into dataset_mapping (tenant_id, dataset_id, ontology_id, spec)"
            + " values (?, ?, ?, '{}'::jsonb)",
        tenantId,
        datasetId,
        ontologyId);
  }

  @Override
  public List<DatasetOntologyRow> datasetOntologyRows(long tenantId, long datasetId) {
    return ownerJdbc.query(
        "select ontology_id, bound_by, bound_at from dataset_ontology"
            + " where tenant_id = ? and dataset_id = ?",
        (rs, i) ->
            new DatasetOntologyRow(
                rs.getLong("ontology_id"),
                rs.getObject("bound_by", Long.class),
                rs.getObject("bound_at", java.time.OffsetDateTime.class).toInstant()),
        tenantId,
        datasetId);
  }

  @Override
  public void setDatasetOntologyBoundAt(long tenantId, long datasetId, java.time.Instant at) {
    ownerJdbc.update(
        "update dataset_ontology set bound_at = ? where tenant_id = ? and dataset_id = ?",
        java.time.OffsetDateTime.ofInstant(at, java.time.ZoneOffset.UTC),
        tenantId,
        datasetId);
  }

  @Override
  public void setRoleMaxLevel(long roleId, long levelId) {
    ownerJdbc.update("update role set max_security_level_id = ? where id = ?", levelId, roleId);
  }

  @Override
  public void setSecurityLevelFlags(long levelId, boolean allowlistRequired, boolean adminBypass) {
    ownerJdbc.update(
        "update security_level set allowlist_required = ?, admin_bypass = ? where id = ?",
        allowlistRequired,
        adminBypass,
        levelId);
  }

  @Override
  public List<LocalDateTime> securityLevelCreatedAndUpdatedAt(long levelId) {
    return ownerJdbc.queryForObject(
        "select created_at, updated_at from security_level where id = ?",
        (rs, i) ->
            List.of(rs.getObject(1, LocalDateTime.class), rs.getObject(2, LocalDateTime.class)),
        levelId);
  }

  @Override
  public LocalDateTime dbWallClockNow() {
    return ownerJdbc.queryForObject("select localtimestamp", LocalDateTime.class);
  }

  @Override
  public long createOntology(long tenantId, String name) {
    // tenant_id·schema_version·status·updated_at 은 열 DEFAULT 가 채운다.
    return inTenant(
        tenantId,
        () ->
            dsl.fetchOne("insert into ontology (domain) values (?) returning id", name)
                .get(0, Long.class));
  }

  @Override
  public long createCategory(long tenantId, String name) {
    // tenant_id 는 DEFAULT(GUC)가 채운다.
    return inTenant(
        tenantId,
        () ->
            dsl.fetchOne("insert into dataset_category (name) values (?) returning id", name)
                .get(0, Long.class));
  }

  @Override
  public void assignCategory(long datasetId, long categoryId) {
    ownerJdbc.update("update dataset set category_id = ? where id = ?", categoryId, datasetId);
  }

  @Override
  public long createColumn(long datasetId, String name, int order) {
    long tenantId = datasetTenant(datasetId);
    return inTenant(
        tenantId,
        () ->
            dsl.fetchOne(
                    "insert into dataset_column (dataset_id, column_name, data_type, column_order)"
                        + " values (?, ?, 'TEXT', ?) returning id",
                    datasetId,
                    name,
                    order)
                .get(0, Long.class));
  }

  @Override
  public void setColumnText(long columnId, String displayName, String description) {
    ownerJdbc.update(
        "update dataset_column set display_name = ?, description = ? where id = ?",
        displayName,
        description,
        columnId);
  }

  /** setDatasetField 가 받는 열 이름 — 시험 코드 고정 이름만 SQL 에 이어 붙인다(Tibero 픽스처도 같은 목록을 쓴다). */
  static final Set<String> DATASET_FIELDS =
      Set.of(
          "description",
          "storage_type",
          "origin_type",
          "status",
          "status_note",
          "security_level_id",
          "source_pipeline_step_id",
          "created_at",
          "updated_at");

  /** 허용 목록에 없는 열 이름이면 예외. */
  static String datasetField(String column) {
    if (!DATASET_FIELDS.contains(column)) {
      throw new IllegalArgumentException("허용하지 않는 데이터셋 열: " + column);
    }
    return column;
  }

  @Override
  public void setDatasetField(long datasetId, String column, Object value) {
    String col = datasetField(column);
    if (value == null) {
      // NULL 은 바인드 대신 리터럴로 — 드라이버가 타입 없는 NULL 바인드를 거절하지 않게(Tibero 픽스처와 같은 방식).
      ownerJdbc.update("update dataset set " + col + " = null where id = ?", datasetId);
      return;
    }
    ownerJdbc.update("update dataset set " + col + " = ? where id = ?", value, datasetId);
  }

  @Override
  public long datasetSecurityLevel(long datasetId) {
    return ownerJdbc.queryForObject(
        "select security_level_id from dataset where id = ?", Long.class, datasetId);
  }

  @Override
  public void addTag(long datasetId, String tagName) {
    inTenant(
        datasetTenant(datasetId),
        () ->
            dsl.execute(
                "insert into dataset_tag (dataset_id, tag_name) values (?, ?)",
                datasetId,
                tagName));
  }

  @Override
  public void addFavorite(long userId, long datasetId) {
    inTenant(
        datasetTenant(datasetId),
        () ->
            dsl.execute(
                "insert into dataset_favorite (user_id, dataset_id) values (?, ?)",
                userId,
                datasetId));
  }

  @Override
  public long createPipeline(long tenantId, String name, boolean active) {
    long creator = datasetCreator();
    return inTenant(
        tenantId,
        () ->
            dsl.fetchOne(
                    "insert into pipeline (name, is_active, created_by) values (?, ?, ?) returning id",
                    name,
                    active,
                    creator)
                .get(0, Long.class));
  }

  @Override
  public long createStep(
      long pipelineId,
      String name,
      String scriptType,
      String scriptContent,
      Long outputDatasetId,
      String loadStrategy) {
    return inTenant(
        ownerTenantOf("pipeline", pipelineId),
        () ->
            dsl.fetchOne(
                    "insert into pipeline_step (pipeline_id, name, script_type, script_content,"
                        + " output_dataset_id, step_order, load_strategy) values (?, ?, ?, ?, ?, 0, ?)"
                        + " returning id",
                    pipelineId,
                    name,
                    scriptType,
                    scriptContent,
                    outputDatasetId,
                    loadStrategy)
                .get(0, Long.class));
  }

  @Override
  public void addStepInput(long stepId, long datasetId) {
    inTenant(
        ownerTenantOf("pipeline_step", stepId),
        () ->
            dsl.execute(
                "insert into pipeline_step_input (step_id, dataset_id) values (?, ?)",
                stepId,
                datasetId));
  }

  @Override
  public long createTrigger(long pipelineId, String name, String triggerType, String configJson) {
    long creator = datasetCreator();
    return inTenant(
        ownerTenantOf("pipeline", pipelineId),
        () ->
            dsl.fetchOne(
                    "insert into pipeline_trigger (pipeline_id, trigger_type, name, config, created_by)"
                        + " values (?, ?, ?, ?::jsonb, ?) returning id",
                    pipelineId,
                    triggerType,
                    name,
                    configJson,
                    creator)
                .get(0, Long.class));
  }

  @Override
  public long createSavedQuery(long tenantId, Long datasetId, String sqlText) {
    long creator = datasetCreator();
    return inTenant(
        tenantId,
        () ->
            dsl.fetchOne(
                    "insert into saved_query (name, sql_text, dataset_id, created_by)"
                        + " values ('fx-q', ?, ?, ?) returning id",
                    sqlText,
                    datasetId,
                    creator)
                .get(0, Long.class));
  }

  @Override
  public long createChart(long savedQueryId) {
    long creator = datasetCreator();
    return inTenant(
        ownerTenantOf("saved_query", savedQueryId),
        () ->
            dsl.fetchOne(
                    "insert into chart (name, saved_query_id, chart_type, created_by)"
                        + " values ('fx-c', ?, 'BAR', ?) returning id",
                    savedQueryId,
                    creator)
                .get(0, Long.class));
  }

  @Override
  public long createDashboard(long tenantId, String name, long createdBy, boolean shared) {
    return inTenant(
        tenantId,
        () ->
            dsl.fetchOne(
                    "insert into dashboard (name, is_shared, created_by) values (?, ?, ?) returning id",
                    name,
                    shared,
                    createdBy)
                .get(0, Long.class));
  }

  @Override
  public void addWidget(long dashboardId, long chartId) {
    inTenant(
        ownerTenantOf("dashboard", dashboardId),
        () ->
            dsl.execute(
                "insert into dashboard_widget (dashboard_id, chart_id) values (?, ?)",
                dashboardId,
                chartId));
  }

  @Override
  public String searchIndexConfigHash(long datasetId) {
    return ownerJdbc.queryForObject(
        "select config_hash from dataset_search_index where dataset_id = ?",
        String.class,
        datasetId);
  }

  @Override
  public void setSearchLeaseFromDbNow(long datasetId, int seconds) {
    ownerJdbc.update(
        "update dataset_search_index set sync_lease_until = now() + (? * interval '1 second')"
            + " where dataset_id = ?",
        seconds,
        datasetId);
  }

  @Override
  public java.time.Duration searchLeaseRemaining(long datasetId) {
    // 임대 만료 시각과 DB 시계를 같은 문장에서 읽는다 — 두 값 모두 timestamptz 라 순간(Instant)으로 뺀다.
    return ownerJdbc.queryForObject(
        "select sync_lease_until, now() as db_now from dataset_search_index where dataset_id = ?",
        (rs, i) -> {
          java.time.OffsetDateTime lease = rs.getObject(1, java.time.OffsetDateTime.class);
          java.time.OffsetDateTime now = rs.getObject(2, java.time.OffsetDateTime.class);
          return lease == null ? null : java.time.Duration.between(now, lease);
        },
        datasetId);
  }

  @Override
  public void setSecurityLevelAiPolicy(long levelId, String aiPolicy) {
    ownerJdbc.update("update security_level set ai_policy = ? where id = ?", aiPolicy, levelId);
  }

  @Override
  public String embeddingSourceText(long datasetId) {
    return ownerJdbc
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
    return ownerJdbc
        .queryForList(
            "select tenant_id from dataset_embedding where dataset_id = ?", Long.class, datasetId)
        .stream()
        .findFirst()
        .orElse(null);
  }

  @Override
  public void setEmbeddingUpdatedAt(long datasetId, LocalDateTime updatedAt) {
    ownerJdbc.update(
        "update dataset_embedding set updated_at = ? where dataset_id = ?",
        java.sql.Timestamp.valueOf(updatedAt),
        datasetId);
  }

  @Override
  public LocalDateTime embeddingUpdatedAt(long datasetId) {
    return ownerJdbc
        .queryForObject(
            "select updated_at from dataset_embedding where dataset_id = ?",
            java.sql.Timestamp.class,
            datasetId)
        .toLocalDateTime();
  }

  @Override
  public long createDocumentFile(long tenantId, long datasetId, String name, String status) {
    // 소유자 세션이라 정책이 거르지 않아 tenant_id 를 직접 넣는다. 정리는 데이터셋 삭제의 FK CASCADE 가 맡는다.
    return ownerJdbc.queryForObject(
        "insert into document_file (tenant_id, dataset_id, original_name, mime_type, file_size,"
            + " storage_path, status, uploaded_by) values (?, ?, ?, 'text/plain', 3, ?, ?, ?)"
            + " returning id",
        Long.class,
        tenantId,
        datasetId,
        name,
        "/tmp/" + name,
        status,
        datasetCreator());
  }

  @Override
  public List<ChunkRow> chunkRows(long documentFileId) {
    return ownerJdbc.query(
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
   * 부모 행의 테넌트 — 정책을 우회하는 소유자 커넥션으로 읽는다(자식 행 픽스처가 테넌트를 받지 않는다). table 은 시험 코드의 고정 이름만
   * 넘긴다(pipeline·pipeline_step·saved_query·dashboard).
   */
  private long ownerTenantOf(String table, long id) {
    Long t =
        ownerJdbc.queryForObject(
            "select tenant_id from " + table + " where id = ?", Long.class, id);
    if (t == null) throw new IllegalStateException(table + " " + id + " 없음");
    return t;
  }

  /** 데이터셋의 테넌트 — 정책을 우회하는 소유자 커넥션으로 읽는다(호출자는 테넌트를 넘기지 않는다). */
  private long datasetTenant(long datasetId) {
    Long t =
        ownerJdbc.queryForObject(
            "select tenant_id from dataset where id = ?", Long.class, datasetId);
    if (t == null) throw new IllegalStateException("데이터셋 " + datasetId + " 없음");
    return t;
  }

  /** 픽스처 데이터셋의 만든 이 — 처음 부를 때 한 번 만들고 정리 대상(users)에 올린다. */
  @Override
  public long datasetCreator() {
    if (datasetCreatorId == null) {
      datasetCreatorId = createUser("dsfx" + UUID.randomUUID().toString().substring(0, 12), null);
    }
    return datasetCreatorId;
  }

  /** (테넌트, table_name) 유일 색인과 겹치지 않는 무작위 테이블 이름(실제 데이터 테이블은 만들지 않는다). */
  static String randomTableName() {
    return "t_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
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
  public void setDataStoreUpdatedAt(long tenantId, LocalDateTime updatedAt) {
    ownerJdbc.update(
        "update tenant_data_store set updated_at = ? where tenant_id = ?",
        java.sql.Timestamp.valueOf(updatedAt),
        tenantId);
  }

  @Override
  public LocalDateTime dataStoreUpdatedAt(long tenantId) {
    return ownerJdbc
        .queryForObject(
            "select updated_at from tenant_data_store where tenant_id = ?",
            java.sql.Timestamp.class,
            tenantId)
        .toLocalDateTime();
  }

  @Override
  public String dataStoreHost(long tenantId) {
    return ownerJdbc.queryForObject(
        "select host from tenant_data_store where tenant_id = ?", String.class, tenantId);
  }

  @Override
  public <T> T withDataStoreRowLocked(long tenantId, Supplier<T> call) {
    return ownerJdbc.execute(
        (java.sql.Connection c) -> {
          c.setAutoCommit(false);
          try (java.sql.PreparedStatement ps =
              c.prepareStatement(
                  "select tenant_id from tenant_data_store where tenant_id = ? for update")) {
            ps.setLong(1, tenantId);
            ps.executeQuery().close();
            return call.get();
          } finally {
            c.rollback();
            c.setAutoCommit(true);
          }
        });
  }

  @Override
  public boolean isDataStoreRowLockedByOther(long tenantId) {
    return ownerJdbc.execute(
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
    return TenantRlsTestSupport.runInTenantTransaction(tx, tenantId, call);
  }

  @Override
  public <T> T noTenant(Supplier<T> call) {
    TenantContext.clear();
    return call.get();
  }

  @Override
  public void setAiSessionUpdatedAt(long sessionId, LocalDateTime updatedAt) {
    ownerJdbc.update("update ai_session set updated_at = ? where id = ?", updatedAt, sessionId);
  }

  @Override
  public String aiSessionTitle(long sessionId) {
    return ownerJdbc.queryForObject(
        "select title from ai_session where id = ?", String.class, sessionId);
  }

  @Override
  public void setReembedLeaseFromDbNow(long tenantId, int seconds) {
    ownerJdbc.update(
        "update embedding_reembed_state set lease_until = now() + (? * interval '1 second')"
            + " where tenant_id = ?",
        seconds,
        tenantId);
  }

  @Override
  public java.time.Duration reembedLeaseRemaining(long tenantId) {
    // 임대 만료 시각과 DB 시계를 같은 문장에서 읽는다 — 둘 다 timestamptz 라 순간으로 뺀다.
    return ownerJdbc.queryForObject(
        "select lease_until, now() as db_now from embedding_reembed_state where tenant_id = ?",
        (rs, i) -> {
          java.time.OffsetDateTime lease = rs.getObject(1, java.time.OffsetDateTime.class);
          java.time.OffsetDateTime now = rs.getObject(2, java.time.OffsetDateTime.class);
          return lease == null ? null : java.time.Duration.between(now, lease);
        },
        tenantId);
  }

  @Override
  public void setReembedUpdatedAtFromDbNow(long tenantId, int seconds) {
    ownerJdbc.update(
        "update embedding_reembed_state set updated_at = now() + (? * interval '1 second')"
            + " where tenant_id = ?",
        seconds,
        tenantId);
  }

  @Override
  public void putTenantSettingFromDbNow(long tenantId, String key, String value, int seconds) {
    // 운영(jOOQ currentLocalDateTime)과 같은 LOCALTIMESTAMP 기준 — 세션 TimeZone 벽시계 TIMESTAMP 다.
    ownerJdbc.update(
        "insert into tenant_settings (tenant_id, key, value, updated_at)"
            + " values (?, ?, ?, LOCALTIMESTAMP + (? * interval '1 second'))"
            + " on conflict (tenant_id, key) do update set value = excluded.value,"
            + " updated_at = excluded.updated_at",
        tenantId,
        key,
        value,
        seconds);
  }

  @Override
  public java.time.Instant dbNow() {
    return ownerJdbc.queryForObject("select now()", java.time.OffsetDateTime.class).toInstant();
  }

  @Override
  public boolean emptyStringIsNull() {
    return false;
  }

  @Override
  public void deleteDataset(long datasetId) {
    ownerJdbc.update("delete from dataset where id = ?", datasetId);
  }

  @Override
  public void setOntologySchemaVersion(long ontologyId, int version) {
    ownerJdbc.update("update ontology set schema_version = ? where id = ?", version, ontologyId);
  }

  @Override
  public void insertGraphSource(long tenantId, long ontologyId, long datasetId) {
    ownerJdbc.update(
        "insert into graph_ontology_source (tenant_id, ontology_id, dataset_id) values (?, ?, ?)",
        tenantId,
        ontologyId,
        datasetId);
  }

  @Override
  public int countGraphSources(long tenantId, long ontologyId, long datasetId) {
    return ownerJdbc.queryForObject(
        "select count(*) from graph_ontology_source"
            + " where tenant_id = ? and ontology_id = ? and dataset_id = ?",
        Integer.class,
        tenantId,
        ontologyId,
        datasetId);
  }

  @Override
  public long insertGraphIngest(
      long tenantId, long datasetId, int schemaVersion, LocalDateTime ingestedAt) {
    return ownerJdbc.queryForObject(
        "insert into dataset_graph_ingest (tenant_id, dataset_id, ingested_at, schema_version_at_ingest)"
            + " values (?, ?, ?, ?) returning id",
        Long.class,
        tenantId,
        datasetId,
        ingestedAt,
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
        ownerJdbc.queryForObject(
            "select pipeline_id from pipeline_trigger where id = ?", Long.class, triggerId);
    return ownerJdbc.queryForObject(
        "insert into trigger_event (tenant_id, trigger_id, pipeline_id, execution_id, event_type,"
            + " detail, created_at) values (?, ?, ?, ?, ?, ?::jsonb, ?) returning id",
        Long.class,
        ownerTenantOf("pipeline", pipelineId),
        triggerId,
        pipelineId,
        executionId,
        eventType,
        detailJson,
        createdAt);
  }

  @Override
  public List<LocalDateTime> triggerEventCreatedAts(long triggerId) {
    return ownerJdbc.query(
        "select created_at from trigger_event where trigger_id = ? order by id",
        (rs, i) -> rs.getObject(1, LocalDateTime.class),
        triggerId);
  }

  @Override
  public int countTriggerEvents(long pipelineId) {
    return ownerJdbc.queryForObject(
        "select count(*) from trigger_event where pipeline_id = ?", Integer.class, pipelineId);
  }

  @Override
  public void setTriggerEnabled(long triggerId, boolean enabled) {
    ownerJdbc.update("update pipeline_trigger set is_enabled = ? where id = ?", enabled, triggerId);
  }

  @Override
  public void setTriggerState(long triggerId, String stateJson) {
    ownerJdbc.update(
        "update pipeline_trigger set trigger_state = ?::jsonb where id = ?", stateJson, triggerId);
  }

  @Override
  public void setTriggerUpdatedAt(long triggerId, LocalDateTime updatedAt) {
    ownerJdbc.update(
        "update pipeline_trigger set updated_at = ? where id = ?", updatedAt, triggerId);
  }

  @Override
  public TriggerStored triggerStored(long triggerId) {
    List<TriggerStored> rows =
        ownerJdbc.query(
            "select config::text as config, trigger_state::text as state, is_enabled, description,"
                + " updated_by, created_at, updated_at from pipeline_trigger where id = ?",
            (rs, i) ->
                new TriggerStored(
                    rs.getString("config"),
                    rs.getString("state"),
                    rs.getBoolean("is_enabled"),
                    rs.getString("description"),
                    rs.getObject("updated_by", Long.class),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class)),
            triggerId);
    return rows.isEmpty() ? null : rows.get(0);
  }

  @Override
  public void setPipelineUpdatedAt(long pipelineId, LocalDateTime updatedAt) {
    ownerJdbc.update("update pipeline set updated_at = ? where id = ?", updatedAt, pipelineId);
  }

  @Override
  public int countAiInferenceCache(long tenantId, String rowHash, String promptVersion) {
    return ownerJdbc.queryForObject(
        "select count(*) from ai_inference_cache where tenant_id = ? and row_hash = ?"
            + " and prompt_version = ?",
        Integer.class,
        tenantId,
        rowHash,
        promptVersion);
  }

  @Override
  public LocalDateTime aiInferenceCacheCreatedAt(
      long tenantId, String rowHash, String promptVersion) {
    return ownerJdbc
        .query(
            "select created_at from ai_inference_cache where tenant_id = ? and row_hash = ?"
                + " and prompt_version = ?",
            (rs, i) -> rs.getObject(1, LocalDateTime.class),
            tenantId,
            rowHash,
            promptVersion)
        .stream()
        .findFirst()
        .orElse(null);
  }

  @Override
  public ApiConnectionStored apiConnectionStored(long id) {
    return ownerJdbc
        .query(
            "select base_url, health_check_path, last_status, last_latency_ms, last_error_message,"
                + " auth_config, created_at, updated_at, last_checked_at from api_connection"
                + " where id = ?",
            (rs, i) ->
                new ApiConnectionStored(
                    rs.getString("base_url"),
                    rs.getString("health_check_path"),
                    rs.getString("last_status"),
                    rs.getObject("last_latency_ms") == null ? null : rs.getLong("last_latency_ms"),
                    rs.getString("last_error_message"),
                    rs.getString("auth_config"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class),
                    rs.getObject("last_checked_at", LocalDateTime.class)),
            id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  @Override
  public void setApiConnectionUpdatedAt(long id, LocalDateTime at) {
    ownerJdbc.update("update api_connection set updated_at = ? where id = ?", at, id);
  }

  @Override
  public long createApiCallStep(long pipelineId, String name, long apiConnectionId) {
    return inTenant(
        ownerTenantOf("pipeline", pipelineId),
        () ->
            dsl.fetchOne(
                    "insert into pipeline_step (pipeline_id, name, script_type, step_order,"
                        + " api_connection_id) values (?, ?, 'API_CALL', 0, ?) returning id",
                    pipelineId,
                    name,
                    apiConnectionId)
                .get(0, Long.class));
  }

  @Override
  public List<DatasetMappingStored> datasetMappingRows(long tenantId, long datasetId) {
    return ownerJdbc.query(
        "select ontology_id, status, updated_by, updated_at from dataset_mapping"
            + " where tenant_id = ? and dataset_id = ?",
        (rs, i) ->
            new DatasetMappingStored(
                rs.getLong("ontology_id"),
                rs.getString("status"),
                rs.getObject("updated_by", Long.class),
                rs.getObject("updated_at", java.time.OffsetDateTime.class).toInstant()),
        tenantId,
        datasetId);
  }

  @Override
  public void setDatasetMappingUpdatedAt(long tenantId, long datasetId, java.time.Instant at) {
    ownerJdbc.update(
        "update dataset_mapping set updated_at = ? where tenant_id = ? and dataset_id = ?",
        java.time.OffsetDateTime.ofInstant(at, java.time.ZoneOffset.UTC),
        tenantId,
        datasetId);
  }

  @Override
  public LocalDateTime stepCreatedAt(long stepId) {
    return ownerJdbc.queryForObject(
        "select created_at from pipeline_step where id = ?", LocalDateTime.class, stepId);
  }

  // ---- 묶음 ③ Task 11(WD-708: 파이프라인 실행) ----

  @Override
  public long createExecution(
      long pipelineId,
      long executedBy,
      String status,
      LocalDateTime heartbeatAt,
      LocalDateTime startedAt) {
    return ownerJdbc.queryForObject(
        "insert into pipeline_execution (tenant_id, pipeline_id, status, executed_by, heartbeat_at,"
            + " started_at) values (?, ?, ?, ?, ?, ?) returning id",
        Long.class,
        ownerTenantOf("pipeline", pipelineId),
        pipelineId,
        status,
        executedBy,
        heartbeatAt,
        startedAt);
  }

  @Override
  public long createStepExecution(
      long executionId,
      Long stepId,
      String stepName,
      String status,
      LocalDateTime startedAt,
      LocalDateTime completedAt) {
    return ownerJdbc.queryForObject(
        "insert into pipeline_step_execution (tenant_id, execution_id, step_id, step_name, status,"
            + " started_at, completed_at) values (?, ?, ?, ?, ?, ?, ?) returning id",
        Long.class,
        ownerTenantOf("pipeline_execution", executionId),
        executionId,
        stepId,
        stepName,
        status,
        startedAt,
        completedAt);
  }

  @Override
  public void setExecutionField(long executionId, String column, Object value) {
    String col = AccessFixtures.executionField(column);
    if (value == null) {
      ownerJdbc.update(
          "update pipeline_execution set " + col + " = null where id = ?", executionId);
      return;
    }
    ownerJdbc.update(
        "update pipeline_execution set " + col + " = ? where id = ?", value, executionId);
  }

  @Override
  public ExecutionStored executionStored(long executionId) {
    List<ExecutionStored> rows =
        ownerJdbc.query(
            "select pipeline_id, status, executed_by, triggered_by, trigger_id, error_message,"
                + " started_at, completed_at, created_at, heartbeat_at, cancel_requested_at,"
                + " cancel_requested_by from pipeline_execution where id = ?",
            (rs, i) ->
                new ExecutionStored(
                    rs.getLong("pipeline_id"),
                    rs.getString("status"),
                    rs.getLong("executed_by"),
                    rs.getString("triggered_by"),
                    rs.getObject("trigger_id", Long.class),
                    rs.getString("error_message"),
                    rs.getObject("started_at", LocalDateTime.class),
                    rs.getObject("completed_at", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("heartbeat_at", LocalDateTime.class),
                    rs.getObject("cancel_requested_at", LocalDateTime.class),
                    rs.getObject("cancel_requested_by", Long.class)),
            executionId);
    return rows.isEmpty() ? null : rows.get(0);
  }

  @Override
  public StepExecutionStored stepExecutionStored(long stepExecId) {
    List<StepExecutionStored> rows =
        ownerJdbc.query(
            "select step_id, step_name, status, output_rows, log, error_message, started_at,"
                + " completed_at, injected_last_run_at from pipeline_step_execution where id = ?",
            (rs, i) -> {
              java.time.OffsetDateTime injected =
                  rs.getObject("injected_last_run_at", java.time.OffsetDateTime.class);
              return new StepExecutionStored(
                  rs.getObject("step_id", Long.class),
                  rs.getString("step_name"),
                  rs.getString("status"),
                  rs.getObject("output_rows", Integer.class),
                  rs.getString("log"),
                  rs.getString("error_message"),
                  rs.getObject("started_at", LocalDateTime.class),
                  rs.getObject("completed_at", LocalDateTime.class),
                  injected == null ? null : injected.toInstant());
            },
            stepExecId);
    return rows.isEmpty() ? null : rows.get(0);
  }

  @Override
  public void withFailingStepExecutionUpdate(String marker, Runnable body) {
    String cond =
        marker != null ? "NEW.error_message = '" + marker + "'" : "NEW.status = 'SKIPPED'";
    // DDL(임시 트리거)은 앱 롤에 권한이 없어 플랫폼 DB 소유자 연결로 건다(WD-494 — 스키마 소유자 풀은 분리 모드에서 데이터 저장소를
    // 가리킬 수 있어 플랫폼 소유자 풀을 따로 쓴다).
    JdbcTemplate ddl =
        new JdbcTemplate(com.smartfirehub.support.PostgresTestContainer.platformOwnerDataSource());
    ddl.execute(
        "CREATE OR REPLACE FUNCTION wd230_step_boom() RETURNS trigger AS $$ BEGIN IF "
            + cond
            + " THEN RAISE EXCEPTION 'wd230 boom'; END IF; RETURN NEW; END $$ LANGUAGE plpgsql");
    ddl.execute(
        "CREATE TRIGGER wd230_step_boom_trg BEFORE UPDATE ON pipeline_step_execution"
            + " FOR EACH ROW EXECUTE FUNCTION wd230_step_boom()");
    try {
      body.run();
    } finally {
      ddl.execute("DROP TRIGGER IF EXISTS wd230_step_boom_trg ON pipeline_step_execution");
      ddl.execute("DROP FUNCTION IF EXISTS wd230_step_boom()");
    }
  }

  @Override
  public long createSlackWorkspace(long tenantId, String teamId, boolean revoked) {
    return inTenant(
        tenantId,
        () ->
            dsl.fetchOne(
                    "insert into slack_workspace (team_id, team_name, bot_user_id, bot_token_enc,"
                        + " signing_secret_enc, revoked_at) values (?, 'fx-team', 'UBOT', 'enc-bot',"
                        + " 'enc-sign', case when ? then now() else null end) returning id",
                    teamId,
                    revoked)
                .get(0, Long.class));
  }

  @Override
  public long createProactiveJob(
      long tenantId,
      long userId,
      String name,
      boolean enabled,
      String triggerType,
      String configJson) {
    // config 가 null 이면 열 기본값({})에 맡긴다 — jsonb 로 CAST 해 문자열을 그대로 넣는다.
    return inTenant(
        tenantId,
        () ->
            dsl.fetchOne(
                    "insert into proactive_job (user_id, name, prompt, cron_expression, enabled,"
                        + " trigger_type, config) values (?, ?, 'fx prompt', '0 0 9 * * *', ?, ?,"
                        + " coalesce(cast(? as jsonb), '{}'::jsonb)) returning id",
                    userId,
                    name,
                    enabled,
                    triggerType,
                    configJson)
                .get(0, Long.class));
  }

  @Override
  public long createProactiveJob(long tenantId, long userId, String name) {
    return inTenant(
        tenantId,
        () ->
            dsl.fetchOne(
                    "insert into proactive_job (user_id, name, prompt, cron_expression, enabled)"
                        + " values (?, ?, 'fx prompt', '0 0 9 * * *', true) returning id",
                    userId,
                    name)
                .get(0, Long.class));
  }

  @Override
  public long createReportTemplate(long tenantId, Long userId, String name) {
    return inTenant(
        tenantId,
        () ->
            dsl.fetchOne(
                    "insert into report_template (name, user_id) values (?, ?) returning id",
                    name,
                    userId)
                .get(0, Long.class));
  }

  @Override
  public ProactiveJobStored proactiveJobStored(long id) {
    return ownerJdbc
        .query(
            "select name, prompt, template_id, cron_expression, timezone, enabled, trigger_type,"
                + " config::text as config, last_executed_at, next_execute_at, created_at,"
                + " updated_at from proactive_job where id = ?",
            (rs, i) ->
                new ProactiveJobStored(
                    rs.getString("name"),
                    rs.getString("prompt"),
                    rs.getObject("template_id", Long.class),
                    rs.getString("cron_expression"),
                    rs.getString("timezone"),
                    rs.getBoolean("enabled"),
                    rs.getString("trigger_type"),
                    rs.getString("config"),
                    rs.getObject("last_executed_at", LocalDateTime.class),
                    rs.getObject("next_execute_at", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class)),
            id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  @Override
  public void setProactiveJobUpdatedAt(long id, LocalDateTime updatedAt) {
    ownerJdbc.update("update proactive_job set updated_at = ? where id = ?", updatedAt, id);
  }

  @Override
  public ReportTemplateStored reportTemplateStored(long id) {
    return ownerJdbc
        .query(
            "select name, description, sections::text as sections, style, user_id, created_at,"
                + " updated_at from report_template where id = ?",
            (rs, i) ->
                new ReportTemplateStored(
                    rs.getString("name"),
                    rs.getString("description"),
                    rs.getString("sections"),
                    rs.getString("style"),
                    rs.getObject("user_id", Long.class),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class)),
            id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  @Override
  public void setReportTemplateUpdatedAt(long id, LocalDateTime updatedAt) {
    ownerJdbc.update("update report_template set updated_at = ? where id = ?", updatedAt, id);
  }

  @Override
  public void setReportTemplateSectionsJson(long id, String sectionsJson) {
    ownerJdbc.update(
        "update report_template set sections = cast(? as jsonb) where id = ?", sectionsJson, id);
  }

  @Override
  public long createProactiveJobExecution(long tenantId, long jobId, String status) {
    return inTenant(
        tenantId,
        () ->
            dsl.fetchOne(
                    "insert into proactive_job_execution (job_id, status) values (?, ?) returning id",
                    jobId,
                    status)
                .get(0, Long.class));
  }

  @Override
  public ProactiveExecutionStored proactiveExecutionStored(long id) {
    return ownerJdbc
        .query(
            "select job_id, status, started_at, completed_at, error_message, result::text as result,"
                + " delivered_channels, security_level_id, heartbeat_at, created_at"
                + " from proactive_job_execution where id = ?",
            (rs, i) ->
                new ProactiveExecutionStored(
                    rs.getLong("job_id"),
                    rs.getString("status"),
                    rs.getObject("started_at", LocalDateTime.class),
                    rs.getObject("completed_at", LocalDateTime.class),
                    rs.getString("error_message"),
                    rs.getString("result"),
                    rs.getString("delivered_channels"),
                    rs.getObject("security_level_id", Long.class),
                    rs.getObject("heartbeat_at", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class)),
            id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  @Override
  public void setProactiveExecutionField(long id, String column, Object value) {
    String col = AccessFixtures.proactiveExecutionField(column);
    if (value == null) {
      ownerJdbc.update("update proactive_job_execution set " + col + " = null where id = ?", id);
      return;
    }
    ownerJdbc.update("update proactive_job_execution set " + col + " = ? where id = ?", value, id);
  }

  @Override
  public void setProactiveExecutionResultJson(long id, String resultJson) {
    ownerJdbc.update(
        "update proactive_job_execution set result = cast(? as jsonb) where id = ?",
        resultJson,
        id);
  }

  @Override
  public OAuthStateStored oauthStateStored(String state) {
    return ownerJdbc
        .query(
            "select user_id, channel_type, tenant_id, consumed_at is not null as consumed"
                + " from oauth_state where state = ?",
            (rs, i) ->
                new OAuthStateStored(
                    rs.getLong("user_id"),
                    rs.getString("channel_type"),
                    rs.getLong("tenant_id"),
                    rs.getBoolean("consumed")),
            state)
        .stream()
        .findFirst()
        .orElse(null);
  }

  @Override
  public Boolean userChannelPreferenceEnabled(long tenantId, long userId, String channelType) {
    return ownerJdbc
        .query(
            "select enabled from user_channel_preference"
                + " where tenant_id = ? and user_id = ? and channel_type = ?",
            (rs, i) -> rs.getBoolean(1),
            tenantId,
            userId,
            channelType)
        .stream()
        .findFirst()
        .orElse(null);
  }

  @Override
  public SlackWorkspaceStored slackWorkspaceStored(long id) {
    return ownerJdbc
        .query(
            "select team_id, team_name, bot_user_id, bot_token_enc, signing_secret_enc,"
                + " installed_by_user_id, revoked_at is not null as revoked"
                + " from slack_workspace where id = ?",
            (rs, i) ->
                new SlackWorkspaceStored(
                    rs.getString("team_id"),
                    rs.getString("team_name"),
                    rs.getString("bot_user_id"),
                    rs.getString("bot_token_enc"),
                    rs.getString("signing_secret_enc"),
                    rs.getObject("installed_by_user_id", Long.class),
                    rs.getBoolean("revoked")),
            id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  @Override
  public void setSlackSigningSecret(long id, String enc) {
    ownerJdbc.update("update slack_workspace set signing_secret_enc = ? where id = ?", enc, id);
  }

  @Override
  public void setSlackPreviousSigningSecret(long id, String enc, java.time.Instant expiresAt) {
    ownerJdbc.update(
        "update slack_workspace set previous_signing_secret_enc = ?,"
            + " previous_signing_secret_expires_at = ? where id = ?",
        enc,
        java.time.OffsetDateTime.ofInstant(expiresAt, java.time.ZoneOffset.UTC),
        id);
  }

  @Override
  public long createSavedQuery(
      long tenantId, long userId, String name, boolean shared, Long datasetId) {
    return inTenant(
        tenantId,
        () ->
            dsl.fetchOne(
                    "insert into saved_query (name, sql_text, dataset_id, is_shared, created_by)"
                        + " values (?, 'SELECT 1', ?, ?, ?) returning id",
                    name,
                    datasetId,
                    shared,
                    userId)
                .get(0, Long.class));
  }

  @Override
  public long createChart(long tenantId, long userId, long savedQueryId, boolean shared) {
    return inTenant(
        tenantId,
        () ->
            dsl.fetchOne(
                    "insert into chart (name, saved_query_id, chart_type, is_shared, created_by)"
                        + " values ('fx-c', ?, 'BAR', ?, ?) returning id",
                    savedQueryId,
                    shared,
                    userId)
                .get(0, Long.class));
  }

  @Override
  public long createDashboard(long tenantId, long userId, boolean shared) {
    return createDashboard(tenantId, "fx-d", userId, shared);
  }

  @Override
  public long addWidget(long tenantId, long dashboardId, long chartId) {
    return inTenant(
        tenantId,
        () ->
            dsl.fetchOne(
                    "insert into dashboard_widget (dashboard_id, chart_id) values (?, ?) returning id",
                    dashboardId,
                    chartId)
                .get(0, Long.class));
  }

  @Override
  public void setSavedQueryUpdatedAt(long id, LocalDateTime updatedAt) {
    ownerJdbc.update("update saved_query set updated_at = ? where id = ?", updatedAt, id);
  }

  @Override
  public Long savedQueryUpdatedBy(long id) {
    List<Long> rows =
        ownerJdbc.query(
            "select updated_by from saved_query where id = ?",
            (rs, i) -> rs.getObject(1, Long.class),
            id);
    return rows.isEmpty() ? null : rows.get(0);
  }

  @Override
  public void ageAnalyticsQueryRun(UUID id, int minutesAgo) {
    // DB 시계(now())로 옮긴다 — 리포지토리의 보존 기간 비교도 DB 시계라 두 시계 차이가 끼지 않는다.
    ownerJdbc.update(
        "update analytics_query_run set created_at = now() - (? * interval '1 minute')"
            + " where id = ?",
        minutesAgo,
        id);
  }

  @Override
  public boolean analyticsQueryRunExists(UUID id) {
    Integer n =
        ownerJdbc.queryForObject(
            "select count(*) from analytics_query_run where id = ?", Integer.class, id);
    return n != null && n > 0;
  }

  @Override
  public void setChartUpdatedAt(long chartId, LocalDateTime updatedAt) {
    ownerJdbc.update("update chart set updated_at = ? where id = ?", updatedAt, chartId);
  }

  @Override
  public Long chartUpdatedBy(long chartId) {
    List<Long> rows =
        ownerJdbc.query(
            "select updated_by from chart where id = ?",
            (rs, i) -> rs.getObject(1, Long.class),
            chartId);
    return rows.isEmpty() ? null : rows.get(0);
  }

  @Override
  public boolean widgetExists(long widgetId) {
    Integer n =
        ownerJdbc.queryForObject(
            "select count(*) from dashboard_widget where id = ?", Integer.class, widgetId);
    return n != null && n > 0;
  }

  @Override
  public void setWidgetUpdatedAt(long widgetId, LocalDateTime updatedAt) {
    ownerJdbc.update(
        "update dashboard_widget set updated_at = ? where id = ?", updatedAt, widgetId);
  }

  @Override
  public LocalDateTime widgetUpdatedAt(long widgetId) {
    return ownerJdbc
        .queryForObject(
            "select updated_at from dashboard_widget where id = ?",
            java.sql.Timestamp.class,
            widgetId)
        .toLocalDateTime();
  }

  @Override
  public void setDashboardUpdatedAt(long dashboardId, LocalDateTime updatedAt) {
    ownerJdbc.update("update dashboard set updated_at = ? where id = ?", updatedAt, dashboardId);
  }

  @Override
  public Long dashboardUpdatedBy(long dashboardId) {
    List<Long> rows =
        ownerJdbc.query(
            "select updated_by from dashboard where id = ?",
            (rs, i) -> rs.getObject(1, Long.class),
            dashboardId);
    return rows.isEmpty() ? null : rows.get(0);
  }

  @Override
  public void setAuditLogResult(long id, String result) {
    ownerJdbc.update("update audit_log set result = ? where id = ?", result, id);
  }

  @Override
  public void cleanup() {
    for (long t : tenants) {
      // 적재 이력은 테넌트 FK 만 있어(온톨로지 CASCADE 밖) 테넌트 정리 전에 지운다.
      ownerJdbc.update("delete from dataset_graph_ingest where tenant_id = ?", t);
    }
    cleanupRows();
  }

  private void cleanupRows() {
    for (String k : systemSettingKeys) {
      dsl.execute("delete from system_settings where key = ?", k);
    }
    // 테넌트 없는(플랫폼) 감사 행은 GUC 없는 트랜잭션에서만 보인다 — 사용자 FK 를 잡으므로 사용자보다 먼저 지운다.
    for (long a : auditLogs) {
      inTenant(null, () -> dsl.execute("delete from audit_log where id = ?", a));
    }
    // 바인딩은 다른 테넌트의 슬랙 워크스페이스를 참조할 수 있어(같은 workspace 를 B 가 upsert, WD-709) 모든 테넌트의 바인딩을
    // 워크스페이스 삭제보다 먼저 지운다 — 테넌트별 루프 안에서만 지우면 A 의 워크스페이스 삭제가 B 의 바인딩 FK 에 막힌다.
    for (long t : tenants) {
      inTenant(t, () -> dsl.execute("delete from user_channel_binding where tenant_id = ?", t));
    }
    for (long t : tenants) {
      // 프로비저닝·리포지토리가 만든 RBAC·시드 행과 설정 오버라이드를 그 테넌트 컨텍스트 안에서 지운다. 감사·작업·데이터셋은
      // 사용자·보안 등급을 참조하므로 먼저 지운다.
      inTenant(
          t,
          () -> {
            dsl.execute("delete from audit_log where tenant_id = ?", t);
            dsl.execute("delete from async_job where tenant_id = ?", t);
            // ai_session 은 사용자 FK 가 CASCADE 가 아니라 사용자보다 먼저 지운다(WD-712).
            dsl.execute("delete from ai_session where tenant_id = ?", t);
            dsl.execute("delete from dataset_access_grant where tenant_id = ?", t);
            dsl.execute("delete from dataset_mapping where tenant_id = ?", t);
            // 온톨로지 하위(엔티티 타입·속성·관계·데이터셋 연결)는 FK CASCADE 로 함께 사라진다.
            dsl.execute("delete from ontology where tenant_id = ?", t);
            // 대시보드·저장 쿼리·파이프라인은 사용자(created_by)를 참조하고, 스텝의 output_dataset_id 는 ON DELETE 없는 데이터셋
            // FK 라 데이터셋보다 먼저 지운다. 위젯·차트·스텝·스텝 입력·트리거는 FK CASCADE 로 함께 사라진다.
            // uploaded_files 는 사용자 FK 가 CASCADE 가 아니라 사용자보다 먼저 지운다(WD-712).
            dsl.execute("delete from uploaded_files where tenant_id = ?", t);
            dsl.execute("delete from ai_inference_cache where tenant_id = ?", t);
            // 쿼리 실행 기록은 테넌트 FK 가 CASCADE 가 아니라 테넌트보다 먼저, 위젯·차트는 대시보드·저장 쿼리보다 먼저(자식 먼저) 지운다
            // — 다른 테넌트 행을 참조하는 일은 없지만 CASCADE 에만 기대지 않고 정리 순서를 드러낸다(WD-710).
            dsl.execute("delete from analytics_query_run where tenant_id = ?", t);
            dsl.execute("delete from dashboard_widget where tenant_id = ?", t);
            dsl.execute("delete from chart where tenant_id = ?", t);
            dsl.execute("delete from dashboard where tenant_id = ?", t);
            dsl.execute("delete from saved_query where tenant_id = ?", t);
            dsl.execute("delete from pipeline where tenant_id = ?", t);
            // API 연결은 파이프라인 스텝이 참조하므로 파이프라인 뒤, 사용자(created_by) 앞에서 지운다(WD-708).
            dsl.execute("delete from api_connection where tenant_id = ?", t);
            dsl.execute("delete from dataset where tenant_id = ?", t);
            dsl.execute("delete from tenant_settings where tenant_id = ?", t);
            dsl.execute("delete from embedding_reembed_state where tenant_id = ?", t);
            // 선제 작업(실행 이력 CASCADE)은 사용자·템플릿을 참조하고, 슬랙 워크스페이스는 테넌트·사용자를 참조한다(WD-709·710).
            dsl.execute("delete from proactive_job where tenant_id = ?", t);
            // 채널 선호는 사용자 FK 가 CASCADE 가 아니라 사용자보다 먼저, oauth_state 도 같은 이유로 함께 지운다(WD-709).
            dsl.execute("delete from user_channel_preference where tenant_id = ?", t);
            dsl.execute("delete from oauth_state where tenant_id = ?", t);
            // 바인딩의 workspace_id FK 는 CASCADE 가 아니라 워크스페이스보다 먼저 지운다(WD-709).
            dsl.execute("delete from user_channel_binding where tenant_id = ?", t);
            dsl.execute("delete from slack_workspace where tenant_id = ?", t);
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
