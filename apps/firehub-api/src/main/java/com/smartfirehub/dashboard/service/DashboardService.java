package com.smartfirehub.dashboard.service;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.dashboard.dto.ActivityFeedResponse;
import com.smartfirehub.dashboard.dto.ActivityFeedResponse.ActivityItem;
import com.smartfirehub.dashboard.dto.AttentionItemResponse;
import com.smartfirehub.dashboard.dto.DashboardStatsResponse;
import com.smartfirehub.dashboard.dto.RecentExecutionResponse;
import com.smartfirehub.dashboard.dto.RecentImportResponse;
import com.smartfirehub.dashboard.dto.SystemHealthResponse;
import com.smartfirehub.dashboard.dto.SystemHealthResponse.DatasetHealth;
import com.smartfirehub.dashboard.dto.SystemHealthResponse.PipelineHealth;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {

  private final DSLContext dsl;

  // Table constants
  private static final Table<?> DATASET = table(name("dataset"));
  private static final Field<Long> D_ID = field(name("dataset", "id"), Long.class);
  private static final Field<String> D_NAME = field(name("dataset", "name"), String.class);
  private static final Field<String> D_TABLE_NAME =
      field(name("dataset", "table_name"), String.class);
  private static final Field<String> D_DATASET_TYPE =
      field(name("dataset", "dataset_type"), String.class);
  private static final Field<LocalDateTime> D_CREATED_AT =
      field(name("dataset", "created_at"), LocalDateTime.class);

  private static final Table<?> PIPELINE = table(name("pipeline"));
  private static final Field<Long> P_ID = field(name("pipeline", "id"), Long.class);
  private static final Field<String> P_NAME = field(name("pipeline", "name"), String.class);
  private static final Field<Boolean> P_IS_ACTIVE =
      field(name("pipeline", "is_active"), Boolean.class);

  // audit_log constants
  private static final Table<?> AUDIT_LOG = table(name("audit_log"));
  private static final Field<Long> AL_ID = field(name("audit_log", "id"), Long.class);
  private static final Field<String> AL_ACTION_TYPE =
      field(name("audit_log", "action_type"), String.class);
  private static final Field<String> AL_RESOURCE =
      field(name("audit_log", "resource"), String.class);
  private static final Field<String> AL_RESOURCE_ID =
      field(name("audit_log", "resource_id"), String.class);
  private static final Field<String> AL_RESULT = field(name("audit_log", "result"), String.class);
  private static final Field<LocalDateTime> AL_ACTION_TIME =
      field(name("audit_log", "action_time"), LocalDateTime.class);
  private static final Field<String> AL_DESCRIPTION =
      field(name("audit_log", "description"), String.class);

  private static final Table<?> PIPELINE_EXECUTION = table(name("pipeline_execution"));
  private static final Field<Long> PE_ID = field(name("pipeline_execution", "id"), Long.class);
  private static final Field<Long> PE_PIPELINE_ID =
      field(name("pipeline_execution", "pipeline_id"), Long.class);
  private static final Field<String> PE_STATUS =
      field(name("pipeline_execution", "status"), String.class);
  private static final Field<LocalDateTime> PE_CREATED_AT =
      field(name("pipeline_execution", "created_at"), LocalDateTime.class);
  private static final Field<LocalDateTime> PE_STARTED_AT =
      field(name("pipeline_execution", "started_at"), LocalDateTime.class);
  private static final Field<LocalDateTime> PE_COMPLETED_AT =
      field(name("pipeline_execution", "completed_at"), LocalDateTime.class);

  public DashboardService(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Transactional(readOnly = true)
  public DashboardStatsResponse getStats() {
    // Count total datasets
    long totalDatasets = dsl.selectCount().from(DATASET).fetchOne(0, Long.class);

    // Count source datasets
    long sourceDatasets =
        dsl.selectCount().from(DATASET).where(D_DATASET_TYPE.eq("SOURCE")).fetchOne(0, Long.class);

    // Count derived datasets
    long derivedDatasets =
        dsl.selectCount().from(DATASET).where(D_DATASET_TYPE.eq("DERIVED")).fetchOne(0, Long.class);

    // Count total pipelines
    long totalPipelines = dsl.selectCount().from(PIPELINE).fetchOne(0, Long.class);

    // Count active pipelines
    long activePipelines =
        dsl.selectCount().from(PIPELINE).where(P_IS_ACTIVE.eq(true)).fetchOne(0, Long.class);

    // Get recent imports from audit_log (top 5)
    Field<String> metadataFileName = field("audit_log.metadata->>'fileName'", String.class);

    List<RecentImportResponse> recentImports =
        dsl.select(AL_ID, D_NAME, metadataFileName, AL_RESULT, AL_ACTION_TIME)
            .from(AUDIT_LOG)
            .join(DATASET)
            .on(AL_RESOURCE_ID.cast(Long.class).eq(D_ID))
            .where(AL_ACTION_TYPE.eq("IMPORT").and(AL_RESOURCE.eq("dataset")))
            .orderBy(AL_ACTION_TIME.desc())
            .limit(5)
            .fetch(
                r -> {
                  String status =
                      switch (r.get(AL_RESULT)) {
                        case "SUCCESS" -> "COMPLETED";
                        case "FAILURE" -> "FAILED";
                        default -> r.get(AL_RESULT);
                      };
                  return new RecentImportResponse(
                      r.get(AL_ID),
                      r.get(D_NAME),
                      r.get(metadataFileName),
                      status,
                      r.get(AL_ACTION_TIME));
                });

    // Get recent executions (top 5)
    List<RecentExecutionResponse> recentExecutions =
        dsl.select(PE_ID, P_NAME, PE_STATUS, PE_CREATED_AT)
            .from(PIPELINE_EXECUTION)
            .join(PIPELINE)
            .on(PE_PIPELINE_ID.eq(P_ID))
            .orderBy(PE_CREATED_AT.desc())
            .limit(5)
            .fetch(
                r ->
                    new RecentExecutionResponse(
                        r.get(PE_ID), r.get(P_NAME), r.get(PE_STATUS), r.get(PE_CREATED_AT)));

    return new DashboardStatsResponse(
        totalDatasets,
        sourceDatasets,
        derivedDatasets,
        totalPipelines,
        activePipelines,
        recentImports,
        recentExecutions);
  }

  @Transactional(readOnly = true)
  public SystemHealthResponse getSystemHealth() {
    // ---- Pipeline health ----
    // Count all pipelines
    int totalPipelines = dsl.selectCount().from(PIPELINE).fetchOne(0, int.class);

    // Count disabled pipelines (is_active = false)
    int disabledPipelines =
        dsl.selectCount().from(PIPELINE).where(P_IS_ACTIVE.eq(false)).fetchOne(0, int.class);

    // Get the most recent execution status per pipeline (only active pipelines)
    // Use a lateral/subquery approach: for each active pipeline, get its latest execution status
    // We query pipeline_execution grouped by pipeline_id, taking MAX(id) as the latest
    // Build subquery: latest execution per pipeline
    var latestExecSubquery =
        dsl.select(
                PE_PIPELINE_ID.as("pipeline_id"),
                max(PE_ID).as("latest_id"),
                field(
                        "( SELECT pe2.status FROM pipeline_execution pe2"
                            + " WHERE pe2.pipeline_id = pipeline_execution.pipeline_id"
                            + " ORDER BY pe2.id DESC LIMIT 1 )",
                        String.class)
                    .as("latest_status"))
            .from(PIPELINE_EXECUTION)
            .groupBy(PE_PIPELINE_ID)
            .asTable("latest_exec");

    // Join active pipelines with their latest execution status
    List<String> latestStatuses =
        dsl.select(field("latest_exec.latest_status", String.class))
            .from(latestExecSubquery)
            .join(PIPELINE)
            .on(field("latest_exec.pipeline_id", Long.class).eq(P_ID))
            .where(P_IS_ACTIVE.eq(true))
            .fetch(r -> r.get(0, String.class));

    int activePipelinesTotal = totalPipelines - disabledPipelines;

    int runningPipelines = (int) latestStatuses.stream().filter("RUNNING"::equals).count();
    int failingPipelines = (int) latestStatuses.stream().filter("FAILED"::equals).count();
    // healthy = completed + pending (waiting to run) + no execution yet
    int healthyPipelines = activePipelinesTotal - runningPipelines - failingPipelines;
    if (healthyPipelines < 0) healthyPipelines = 0;

    PipelineHealth pipelineHealth =
        new PipelineHealth(
            totalPipelines,
            healthyPipelines,
            failingPipelines,
            runningPipelines,
            disabledPipelines);

    // ---- Dataset health ----
    int totalDatasets = dsl.selectCount().from(DATASET).fetchOne(0, int.class);

    LocalDateTime now = LocalDateTime.now();
    LocalDateTime freshThreshold = now.minusHours(24);

    // fresh: SOURCE datasets with a SUCCESS import within 24h
    int freshDatasets =
        dsl.fetchCount(
            dsl.selectDistinct(AL_RESOURCE_ID)
                .from(AUDIT_LOG)
                .join(DATASET)
                .on(AL_RESOURCE_ID.eq(D_ID.cast(String.class)))
                .where(
                    AL_ACTION_TYPE
                        .eq("IMPORT")
                        .and(AL_RESOURCE.eq("dataset"))
                        .and(AL_RESULT.eq("SUCCESS"))
                        .and(AL_ACTION_TIME.greaterThan(freshThreshold))
                        .and(D_DATASET_TYPE.eq("SOURCE"))));

    // Also count source datasets created within 24h with no imports (brand new = fresh)
    int newSourceNoImport =
        dsl.fetchCount(
            dsl.selectDistinct(D_ID)
                .from(DATASET)
                .where(
                    D_DATASET_TYPE
                        .eq("SOURCE")
                        .and(D_CREATED_AT.greaterThan(freshThreshold))
                        .and(
                            notExists(
                                dsl.selectOne()
                                    .from(AUDIT_LOG)
                                    .where(
                                        AL_ACTION_TYPE
                                            .eq("IMPORT")
                                            .and(AL_RESOURCE.eq("dataset"))
                                            .and(AL_RESOURCE_ID.eq(D_ID.cast(String.class))))))));
    freshDatasets += newSourceNoImport;

    // stale: source datasets whose last import was >24h ago (or has never been imported and >24h
    // old)
    LocalDateTime staleThreshold = now.minusHours(24);

    // Find SOURCE datasets with last import older than 24h
    int staleWithOldImport =
        dsl.fetchCount(
            dsl.selectDistinct(AL_RESOURCE_ID)
                .from(AUDIT_LOG)
                .join(DATASET)
                .on(AL_RESOURCE_ID.eq(D_ID.cast(String.class)))
                .where(
                    AL_ACTION_TYPE
                        .eq("IMPORT")
                        .and(AL_RESOURCE.eq("dataset"))
                        .and(AL_RESULT.eq("SUCCESS"))
                        .and(D_DATASET_TYPE.eq("SOURCE")))
                .andNot(
                    exists(
                        dsl.selectOne()
                            .from(AUDIT_LOG.as("al2"))
                            .where(
                                field("al2.action_type", String.class)
                                    .eq("IMPORT")
                                    .and(field("al2.resource", String.class).eq("dataset"))
                                    .and(field("al2.result", String.class).eq("SUCCESS"))
                                    .and(field("al2.resource_id", String.class).eq(AL_RESOURCE_ID))
                                    .and(
                                        field("al2.action_time", LocalDateTime.class)
                                            .greaterThan(staleThreshold))))));

    // Source datasets created >24h ago with no import at all
    int staleNoImport =
        dsl.fetchCount(
            dsl.selectDistinct(D_ID)
                .from(DATASET)
                .where(
                    D_DATASET_TYPE
                        .eq("SOURCE")
                        .and(D_CREATED_AT.lessOrEqual(staleThreshold))
                        .and(
                            notExists(
                                dsl.selectOne()
                                    .from(AUDIT_LOG)
                                    .where(
                                        AL_ACTION_TYPE
                                            .eq("IMPORT")
                                            .and(AL_RESOURCE.eq("dataset"))
                                            .and(AL_RESOURCE_ID.eq(D_ID.cast(String.class))))))));
    int staleDatasets = staleWithOldImport + staleNoImport;

    // empty: datasets where the data table has 0 rows
    // Query pg_stat_user_tables for row estimates, or do a direct count
    // We use information_schema + pg_class for accurate counts via pg_stat_user_tables
    // For simplicity and correctness, count datasets whose table name appears in
    // pg_stat_user_tables with n_live_tup=0
    int emptyDatasets =
        dsl.fetchCount(
            dsl.select(D_ID)
                .from(DATASET)
                .join(table("pg_stat_user_tables").as("psu"))
                .on(
                    field("psu.relname", String.class)
                        .eq(D_TABLE_NAME)
                        .and(field("psu.schemaname", String.class).eq("data")))
                .where(field("psu.n_live_tup", Long.class).eq(0L)));

    DatasetHealth datasetHealth =
        new DatasetHealth(totalDatasets, freshDatasets, staleDatasets, emptyDatasets);

    return new SystemHealthResponse(pipelineHealth, datasetHealth);
  }

  @Transactional(readOnly = true)
  public List<AttentionItemResponse> getAttentionItems() {
    List<AttentionItemResponse> items = new ArrayList<>();
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime twoHoursAgo = now.minusHours(2);
    LocalDateTime twentyFourHoursAgo = now.minusHours(24);
    LocalDateTime seventyTwoHoursAgo = now.minusHours(72);

    // 1. Failed pipelines: active pipelines whose latest execution is FAILED
    // Get latest execution per pipeline
    var failedPipelines =
        dsl.select(
                P_ID,
                P_NAME,
                field("last_exec.last_status", String.class),
                field("last_exec.last_failed_at", LocalDateTime.class),
                field("last_exec.last_success_at", LocalDateTime.class))
            .from(PIPELINE)
            .join(
                dsl.select(
                        PE_PIPELINE_ID.as("pid"),
                        field(
                                "MAX(CASE WHEN pipeline_execution.status = 'FAILED'"
                                    + " THEN pipeline_execution.created_at END)",
                                LocalDateTime.class)
                            .as("last_failed_at"),
                        field(
                                "MAX(CASE WHEN pipeline_execution.status = 'COMPLETED'"
                                    + " THEN pipeline_execution.created_at END)",
                                LocalDateTime.class)
                            .as("last_success_at"),
                        field(
                                "( SELECT pe3.status FROM pipeline_execution pe3"
                                    + " WHERE pe3.pipeline_id = pipeline_execution.pipeline_id"
                                    + " ORDER BY pe3.id DESC LIMIT 1 )",
                                String.class)
                            .as("last_status"))
                    .from(PIPELINE_EXECUTION)
                    .groupBy(PE_PIPELINE_ID)
                    .asTable("last_exec"))
            .on(P_ID.eq(field("last_exec.pid", Long.class)))
            .where(
                P_IS_ACTIVE.eq(true).and(field("last_exec.last_status", String.class).eq("FAILED")))
            .fetch();

    for (Record r : failedPipelines) {
      Long pipelineId = r.get(P_ID);
      String pipelineName = r.get(P_NAME);
      LocalDateTime lastFailedAt = r.get(field("last_exec.last_failed_at", LocalDateTime.class));
      LocalDateTime lastSuccessAt = r.get(field("last_exec.last_success_at", LocalDateTime.class));

      // CRITICAL: failing for 2h+ (last success was >2h ago or never)
      boolean isCritical = lastSuccessAt == null || lastSuccessAt.isBefore(twoHoursAgo);
      String severity = isCritical ? "CRITICAL" : "WARNING";

      String description;
      if (lastSuccessAt != null) {
        description = "마지막 성공: " + formatTimeAgo(lastSuccessAt, now);
      } else {
        description = "성공 이력 없음";
      }

      items.add(
          new AttentionItemResponse(
              "PIPELINE_FAILED",
              severity,
              "파이프라인 '" + pipelineName + "' 실패",
              description,
              pipelineId,
              "PIPELINE",
              lastFailedAt != null ? lastFailedAt : now));
    }

    // 2. Stale source datasets: 24h+ no successful import
    // WARNING: 24h~72h, CRITICAL: 72h+
    var staleDatasets =
        dsl.select(D_ID, D_NAME, field("last_import.last_import_at", LocalDateTime.class))
            .from(DATASET)
            .leftJoin(
                dsl.select(
                        AL_RESOURCE_ID.as("dataset_rid"), max(AL_ACTION_TIME).as("last_import_at"))
                    .from(AUDIT_LOG)
                    .where(
                        AL_ACTION_TYPE
                            .eq("IMPORT")
                            .and(AL_RESOURCE.eq("dataset"))
                            .and(AL_RESULT.eq("SUCCESS")))
                    .groupBy(AL_RESOURCE_ID)
                    .asTable("last_import"))
            .on(D_ID.cast(String.class).eq(field("last_import.dataset_rid", String.class)))
            .where(
                D_DATASET_TYPE
                    .eq("SOURCE")
                    .and(
                        // no import at all, or last import older than 24h
                        field("last_import.last_import_at", LocalDateTime.class)
                            .isNull()
                            .or(
                                field("last_import.last_import_at", LocalDateTime.class)
                                    .lessOrEqual(twentyFourHoursAgo)))
                    // exclude brand-new datasets (created <24h ago with no import — not yet stale)
                    .and(
                        D_CREATED_AT
                            .lessOrEqual(twentyFourHoursAgo)
                            .or(
                                field("last_import.last_import_at", LocalDateTime.class)
                                    .isNotNull())))
            .fetch();

    for (Record r : staleDatasets) {
      Long datasetId = r.get(D_ID);
      String datasetName = r.get(D_NAME);
      LocalDateTime lastImportAt = r.get(field("last_import.last_import_at", LocalDateTime.class));

      boolean isCritical = lastImportAt == null || lastImportAt.isBefore(seventyTwoHoursAgo);
      String severity = isCritical ? "CRITICAL" : "WARNING";

      String description =
          lastImportAt != null ? "마지막 갱신: " + formatTimeAgo(lastImportAt, now) : "임포트 이력 없음";

      items.add(
          new AttentionItemResponse(
              "DATASET_STALE",
              severity,
              "데이터셋 '" + datasetName + "' 오래됨",
              description,
              datasetId,
              "DATASET",
              lastImportAt != null ? lastImportAt : now));
    }

    // 3. Failed imports within last 24h: WARNING
    var failedImports =
        dsl.select(AL_ID, D_ID, D_NAME, AL_ACTION_TIME)
            .from(AUDIT_LOG)
            .join(DATASET)
            .on(AL_RESOURCE_ID.cast(Long.class).eq(D_ID))
            .where(
                AL_ACTION_TYPE
                    .eq("IMPORT")
                    .and(AL_RESOURCE.eq("dataset"))
                    .and(AL_RESULT.eq("FAILURE"))
                    .and(AL_ACTION_TIME.greaterThan(twentyFourHoursAgo)))
            .orderBy(AL_ACTION_TIME.desc())
            .fetch();

    for (Record r : failedImports) {
      Long datasetId = r.get(D_ID);
      String datasetName = r.get(D_NAME);
      LocalDateTime occurredAt = r.get(AL_ACTION_TIME);

      items.add(
          new AttentionItemResponse(
              "IMPORT_FAILED",
              "WARNING",
              "데이터셋 '" + datasetName + "' 임포트 실패",
              "발생: " + formatTimeAgo(occurredAt, now),
              datasetId,
              "DATASET",
              occurredAt));
    }

    // Sort: CRITICAL first, then WARNING; within same severity, most recent first
    items.sort(
        Comparator.comparingInt((AttentionItemResponse a) -> severityOrder(a.severity()))
            .thenComparing(Comparator.comparing(AttentionItemResponse::occurredAt).reversed()));

    return items;
  }

  @Transactional(readOnly = true)
  public ActivityFeedResponse getActivityFeed(
      String typeFilter, String severityFilter, int page, int size) {

    // Build activity items from two sources:
    // 1. pipeline_execution — pipeline events
    // 2. audit_log — import events + dataset/dashboard creation events
    // Unresolved (failing) pipelines are pinned to top via isResolved=false + sort

    List<ActivityItem> allItems = new ArrayList<>();

    // ---- Pipeline executions ----
    var executions =
        dsl.select(
                PE_ID,
                PE_PIPELINE_ID,
                P_NAME,
                PE_STATUS,
                PE_CREATED_AT,
                PE_COMPLETED_AT,
                PE_STARTED_AT)
            .from(PIPELINE_EXECUTION)
            .join(PIPELINE)
            .on(PE_PIPELINE_ID.eq(P_ID))
            .orderBy(PE_CREATED_AT.desc())
            .limit(500) // reasonable cap for feed building
            .fetch();

    // To determine if a FAILED pipeline has since been resolved (later COMPLETED execution exists)
    // Build map: pipeline_id -> last execution status
    Map<Long, String> latestStatusByPipeline =
        executions.stream()
            .collect(
                Collectors.toMap(
                    r -> r.get(PE_PIPELINE_ID),
                    r -> r.get(PE_STATUS),
                    (existing, replacement) -> existing // keep first (most recent)
                    ));

    for (Record r : executions) {
      String status = r.get(PE_STATUS);
      String eventType =
          switch (status) {
            case "COMPLETED" -> "PIPELINE_COMPLETED";
            case "FAILED" -> "PIPELINE_FAILED";
            case "RUNNING" -> "PIPELINE_RUNNING";
            default -> "PIPELINE_" + status;
          };

      String severity =
          switch (status) {
            case "FAILED" -> "CRITICAL";
            case "RUNNING" -> "INFO";
            default -> "INFO";
          };

      Long pipelineId = r.get(PE_PIPELINE_ID);
      String pipelineName = r.get(P_NAME);
      LocalDateTime occurredAt = r.get(PE_CREATED_AT);

      // isResolved: non-FAILED executions are always resolved;
      // FAILED is resolved only if the latest execution for that pipeline is COMPLETED
      boolean isResolved =
          !"FAILED".equals(status) || "COMPLETED".equals(latestStatusByPipeline.get(pipelineId));

      String description =
          buildPipelineDescription(status, r.get(PE_STARTED_AT), r.get(PE_COMPLETED_AT));

      allItems.add(
          new ActivityItem(
              r.get(PE_ID),
              eventType,
              "파이프라인 '" + pipelineName + "' " + statusLabel(status),
              description,
              severity,
              "PIPELINE",
              pipelineId,
              occurredAt,
              isResolved));
    }

    // ---- Audit log events (imports + dataset creation) ----
    var auditItems =
        dsl.select(
                AL_ID,
                AL_ACTION_TYPE,
                AL_RESOURCE,
                AL_RESOURCE_ID,
                AL_RESULT,
                AL_ACTION_TIME,
                AL_DESCRIPTION,
                D_NAME,
                D_ID)
            .from(AUDIT_LOG)
            .leftJoin(DATASET)
            .on(AL_RESOURCE.eq("dataset").and(AL_RESOURCE_ID.cast(Long.class).eq(D_ID)))
            .where(
                AL_ACTION_TYPE.in("IMPORT", "CREATE").and(AL_RESOURCE.in("dataset", "dashboard")))
            .orderBy(AL_ACTION_TIME.desc())
            .limit(500)
            .fetch();

    for (Record r : auditItems) {
      String actionType = r.get(AL_ACTION_TYPE);
      String resource = r.get(AL_RESOURCE);
      String result = r.get(AL_RESULT);
      LocalDateTime occurredAt = r.get(AL_ACTION_TIME);
      Long resourceId = r.get(AL_RESOURCE_ID) != null ? parseLongSafe(r.get(AL_RESOURCE_ID)) : null;
      String entityName = r.get(D_NAME) != null ? r.get(D_NAME) : resource + " #" + resourceId;

      String eventType;
      String severity;
      String title;
      String description;
      String entityType;

      if ("IMPORT".equals(actionType)) {
        boolean success = "SUCCESS".equals(result);
        eventType = success ? "IMPORT_COMPLETED" : "IMPORT_FAILED";
        severity = success ? "INFO" : "WARNING";
        title = "데이터셋 '" + entityName + "' 임포트 " + (success ? "완료" : "실패");
        description = r.get(AL_DESCRIPTION) != null ? r.get(AL_DESCRIPTION) : "";
        entityType = "DATASET";
      } else if ("CREATE".equals(actionType) && "dataset".equals(resource)) {
        eventType = "DATASET_CREATED";
        severity = "INFO";
        title = "데이터셋 '" + entityName + "' 생성됨";
        description = "";
        entityType = "DATASET";
      } else if ("CREATE".equals(actionType) && "dashboard".equals(resource)) {
        eventType = "DASHBOARD_CREATED";
        severity = "INFO";
        title = "대시보드 생성됨";
        description = "";
        entityType = "DASHBOARD";
      } else {
        continue;
      }

      allItems.add(
          new ActivityItem(
              r.get(AL_ID),
              eventType,
              title,
              description,
              severity,
              entityType,
              resourceId,
              occurredAt,
              true // audit log entries are always considered resolved
              ));
    }

    // Apply filters
    List<ActivityItem> filtered =
        allItems.stream()
            .filter(
                item -> {
                  if (typeFilter != null
                      && !typeFilter.isBlank()
                      && !"ALL".equalsIgnoreCase(typeFilter)) {
                    String entityType = item.entityType();
                    boolean matches =
                        typeFilter.equalsIgnoreCase(entityType)
                            || (typeFilter.equalsIgnoreCase("PIPELINE")
                                && "PIPELINE".equals(entityType))
                            || (typeFilter.equalsIgnoreCase("DATASET")
                                && "DATASET".equals(entityType))
                            || (typeFilter.equalsIgnoreCase("IMPORT")
                                && item.eventType().startsWith("IMPORT"))
                            || (typeFilter.equalsIgnoreCase("DASHBOARD")
                                && "DASHBOARD".equals(entityType));
                    if (!matches) return false;
                  }
                  if (severityFilter != null
                      && !severityFilter.isBlank()
                      && !"ALL".equalsIgnoreCase(severityFilter)) {
                    if (!severityFilter.equalsIgnoreCase(item.severity())) return false;
                  }
                  return true;
                })
            .sorted(
                // Unresolved (isResolved=false) items first, then by occurredAt desc
                Comparator.comparingInt((ActivityItem i) -> i.isResolved() ? 1 : 0)
                    .thenComparing(Comparator.comparing(ActivityItem::occurredAt).reversed()))
            .collect(Collectors.toList());

    int totalCount = filtered.size();
    int fromIndex = page * size;
    int toIndex = Math.min(fromIndex + size, totalCount);

    List<ActivityItem> pageItems =
        fromIndex >= totalCount ? List.of() : filtered.subList(fromIndex, toIndex);

    return new ActivityFeedResponse(pageItems, totalCount, toIndex < totalCount);
  }

  // ---- Helpers ----

  private String formatTimeAgo(LocalDateTime time, LocalDateTime now) {
    long minutes = java.time.Duration.between(time, now).toMinutes();
    if (minutes < 60) return minutes + "분 전";
    long hours = minutes / 60;
    if (hours < 24) return hours + "시간 전";
    long days = hours / 24;
    return days + "일 전";
  }

  private int severityOrder(String severity) {
    return switch (severity) {
      case "CRITICAL" -> 0;
      case "WARNING" -> 1;
      default -> 2;
    };
  }

  private String statusLabel(String status) {
    return switch (status) {
      case "COMPLETED" -> "완료";
      case "FAILED" -> "실패";
      case "RUNNING" -> "실행중";
      case "PENDING" -> "대기";
      case "CANCELLED" -> "취소됨";
      default -> status;
    };
  }

  private String buildPipelineDescription(
      String status, LocalDateTime startedAt, LocalDateTime completedAt) {
    if ("COMPLETED".equals(status) && startedAt != null && completedAt != null) {
      long seconds = java.time.Duration.between(startedAt, completedAt).getSeconds();
      if (seconds < 60) return "실행 시간: " + seconds + "초";
      return "실행 시간: " + (seconds / 60) + "분 " + (seconds % 60) + "초";
    }
    return "";
  }

  private Long parseLongSafe(String s) {
    if (s == null) return null;
    try {
      return Long.parseLong(s);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
