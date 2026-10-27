package com.smartfirehub.proactive.repository;

import static org.jooq.impl.DSL.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.jooq.tables.SecurityLevel;
import com.smartfirehub.proactive.dto.ProactiveJobExecutionResponse;
import com.smartfirehub.proactive.dto.ProactiveJobExecutionSummaryResponse;
import com.smartfirehub.proactive.dto.ReportListItemResponse;
import com.smartfirehub.proactive.util.ProactiveTime;
import com.smartfirehub.securitylevel.dto.SecurityLevelSummary;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.SelectFieldOrAsterisk;
import org.jooq.Table;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 프로액티브 잡 실행 이력 저장소.
 *
 * <p>클래스 레벨 {@code @Transactional} 이 필요한 이유: V103 으로 {@code tenant_id} 가 생겼고 V104 에서 RLS 가 걸린다. 테넌트
 * 값은 트랜잭션-로컬 GUC 라 트랜잭션이 없으면 공급되지 않는다. 이 저장소의 주 호출자인 {@code ProactiveJobAsyncRunner.executeJob} 은
 * {@code @Async} 만 있고 트랜잭션이 없어, 배선이 없으면 실행 이력이 조용히 남지 않는다. 전파 REQUIRED 이므로 컨트롤러 경로의 동작은 불변이다.
 */
@Transactional
@Repository
@RequiredArgsConstructor
public class ProactiveJobExecutionRepository {

  private final DSLContext dsl;
  private final ObjectMapper objectMapper;

  private static final Table<?> PROACTIVE_JOB_EXECUTION = table(name("proactive_job_execution"));
  private static final Field<Long> PJE_ID =
      field(name("proactive_job_execution", "id"), Long.class);
  private static final Field<Long> PJE_JOB_ID =
      field(name("proactive_job_execution", "job_id"), Long.class);
  private static final Field<String> PJE_STATUS =
      field(name("proactive_job_execution", "status"), String.class);
  private static final Field<LocalDateTime> PJE_STARTED_AT =
      field(name("proactive_job_execution", "started_at"), LocalDateTime.class);
  private static final Field<LocalDateTime> PJE_COMPLETED_AT =
      field(name("proactive_job_execution", "completed_at"), LocalDateTime.class);
  private static final Field<String> PJE_ERROR_MESSAGE =
      field(name("proactive_job_execution", "error_message"), String.class);
  private static final Field<JSONB> PJE_RESULT =
      field(name("proactive_job_execution", "result"), JSONB.class);
  private static final Field<LocalDateTime> PJE_CREATED_AT =
      field(name("proactive_job_execution", "created_at"), LocalDateTime.class);
  private static final Field<LocalDateTime> PJE_HEARTBEAT_AT =
      field(name("proactive_job_execution", "heartbeat_at"), LocalDateTime.class);
  private static final Field<Long> PJE_TENANT_ID =
      field(name("proactive_job_execution", "tenant_id"), Long.class);

  /**
   * 아직 닫히지 않은 실행(PENDING·RUNNING) 조건. 조건부 상태 갱신·하트비트·정리 조회가 같은 「활성」 정의를 쓰게 한 곳에 둔다 — PJE_STATUS 뒤에
   * 선언해야 클래스 초기화 때 null 이 아니다.
   */
  private static final Condition ACTIVE = PJE_STATUS.in("PENDING", "RUNNING");

  /** 실제 전달된 채널 목록 (쉼표 구분 문자열, 예: "CHAT,EMAIL") */
  private static final Field<String> PJE_DELIVERED_CHANNELS =
      field(name("proactive_job_execution", "delivered_channels"), String.class);

  /** 리포트 등급(WD-198) — 실행 동안 읽은 데이터셋의 최고 등급. null = 등급 데이터를 읽지 않음·이 컬럼 이전의 리포트. */
  private static final Field<Long> PJE_SECURITY_LEVEL_ID =
      field(name("proactive_job_execution", "security_level_id"), Long.class);

