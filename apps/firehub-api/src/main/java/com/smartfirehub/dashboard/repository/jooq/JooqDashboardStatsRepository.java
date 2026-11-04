package com.smartfirehub.dashboard.repository.jooq;

import static com.smartfirehub.audit.repository.jooq.JooqAuditLogRepository.AL_RESOURCE_ID_AS_LONG;
import static org.jooq.impl.DSL.*;

import com.smartfirehub.dashboard.dto.RecentExecutionResponse;
import com.smartfirehub.dashboard.repository.DashboardStatsRepository;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.securitylevel.repository.jooq.JooqDatasetVisibility;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

/**
 * DashboardStatsRepository 의 jOOQ(PostgreSQL) 구현 — WD-485.
 *
 * <p>홈 대시보드(통계·건강도·주의 항목·활동) 조회 쿼리 모음(WD-484).
 *
 * <p>{@code DashboardService} 의 직접 jOOQ 쿼리를 문장 그대로 옮긴 것이다. 행·집계 값만 돌려주고, 응답 DTO 조립·추이 채우기·정렬·포맷은
 * 서비스에 남는다.
 *
 * <p>클래스 단위 {@code @Transactional} 을 두지 않는다 — 서비스의 기존 트랜잭션 구간(readOnly 어노테이션·readOnly 템플릿) 안에서 불려 그
 * 트랜잭션(=RLS GUC)을 그대로 쓴다. 여기서 새 트랜잭션을 열면 Tibero 테넌트의 빈 테이블 판정을 PG 트랜잭션 밖으로 뺀 구간 나눔(WD-80)이 흐트러진다.
 *
 * <p>가시성(보안 등급)은 서비스가 {@code DatasetAccessGuard} 로 {@code DatasetVisibility} 를 만들어 넘기고, 여기서 {@code
 * JooqDatasetVisibility} 로 술어를 만든다. 시간 경계(24시간 전·추이 시작 시각)도 서비스가 예전과 같은 시점에 계산해 넘긴다 — 한 화면의 여러 집계가
 * 같은 기준 시각을 쓰게 하려는 것이다.
 */
@Repository
@RequiredArgsConstructor
public class JooqDashboardStatsRepository implements DashboardStatsRepository {

  private final DSLContext dsl;

  // 테이블·컬럼 상수 — 원래 서비스의 문자열 이름 그대로 옮겼다(생성 테이블로 바꾸지 않는다)
  private static final Table<?> DATASET = table(name("dataset"));
  private static final Field<Long> D_ID = field(name("dataset", "id"), Long.class);
  private static final Field<String> D_NAME = field(name("dataset", "name"), String.class);
  private static final Field<String> D_TABLE_NAME =
      field(name("dataset", "table_name"), String.class);
  // 저장 방식(TABLE/DOCUMENT)과 출처(SOURCE/DERIVED/TEMP)를 분리한 두 컬럼
  // 기존 dataset_type='SOURCE' ≡ storage_type='TABLE' AND origin_type='SOURCE' (문서 유입 방지)
  private static final Field<String> D_STORAGE_TYPE =
      field(name("dataset", "storage_type"), String.class);
  private static final Field<String> D_ORIGIN_TYPE =
      field(name("dataset", "origin_type"), String.class);
  private static final Field<LocalDateTime> D_CREATED_AT =
      field(name("dataset", "created_at"), LocalDateTime.class);

  private static final Table<?> PIPELINE = table(name("pipeline"));
  private static final Field<Long> P_ID = field(name("pipeline", "id"), Long.class);
  private static final Field<String> P_NAME = field(name("pipeline", "name"), String.class);
  private static final Field<Boolean> P_IS_ACTIVE =
      field(name("pipeline", "is_active"), Boolean.class);

  // audit_log 컬럼 상수
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

  // ---- 통계(getStats) ----

  /** 조회자가 볼 수 있는 데이터셋 수(통계 카드·건강도 공용). */
  @Override
  public long countDatasets(DatasetVisibility visibility) {
    Condition visible = JooqDatasetVisibility.condition(visibility);
    return dsl.selectCount().from(DATASET).where(visible).fetchOne(0, Long.class);
  }