  /**
   * 응답 배지·열람 판정용 등급 조인(LEFT JOIN — 등급 없는 실행도 나온다). 별칭을 두는 이유: 같은 쿼리의 다른 조인과 이름이 겹치지 않게 한다. 등급
   * 이름·순서(rank)는 지금 값을 읽는다 — 순서 변경 뒤에도 데이터셋 판정과 같은 기준으로 비교된다.
   */
  private static final SecurityLevel PJE_SL =
      com.smartfirehub.jooq.Tables.SECURITY_LEVEL.as("pje_sl");

  // 리포트 목록 조회용 — 잡 소유자 검증과 잡 이름 표기를 위해 proactive_job 을 조인한다
  private static final Table<?> PROACTIVE_JOB = table(name("proactive_job"));
  private static final Field<Long> PJ_ID = field(name("proactive_job", "id"), Long.class);
  private static final Field<Long> PJ_USER_ID = field(name("proactive_job", "user_id"), Long.class);
  private static final Field<String> PJ_NAME = field(name("proactive_job", "name"), String.class);

  public Long create(Long jobId) {
    return dsl.insertInto(PROACTIVE_JOB_EXECUTION)
        .set(PJE_JOB_ID, jobId)
        .set(PJE_STATUS, "PENDING")
        // 생성과 등록 사이 정리기가 돌아도 새 실행을 고아로 보지 않도록 즉시 하트비트를 남긴다.
        .set(PJE_HEARTBEAT_AT, ProactiveTime.nowUtc())
        .returning(PJE_ID)
        .fetchOne(r -> r.get(PJE_ID));
  }

  public boolean updateStatus(
      Long id, String status, LocalDateTime startedAt, LocalDateTime completedAt) {
    var query = dsl.update(PROACTIVE_JOB_EXECUTION).set(PJE_STATUS, status);
    if (startedAt != null) query = query.set(PJE_STARTED_AT, startedAt);
    if (completedAt != null) query = query.set(PJE_COMPLETED_AT, completedAt);
    // 생성 직후 러너가 멈춘 사이 정리기가 닫았다면 RUNNING 으로 되살리지 않는다.
    return query.where(PJE_ID.eq(id)).and(ACTIVE).execute() == 1;
  }

  public boolean updateResult(
      Long id, String status, Map<String, Object> result, LocalDateTime completedAt) {
    try {
      String resultJson = result != null ? objectMapper.writeValueAsString(result) : null;
      var query =
          dsl.update(PROACTIVE_JOB_EXECUTION)
              .set(PJE_STATUS, status)
              .set(PJE_COMPLETED_AT, completedAt);
      if (resultJson != null) query = query.set(PJE_RESULT, JSONB.valueOf(resultJson));
      // 정리기가 먼저 FAILED 로 닫은 실행의 결과와 상태를 되살리지 않는다.
      return query.where(PJE_ID.eq(id)).and(ACTIVE).execute() == 1;
    } catch (Exception e) {
      throw new RuntimeException("Failed to serialize result", e);
    }
  }

  public boolean updateError(Long id, String errorMessage) {
    return dsl.update(PROACTIVE_JOB_EXECUTION)
            .set(PJE_STATUS, "FAILED")
            .set(PJE_ERROR_MESSAGE, errorMessage)
            .set(PJE_COMPLETED_AT, ProactiveTime.nowUtc())
            .where(PJE_ID.eq(id))
            .and(ACTIVE)
            .execute()
        == 1;
  }

  /** 등록된 활성 실행만 하트비트를 갱신한다. RLS 때문에 호출자는 테넌트 컨텍스트 안에 있어야 한다. */
  public int touchHeartbeat(Collection<Long> executionIds, LocalDateTime now) {
    if (executionIds == null || executionIds.isEmpty()) return 0;
    return dsl.update(PROACTIVE_JOB_EXECUTION)
        .set(PJE_HEARTBEAT_AT, now)
        .where(PJE_ID.in(executionIds))
        .and(ACTIVE)
        .execute();
  }

  /**
   * 활성(PENDING·RUNNING) 실행이 있는 테넌트 id 목록(WD-258) — 정리기가 매분 모든 테넌트를 돌지 않고 이 목록만 돈다. V149 의 SECURITY
   * DEFINER 함수({@code outbox_tenant_ids} 와 같은 모양)라 테넌트 컨텍스트 없이도 RLS 를 넘어 정수 목록만 받는다. 실제 정리는 호출자가
   * 테넌트마다 컨텍스트를 연 뒤 RLS 아래서 한다. 실행 표에서 유도하므로 정지된 워크스페이스의 고아 실행도 빠지지 않는다.
   *
   * <p>반환은 완전히 구체화된 리스트다 — 호출자({@code sweep}, 트랜잭션 없음)가 이 메서드를 마친 뒤 테넌트별로 새 트랜잭션을 열어야 각자의 GUC 가
   * 들어간다.
   */
  public List<Long> tenantIdsWithActiveExecutions() {
    return dsl.fetch("select * from proactive_active_execution_tenant_ids()").into(Long.class);
  }

  /** 오래된 활성 실행 ID만 조회한다. 완료·실패 행과 다른 테넌트 행은 제외한다. */
  public List<Long> findStaleExecutions(long tenantId, LocalDateTime threshold) {
    return dsl.select(PJE_ID)
        .from(PROACTIVE_JOB_EXECUTION)
        .where(PJE_TENANT_ID.eq(tenantId))
        .and(ACTIVE)
        .and(coalesce(PJE_HEARTBEAT_AT, PJE_STARTED_AT, PJE_CREATED_AT).lt(threshold))
        .fetch(PJE_ID);
  }

  public List<ProactiveJobExecutionResponse> findByJobId(Long jobId, int limit, int offset) {
    return dsl.select(
            withLevel(
                PJE_ID,
                PJE_JOB_ID,
                PJE_STATUS,
                PJE_STARTED_AT,
                PJE_COMPLETED_AT,
                PJE_ERROR_MESSAGE,
                PJE_RESULT,
                PJE_DELIVERED_CHANNELS,
                PJE_CREATED_AT))
        .from(PROACTIVE_JOB_EXECUTION)
        .leftJoin(PJE_SL)
        .on(PJE_SL.ID.eq(PJE_SECURITY_LEVEL_ID))
        .where(PJE_JOB_ID.eq(jobId))
        .orderBy(PJE_ID.desc())
        .limit(limit)
        .offset(offset)
        .fetch(r -> toResponse(r));
  }

  /**
   * 실행 이력 목록(경량 뷰) 조회 — 컨트롤러 목록 endpoint 전용 (#604).
   *
   * <p>{@link #findByJobId}와 달리 {@code result}(리포트 본문) 컬럼 자체를 SELECT하지 않는다. 목록은 실행 건수만큼 반복되므로 본문을
   * 포함하면 응답이 수만 자에 달해 MCP 도구 결과 토큰 한도를 초과할 수 있다. 본문이 필요하면 단건 조회({@link #findById})를 사용해야 한다.
   */
  public List<ProactiveJobExecutionSummaryResponse> findSummariesByJobId(
      Long jobId, int limit, int offset) {
    return dsl.select(
            withLevel(
                PJE_ID,
                PJE_JOB_ID,
                PJE_STATUS,
                PJE_STARTED_AT,
                PJE_COMPLETED_AT,
                PJE_ERROR_MESSAGE,
                PJE_DELIVERED_CHANNELS,
                PJE_CREATED_AT))
        .from(PROACTIVE_JOB_EXECUTION)
        .leftJoin(PJE_SL)
        .on(PJE_SL.ID.eq(PJE_SECURITY_LEVEL_ID))
        .where(PJE_JOB_ID.eq(jobId))
        .orderBy(PJE_ID.desc())
        .limit(limit)
        .offset(offset)
        .fetch(r -> toSummaryResponse(r));
  }