  /** 조회자가 볼 수 있는 원천(TABLE·SOURCE) 데이터셋 수. */
  @Override
  public long countSourceDatasets(DatasetVisibility visibility) {
    Condition visible = JooqDatasetVisibility.condition(visibility);
    return dsl.selectCount()
        .from(DATASET)
        .where(D_STORAGE_TYPE.eq("TABLE").and(D_ORIGIN_TYPE.eq("SOURCE")))
        .and(visible)
        .fetchOne(0, Long.class);
  }

  /** 조회자가 볼 수 있는 파생(TABLE·DERIVED) 데이터셋 수. */
  @Override
  public long countDerivedDatasets(DatasetVisibility visibility) {
    Condition visible = JooqDatasetVisibility.condition(visibility);
    return dsl.selectCount()
        .from(DATASET)
        .where(D_STORAGE_TYPE.eq("TABLE").and(D_ORIGIN_TYPE.eq("DERIVED")))
        .and(visible)
        .fetchOne(0, Long.class);
  }

  /** 전체 파이프라인 수(통계 카드·건강도 공용). */
  @Override
  public long countPipelines() {
    return dsl.selectCount().from(PIPELINE).fetchOne(0, Long.class);
  }

  /** 활성 파이프라인 수(통계 카드용). */
  @Override
  public long countActivePipelines() {
    return dsl.selectCount().from(PIPELINE).where(P_IS_ACTIVE.eq(true)).fetchOne(0, Long.class);
  }

  /** 최근 임포트 상위 5건(audit_log) — 조회자가 볼 수 있는 데이터셋만. */
  @Override
  public List<RecentImportRow> findRecentImports(DatasetVisibility visibility) {
    Condition visible = JooqDatasetVisibility.condition(visibility);
    Field<String> metadataFileName = field("audit_log.metadata->>'fileName'", String.class);

    return dsl.select(AL_ID, D_NAME, metadataFileName, AL_RESULT, AL_ACTION_TIME)
        .from(AUDIT_LOG)
        .join(DATASET)
        .on(AL_RESOURCE_ID_AS_LONG.eq(D_ID))
        .where(AL_ACTION_TYPE.eq("IMPORT").and(AL_RESOURCE.eq("dataset")))
        // LIMIT 전에 SQL 에서 거른다 — 자바 후처리면 숨김 행이 상위 5개를 차지해 볼 수 있는 임포트가 밀려난다.
        .and(visible)
        .orderBy(AL_ACTION_TIME.desc())
        .limit(5)
        .fetch(
            r ->
                new RecentImportRow(
                    r.get(AL_ID),
                    r.get(D_NAME),
                    r.get(metadataFileName),
                    r.get(AL_RESULT),
                    r.get(AL_ACTION_TIME)));
  }

  /** 최근 파이프라인 실행 상위 5건. */
  @Override
  public List<RecentExecutionResponse> findRecentExecutions() {
    return dsl.select(PE_ID, P_NAME, PE_STATUS, PE_CREATED_AT)
        .from(PIPELINE_EXECUTION)
        .join(PIPELINE)
        .on(PE_PIPELINE_ID.eq(P_ID))
        .orderBy(PE_CREATED_AT.desc())
        .limit(5)
        .fetch(
            r ->
                new RecentExecutionResponse(
                    r.get(PE_ID), r.get(P_NAME), r.get(PE_STATUS), r.get(PE_CREATED_AT)));
  }

  // ---- 파이프라인 건강도 ----

  /** 비활성 파이프라인 수(is_active = false). */
  @Override
  public int countDisabledPipelines() {
    return dsl.selectCount().from(PIPELINE).where(P_IS_ACTIVE.eq(false)).fetchOne(0, int.class);
  }

  /** 활성 파이프라인별 마지막 실행 상태 목록(실행 이력이 없는 파이프라인은 빠진다). */
  @Override
  public List<String> findLatestStatusesOfActivePipelines() {
    // 활성 파이프라인마다 가장 최근 실행의 상태를 읽는다 — pipeline_execution 을 pipeline_id 로 묶어
    // MAX(id) 를 최근 실행으로 보는 부분 질의.
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

    return dsl.select(field("latest_exec.latest_status", String.class))
        .from(latestExecSubquery)
        .join(PIPELINE)
        .on(field("latest_exec.pipeline_id", Long.class).eq(P_ID))
        .where(P_IS_ACTIVE.eq(true))
        .fetch(r -> r.get(0, String.class));
  }

  /** 일자별 파이프라인 실행 건수(#669) — {@code trendFrom} 이후 생성된 실행만. */
  @Override
  public Map<LocalDate, Integer> countExecutionsByDay(LocalDateTime trendFrom) {
    Field<LocalDate> execDay = field("CAST({0} AS date)", LocalDate.class, PE_CREATED_AT);
    return dsl.select(execDay, count())
        .from(PIPELINE_EXECUTION)
        .where(PE_CREATED_AT.greaterOrEqual(trendFrom))
        .groupBy(execDay)
        .fetchMap(r -> r.get(execDay), r -> r.get(1, Integer.class));
  }

  // ---- 데이터셋 건강도 ----