  /**
   * 잡마다 가장 최근 실행 한 건을 읽는다 — 잡 목록·상세의 「마지막 실행 상태」용 (WD-134).
   *
   * <p>예전에는 잡 응답의 {@code lastExecution} 을 채우는 곳이 없어 늘 null 이었고, 예약 실행이 계속 실패해도 목록·상세 어디에도 실패가 드러나지
   * 않았다. 목록은 잡 수만큼 반복되므로 {@code result}(리포트 본문)는 읽지 않는다({@link #findSummariesByJobId} 와 같은 이유) —
   * 돌려주는 응답의 {@code result} 는 항상 null 이다.
   *
   * @return 잡 ID → 최근 실행. 실행 이력이 없는 잡은 키가 없다
   */
  public Map<Long, ProactiveJobExecutionResponse> findLatestByJobIds(Collection<Long> jobIds) {
    Map<Long, ProactiveJobExecutionResponse> latest = new HashMap<>();
    if (jobIds.isEmpty()) return latest;
    dsl.select(
            withLevel(
                PJE_ID,
                PJE_JOB_ID,
                PJE_STATUS,
                PJE_STARTED_AT,
                PJE_COMPLETED_AT,
                PJE_ERROR_MESSAGE,
                PJE_DELIVERED_CHANNELS,
                PJE_CREATED_AT))
        .distinctOn(PJE_JOB_ID)
        .from(PROACTIVE_JOB_EXECUTION)
        .leftJoin(PJE_SL)
        .on(PJE_SL.ID.eq(PJE_SECURITY_LEVEL_ID))
        .where(PJE_JOB_ID.in(jobIds))
        // 인덱스 (job_id, created_at DESC) 순서를 따른다 — 같은 시각이면 나중 행(id 큰 쪽)
        .orderBy(PJE_JOB_ID, PJE_CREATED_AT.desc(), PJE_ID.desc())
        .forEach(
            r ->
                latest.put(
                    r.get(PJE_JOB_ID),
                    new ProactiveJobExecutionResponse(
                        r.get(PJE_ID),
                        r.get(PJE_JOB_ID),
                        r.get(PJE_STATUS),
                        r.get(PJE_STARTED_AT),
                        r.get(PJE_COMPLETED_AT),
                        r.get(PJE_ERROR_MESSAGE),
                        null,
                        parseChannels(r.get(PJE_DELIVERED_CHANNELS)),
                        r.get(PJE_CREATED_AT),
                        levelOf(r))));
    return latest;
  }

  public Optional<ProactiveJobExecutionResponse> findById(Long id) {
    return dsl.select(
            withLevel(
                PJE_ID,
                PJE_JOB_ID,
                PJE_STATUS,
                PJE_STARTED_AT,
                PJE_COMPLETED_AT,
                PJE_ERROR_MESSAGE,
                PJE_RESULT,
                PJE_DELIVERED_CHANNELS,
                PJE_CREATED_AT))
        .from(PROACTIVE_JOB_EXECUTION)
        .leftJoin(PJE_SL)
        .on(PJE_SL.ID.eq(PJE_SECURITY_LEVEL_ID))
        .where(PJE_ID.eq(id))
        .fetchOptional(r -> toResponse(r));
  }

  /** 실행 완료 후 실제 전달된 채널 목록을 DB에 저장한다. 쉼표 구분 문자열로 저장 (예: "CHAT,EMAIL"). */
  public void updateDeliveredChannels(Long executionId, List<String> channels) {
    String value = (channels != null && !channels.isEmpty()) ? String.join(",", channels) : null;
    dsl.update(PROACTIVE_JOB_EXECUTION)
        .set(PJE_DELIVERED_CHANNELS, value)
        .where(PJE_ID.eq(executionId))
        .execute();
  }

  /**
   * 사용자가 소유한 스마트 작업들이 생성한 리포트를 잡 횡단으로 조회한다.
   *
   * <p>"리포트"는 COMPLETED 이면서 result.htmlContent 가 실제로 있는 실행만을 뜻한다. htmlContent 가 없으면 뷰어가 404를 내므로
   * 목록에 넣으면 빈 화면으로 이어진다.
   *
   * <p>본문(htmlContent)은 수십 KB이므로 SELECT 하지 않고 WHERE 절의 존재 판정에만 사용한다. 잡 스코핑이 없는 엔드포인트이므로 user_id 조건이
   * 유일한 소유권 방어선이다.
   */
  public List<ReportListItemResponse> findReportsByUserId(Long userId, int limit, int offset) {
    // result ->> 'htmlContent' / 'title' / 'summary' — JSONB 텍스트 추출
    Field<String> htmlContent = field("{0} ->> 'htmlContent'", String.class, PJE_RESULT);
    Field<String> title = field("{0} ->> 'title'", String.class, PJE_RESULT);
    Field<String> summary = field("{0} ->> 'summary'", String.class, PJE_RESULT);

    // ProactiveResult.effectiveTitle(jobName) 과 동일한 규칙을 SQL 로 재현
    Field<String> effectiveTitle = coalesce(nullif(trim(title), val("")), PJ_NAME);

    return dsl.select(
            withLevel(PJE_ID, PJE_JOB_ID, PJ_NAME, effectiveTitle, summary, PJE_COMPLETED_AT))
        .from(PROACTIVE_JOB_EXECUTION)
        .join(PROACTIVE_JOB)
        .on(PJE_JOB_ID.eq(PJ_ID))
        .leftJoin(PJE_SL)
        .on(PJE_SL.ID.eq(PJE_SECURITY_LEVEL_ID))
        .where(PJ_USER_ID.eq(userId))
        .and(PJE_STATUS.eq("COMPLETED"))
        .and(htmlContent.isNotNull())
        .and(htmlContent.ne(""))
        .orderBy(PJE_COMPLETED_AT.desc(), PJE_ID.desc())
        .limit(limit)
        .offset(offset)
        .fetch(
            r ->
                new ReportListItemResponse(
                    r.get(PJE_ID),
                    r.get(PJE_JOB_ID),
                    r.get(PJ_NAME),
                    r.get(effectiveTitle),
                    r.get(summary),
                    r.get(PJE_COMPLETED_AT),
                    levelOf(r)));
  }

  private ProactiveJobExecutionResponse toResponse(org.jooq.Record r) {
    try {
      JSONB resultJsonb = r.get(PJE_RESULT);
      Map<String, Object> result =
          resultJsonb != null
              ? objectMapper.readValue(resultJsonb.data(), new TypeReference<>() {})
              : null;
      List<String> channels = parseChannels(r.get(PJE_DELIVERED_CHANNELS));
      return new ProactiveJobExecutionResponse(
          r.get(PJE_ID),
          r.get(PJE_JOB_ID),
          r.get(PJE_STATUS),
          r.get(PJE_STARTED_AT),
          r.get(PJE_COMPLETED_AT),
          r.get(PJE_ERROR_MESSAGE),
          result,
          channels,
          r.get(PJE_CREATED_AT),
          levelOf(r));
    } catch (Exception e) {
      throw new RuntimeException("Failed to deserialize result", e);
    }
  }

  private ProactiveJobExecutionSummaryResponse toSummaryResponse(org.jooq.Record r) {
    List<String> channels = parseChannels(r.get(PJE_DELIVERED_CHANNELS));
    return new ProactiveJobExecutionSummaryResponse(
        r.get(PJE_ID),
        r.get(PJE_JOB_ID),
        r.get(PJE_STATUS),
        r.get(PJE_STARTED_AT),
        r.get(PJE_COMPLETED_AT),
        r.get(PJE_ERROR_MESSAGE),
        channels,
        r.get(PJE_CREATED_AT),
        levelOf(r));
  }

  /** 기본 select 목록에 등급 조인 컬럼을 덧붙인다. */
  private static List<SelectFieldOrAsterisk> withLevel(SelectFieldOrAsterisk... base) {
    List<SelectFieldOrAsterisk> all = new ArrayList<>(List.of(base));
    all.addAll(List.of(PJE_SL.fields()));
    return all;
  }

  /** 조인된 등급 → 응답 요약. 등급이 없으면 null. */
  private static SecurityLevelSummary levelOf(org.jooq.Record r) {
    if (r.get(PJE_SL.ID) == null) {
      return null;
    }
    return SecurityLevelSummary.of(SecurityLevelRepository.toPolicy(r.into(PJE_SL)));
  }