  /** fresh: {@code freshThreshold} 이후 SUCCESS 임포트가 있는 원천 데이터셋 수. */
  @Override
  public int countFreshImportedDatasets(
      DatasetVisibility visibility, LocalDateTime freshThreshold) {
    Condition visible = JooqDatasetVisibility.condition(visibility);
    return dsl.fetchCount(
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
                    .and(D_STORAGE_TYPE.eq("TABLE").and(D_ORIGIN_TYPE.eq("SOURCE")))
                    .and(visible)));
  }

  /** {@code freshThreshold} 이후 생성됐고 임포트 이력이 없는 원천 데이터셋 수(새로 만든 것 = fresh). */
  @Override
  public int countNewSourceDatasetsWithoutImport(
      DatasetVisibility visibility, LocalDateTime freshThreshold) {
    Condition visible = JooqDatasetVisibility.condition(visibility);
    return dsl.fetchCount(
        dsl.selectDistinct(D_ID)
            .from(DATASET)
            .where(
                D_STORAGE_TYPE
                    .eq("TABLE")
                    .and(D_ORIGIN_TYPE.eq("SOURCE"))
                    .and(D_CREATED_AT.greaterThan(freshThreshold))
                    .and(visible)
                    .and(
                        notExists(
                            dsl.selectOne()
                                .from(AUDIT_LOG)
                                .where(
                                    AL_ACTION_TYPE
                                        .eq("IMPORT")
                                        .and(AL_RESOURCE.eq("dataset"))
                                        .and(AL_RESOURCE_ID.eq(D_ID.cast(String.class))))))));
  }

  /** stale: SUCCESS 임포트는 있으나 {@code staleThreshold} 이후에는 없는 원천 데이터셋 수. */
  @Override
  public int countStaleImportedDatasets(
      DatasetVisibility visibility, LocalDateTime staleThreshold) {
    Condition visible = JooqDatasetVisibility.condition(visibility);
    return dsl.fetchCount(
        dsl.selectDistinct(AL_RESOURCE_ID)
            .from(AUDIT_LOG)
            .join(DATASET)
            .on(AL_RESOURCE_ID.eq(D_ID.cast(String.class)))
            .where(
                AL_ACTION_TYPE
                    .eq("IMPORT")
                    .and(AL_RESOURCE.eq("dataset"))
                    .and(AL_RESULT.eq("SUCCESS"))
                    .and(D_STORAGE_TYPE.eq("TABLE").and(D_ORIGIN_TYPE.eq("SOURCE")))
                    .and(visible))
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
  }

  /** {@code staleThreshold} 이전에 생성됐고 임포트 이력이 전혀 없는 원천 데이터셋 수. */
  @Override
  public int countStaleSourceDatasetsWithoutImport(
      DatasetVisibility visibility, LocalDateTime staleThreshold) {
    Condition visible = JooqDatasetVisibility.condition(visibility);
    return dsl.fetchCount(
        dsl.selectDistinct(D_ID)
            .from(DATASET)
            .where(
                D_STORAGE_TYPE
                    .eq("TABLE")
                    .and(D_ORIGIN_TYPE.eq("SOURCE"))
                    .and(D_CREATED_AT.lessOrEqual(staleThreshold))
                    .and(visible)
                    .and(
                        notExists(
                            dsl.selectOne()
                                .from(AUDIT_LOG)
                                .where(
                                    AL_ACTION_TYPE
                                        .eq("IMPORT")
                                        .and(AL_RESOURCE.eq("dataset"))
                                        .and(AL_RESOURCE_ID.eq(D_ID.cast(String.class))))))));
  }

  /**
   * 조회자가 볼 수 있는 표(TABLE) 데이터셋의 테이블 이름. 비어 있는지는 호출자가 테넌트 저장소로 판정한다(WD-80).
   *
   * <p>DOCUMENT/FILE 은 물리 테이블이 없어 판정 대상이 아니다 — 예전 pg_stat_user_tables 조인도 이들을 (테이블이 없어) 뺐으므로 PG 결과는
   * 같고, Tibero 는 없는 테이블에 대한 원격 질의를 하지 않는다.
   */
  @Override
  public List<String> findTableDatasetNames(DatasetVisibility visibility) {
    Condition visible = JooqDatasetVisibility.condition(visibility);
    return dsl.select(D_TABLE_NAME)
        .from(DATASET)
        .where(D_STORAGE_TYPE.eq("TABLE").and(visible))
        .fetch(D_TABLE_NAME);
  }

  /**
   * 일자별 데이터셋 임포트 이력 건수(#669) — {@code trendFrom} 이후.
   *
   * <p>숨김 데이터셋의 이력은 추이에서 뺀다(볼 수 있는 것만 집계). 데이터셋 행이 없는 이력(삭제된 데이터셋)은 기존처럼 센다 — 삭제된 데이터셋은 이름·존재를 드러낼
   * 대상이 없다. 임포트(IMPORT)만 센다 — 데이터셋 정보·필드·행 편집도 resource=dataset 으로 감사에 남으므로(WD-463) 모두 세면 행 편집 한
   * 번마다 추이가 올라간다.
   */
  @Override
  public Map<LocalDate, Integer> countImportsByDay(
      DatasetVisibility visibility, LocalDateTime trendFrom) {
    Condition visible = JooqDatasetVisibility.condition(visibility);
    Field<LocalDate> auditDay = field("CAST({0} AS date)", LocalDate.class, AL_ACTION_TIME);
    return dsl.select(auditDay, count())
        .from(AUDIT_LOG)
        .leftJoin(DATASET)
        .on(AL_RESOURCE_ID_AS_LONG.eq(D_ID))
        .where(
            AL_RESOURCE
                .eq("dataset")
                .and(AL_ACTION_TYPE.eq("IMPORT"))
                .and(AL_ACTION_TIME.greaterOrEqual(trendFrom)))
        .and(D_ID.isNull().or(visible))
        .groupBy(auditDay)
        .fetchMap(r -> r.get(auditDay), r -> r.get(1, Integer.class));
  }

  // ---- 주의 항목(getAttentionItems) ----

  /** 마지막 실행이 FAILED 인 활성 파이프라인과 그 마지막 실패·성공 시각. */
  @Override
  public List<FailedPipelineRow> findFailedActivePipelines() {
    return dsl.select(
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
        .where(P_IS_ACTIVE.eq(true).and(field("last_exec.last_status", String.class).eq("FAILED")))
        .fetch(
            r ->
                new FailedPipelineRow(
                    r.get(P_ID),
                    r.get(P_NAME),
                    r.get(field("last_exec.last_failed_at", LocalDateTime.class)),
                    r.get(field("last_exec.last_success_at", LocalDateTime.class))));
  }

  /** {@code since} 이후 임포트 실패 — 조회자가 볼 수 있는 데이터셋만, 최신순. */
  @Override
  public List<FailedImportRow> findFailedImportsSince(
      DatasetVisibility visibility, LocalDateTime since) {
    Condition visible = JooqDatasetVisibility.condition(visibility);
    return dsl.select(AL_ID, D_ID, D_NAME, AL_ACTION_TIME)
        .from(AUDIT_LOG)
        .join(DATASET)
        .on(AL_RESOURCE_ID_AS_LONG.eq(D_ID))
        .where(
            AL_ACTION_TYPE
                .eq("IMPORT")
                .and(AL_RESOURCE.eq("dataset"))
                .and(AL_RESULT.eq("FAILURE"))
                .and(AL_ACTION_TIME.greaterThan(since))
                .and(visible))
        .orderBy(AL_ACTION_TIME.desc())
        .fetch(r -> new FailedImportRow(r.get(D_ID), r.get(D_NAME), r.get(AL_ACTION_TIME)));
  }

  // ---- 활동 피드(getActivityFeed) ----

  /** 피드용 최근 파이프라인 실행(최대 500건, 생성 시각 최신순 — 서비스의 "파이프라인별 마지막 상태" 판정이 이 순서에 기댄다). */
  @Override
  public List<ExecutionRow> findRecentExecutionsForFeed() {
    return dsl.select(
            PE_ID, PE_PIPELINE_ID, P_NAME, PE_STATUS, PE_CREATED_AT, PE_COMPLETED_AT, PE_STARTED_AT)
        .from(PIPELINE_EXECUTION)
        .join(PIPELINE)
        .on(PE_PIPELINE_ID.eq(P_ID))
        .orderBy(PE_CREATED_AT.desc())
        .limit(500) // 피드 조립용 상한
        .fetch(
            r ->
                new ExecutionRow(
                    r.get(PE_ID),
                    r.get(PE_PIPELINE_ID),
                    r.get(P_NAME),
                    r.get(PE_STATUS),
                    r.get(PE_CREATED_AT),
                    r.get(PE_COMPLETED_AT),
                    r.get(PE_STARTED_AT)));
  }

  /**
   * 피드용 데이터셋 감사 이벤트(임포트·생성, 최대 500건, 최신순).
   *
   * <p>분석 대시보드 생성(CREATE·dashboard) 행은 싣지 않는다(WD-464) — 서비스 주석 참고.
   */
  @Override
  public List<AuditActivityRow> findDatasetAuditActivities(DatasetVisibility visibility) {
    Condition visible = JooqDatasetVisibility.condition(visibility);
    return dsl.select(
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
        .on(AL_RESOURCE.eq("dataset").and(AL_RESOURCE_ID_AS_LONG.eq(D_ID)))
        .where(AL_ACTION_TYPE.in("IMPORT", "CREATE").and(AL_RESOURCE.eq("dataset")))
        // 보안 등급: 조회자가 볼 수 없는 데이터셋 유래 활동은 뺀다. ON 이 아니라 WHERE 에 둔다 — ON 에 두면 숨김 행이
        // "dataset #id" 로 남아 id 가 새고, 자바 후처리면 LIMIT 500 이 볼 수 있는 행을 먼저 잘라 낸다. 데이터셋 행이
        // 없는 이력(삭제된 데이터셋)은 D_ID 가 null 이라 기존처럼 남는다.
        .and(D_ID.isNull().or(visible))
        .orderBy(AL_ACTION_TIME.desc())
        .limit(500)
        .fetch(
            r ->
                new AuditActivityRow(
                    r.get(AL_ID),
                    r.get(AL_ACTION_TYPE),
                    r.get(AL_RESOURCE),
                    r.get(AL_RESOURCE_ID),
                    r.get(AL_RESULT),
                    r.get(AL_ACTION_TIME),
                    r.get(AL_DESCRIPTION),
                    r.get(D_NAME),
                    r.get(D_ID)));
  }
}