  /**
   * 리포트 등급을 올린다(WD-198) — 지금 등급이 없거나 rank 가 더 낮을 때만 바꾼다(「가장 높은 등급」만 남는다). 한 문장 UPDATE 라 동시 기록끼리 낮은
   * 값으로 덮어쓰지 않는다.
   *
   * <p>헤더 위조 방지 조건(대행 요청 경로, {@code runningSince != null}): 실행의 작업 작성자가 {@code ownerUserId} 이고, 요청
   * 시작 시점에 진행 중이었던 실행(지금 RUNNING 이거나 그 이후에 끝남)일 때만 갱신한다. 조건에 안 맞으면 0행 — 호출부는 조용히 무시한다. 실행기 내부
   * 경로({@code runningSince == null})도 작성자 조건은 그대로 건다.
   *
   * <p>다른 테넌트의 등급 id 는 RLS 로 security_level 이 보이지 않아 rank 비교가 NULL → 갱신되지 않는다(복합 FK 도 거부한다).
   *
   * @return 갱신된 행 수(0 또는 1)
   */
  public int raiseSecurityLevel(
      long executionId, long ownerUserId, long levelId, LocalDateTime runningSince) {
    Field<Integer> newRank =
        field(
            select(com.smartfirehub.jooq.Tables.SECURITY_LEVEL.RANK)
                .from(com.smartfirehub.jooq.Tables.SECURITY_LEVEL)
                .where(com.smartfirehub.jooq.Tables.SECURITY_LEVEL.ID.eq(levelId)));
    var cur = com.smartfirehub.jooq.Tables.SECURITY_LEVEL.as("cur_sl");
    Field<Integer> curRank =
        field(select(cur.RANK).from(cur).where(cur.ID.eq(PJE_SECURITY_LEVEL_ID)));
    org.jooq.Condition running =
        runningSince == null
            ? noCondition()
            : PJE_STATUS.eq("RUNNING").or(PJE_COMPLETED_AT.ge(runningSince));
    return dsl.update(PROACTIVE_JOB_EXECUTION)
        .set(PJE_SECURITY_LEVEL_ID, levelId)
        .where(PJE_ID.eq(executionId))
        .and(newRank.isNotNull())
        .and(PJE_SECURITY_LEVEL_ID.isNull().or(curRank.lt(newRank)))
        .and(PJE_JOB_ID.in(select(PJ_ID).from(PROACTIVE_JOB).where(PJ_USER_ID.eq(ownerUserId))))
        .and(running)
        .execute();
  }

  /** 데이터셋들의 등급 id(중복 제거) — 리포트 등급 기록이 결과에 실린 데이터셋 id 로부터 등급을 구할 때 쓴다(WD-198). RLS 로 현재 테넌트만 보인다. */
  public Set<Long> findDatasetLevelIds(Collection<Long> datasetIds) {
    return dsl.selectDistinct(com.smartfirehub.jooq.Tables.DATASET.SECURITY_LEVEL_ID)
        .from(com.smartfirehub.jooq.Tables.DATASET)
        .where(com.smartfirehub.jooq.Tables.DATASET.ID.in(datasetIds))
        .fetchSet(com.smartfirehub.jooq.Tables.DATASET.SECURITY_LEVEL_ID);
  }

  /** 등급별 리포트(실행) 수 — 등급 삭제의 「사용 중」 판정(WD-198). */
  public Map<Long, Long> countByLevel() {
    return dsl.select(PJE_SECURITY_LEVEL_ID, count())
        .from(PROACTIVE_JOB_EXECUTION)
        .where(PJE_SECURITY_LEVEL_ID.isNotNull())
        .groupBy(PJE_SECURITY_LEVEL_ID)
        .fetchMap(PJE_SECURITY_LEVEL_ID, r -> r.get(1, Long.class));
  }

  /** 등급 삭제 시 그 등급의 리포트를 옮길 등급으로 옮긴다(WD-198) — 복합 FK 위반을 막고 리포트가 등급 없음(열림)으로 풀리지 않게. */
  public int moveLevel(long fromLevelId, long toLevelId) {
    return dsl.update(PROACTIVE_JOB_EXECUTION)
        .set(PJE_SECURITY_LEVEL_ID, toLevelId)
        .where(PJE_SECURITY_LEVEL_ID.eq(fromLevelId))
        .execute();
  }

  /** deliveredChannels: 쉼표로 구분해 저장한 문자열을 리스트로 변환한다(비었으면 null). */
  private static List<String> parseChannels(String channelsStr) {
    return (channelsStr != null && !channelsStr.isBlank()) ? List.of(channelsStr.split(",")) : null;
  }
}
