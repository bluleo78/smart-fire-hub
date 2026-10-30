package com.smartfirehub.proactive.repository;

import static org.jooq.impl.DSL.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.util.ProactiveTime;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record1;
import org.jooq.Table;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 프로액티브 잡 저장소.
 *
 * <p>클래스 레벨 {@code @Transactional} 이 필요한 이유: V103 으로 {@code proactive_job} 에 {@code tenant_id} 가
 * 생겼고 V104 에서 RLS 정책이 걸린다. 테넌트 값은 <b>트랜잭션-로컬 GUC</b> ({@code app.tenant_id}) 이고 그 GUC 는 {@code
 * TenantAwareTransactionManager.doBegin} 에서만 주입되므로, 트랜잭션 없이 도는 배경 경로(부팅 시 스케줄 재등록
 * {@code @PostConstruct}, 크론 발화 콜백, {@code @Async} 실행기)에서는 INSERT 가 NOT NULL 위반으로 깨지고 SELECT 는 예외도
 * 로그도 없이 0행이 된다. 전파는 REQUIRED 라 이미 트랜잭션 안인 컨트롤러 경로의 동작은 불변이다. 선례: {@code ReportTemplateRepository}.
 */
@Transactional
@Repository
@RequiredArgsConstructor
public class ProactiveJobRepository {

  private final DSLContext dsl;
  private final ObjectMapper objectMapper;

  private static final Table<?> PROACTIVE_JOB = table(name("proactive_job"));
  private static final Field<Long> PJ_ID = field(name("proactive_job", "id"), Long.class);
  private static final Field<Long> PJ_USER_ID = field(name("proactive_job", "user_id"), Long.class);
  private static final Field<Long> PJ_TEMPLATE_ID =
      field(name("proactive_job", "template_id"), Long.class);
  private static final Field<String> PJ_NAME = field(name("proactive_job", "name"), String.class);
  private static final Field<String> PJ_PROMPT =
      field(name("proactive_job", "prompt"), String.class);
  private static final Field<String> PJ_CRON_EXPRESSION =
      field(name("proactive_job", "cron_expression"), String.class);
  private static final Field<String> PJ_TIMEZONE =
      field(name("proactive_job", "timezone"), String.class);
  private static final Field<Boolean> PJ_ENABLED =
      field(name("proactive_job", "enabled"), Boolean.class);
  // 트리거 유형(SCHEDULE/ANOMALY/BOTH). V45에서 컬럼은 추가됐지만 이 리포지토리는 필드 상수 자체가
  // 없어 insert/update/select 어디서도 다루지 않았고, 그 결과 DB 컬럼 기본값(SCHEDULE)만 저장되는
  // 회귀가 있었다 (#655) — ANOMALY/BOTH로 생성해도 MetricPollerService가 절대 조회하지 못함.
  private static final Field<String> PJ_TRIGGER_TYPE =
      field(name("proactive_job", "trigger_type"), String.class);
  private static final Field<JSONB> PJ_CONFIG = field(name("proactive_job", "config"), JSONB.class);
  private static final Field<LocalDateTime> PJ_LAST_EXECUTED_AT =
      field(name("proactive_job", "last_executed_at"), LocalDateTime.class);
  private static final Field<LocalDateTime> PJ_NEXT_EXECUTE_AT =
      field(name("proactive_job", "next_execute_at"), LocalDateTime.class);
  private static final Field<LocalDateTime> PJ_CREATED_AT =
      field(name("proactive_job", "created_at"), LocalDateTime.class);
  private static final Field<LocalDateTime> PJ_UPDATED_AT =
      field(name("proactive_job", "updated_at"), LocalDateTime.class);

  private static final Field<Long> PJ_TENANT_ID =
      field(name("proactive_job", "tenant_id"), Long.class);

  private static final Table<?> REPORT_TEMPLATE = table(name("report_template"));
  private static final Field<Long> RT_ID = field(name("report_template", "id"), Long.class);
  private static final Field<String> RT_NAME = field(name("report_template", "name"), String.class);

  public List<ProactiveJobResponse> findByUserId(Long userId) {
    return dsl.select(
            PJ_ID,
            PJ_USER_ID,
            PJ_TEMPLATE_ID,
            RT_NAME,
            PJ_NAME,
            PJ_PROMPT,
            PJ_CRON_EXPRESSION,
            PJ_TIMEZONE,
            PJ_ENABLED,
            PJ_TRIGGER_TYPE,
            PJ_CONFIG,
            PJ_LAST_EXECUTED_AT,
            PJ_NEXT_EXECUTE_AT,
            PJ_CREATED_AT,
            PJ_UPDATED_AT)
        .from(PROACTIVE_JOB)
        .leftJoin(REPORT_TEMPLATE)
        .on(PJ_TEMPLATE_ID.eq(RT_ID))
        .where(PJ_USER_ID.eq(userId))
        .orderBy(PJ_ID.desc())
        .fetch(r -> toResponse(r, null));
  }

  public Optional<ProactiveJobResponse> findById(Long id, Long userId) {
    return dsl.select(
            PJ_ID,
            PJ_USER_ID,
            PJ_TEMPLATE_ID,
            RT_NAME,
            PJ_NAME,
            PJ_PROMPT,
            PJ_CRON_EXPRESSION,
            PJ_TIMEZONE,
            PJ_ENABLED,
            PJ_TRIGGER_TYPE,
            PJ_CONFIG,
            PJ_LAST_EXECUTED_AT,
            PJ_NEXT_EXECUTE_AT,
            PJ_CREATED_AT,
            PJ_UPDATED_AT)
        .from(PROACTIVE_JOB)
        .leftJoin(REPORT_TEMPLATE)
        .on(PJ_TEMPLATE_ID.eq(RT_ID))
        .where(PJ_ID.eq(id).and(PJ_USER_ID.eq(userId)))
        .fetchOptional(r -> toResponse(r, null));
  }

  public Optional<ProactiveJobResponse> findById(Long id) {
    return dsl.select(
            PJ_ID,
            PJ_USER_ID,
            PJ_TEMPLATE_ID,
            RT_NAME,
            PJ_NAME,
            PJ_PROMPT,
            PJ_CRON_EXPRESSION,
            PJ_TIMEZONE,
            PJ_ENABLED,
            PJ_TRIGGER_TYPE,
            PJ_CONFIG,
            PJ_LAST_EXECUTED_AT,
            PJ_NEXT_EXECUTE_AT,
            PJ_CREATED_AT,
            PJ_UPDATED_AT)
        .from(PROACTIVE_JOB)
        .leftJoin(REPORT_TEMPLATE)
        .on(PJ_TEMPLATE_ID.eq(RT_ID))
        .where(PJ_ID.eq(id))
        .fetchOptional(r -> toResponse(r, null));
  }

  /**
   * 지정한 테넌트의 활성 잡을 전부 읽는다 (부팅 시 크론 재등록 전용).
   *
   * <p><b>왜 RLS 에 맡기지 않고 {@code tenant_id} 술어를 명시하는가</b>: 호출자 ({@code
   * ProactiveJobSchedulerService.reloadAllSchedules})는 ACTIVE 테넌트를 순회하며 잡마다 발화 시점 테넌트를 캡처한다.
   * V104(정책) 이전에는 이 조회가 컨텍스트와 무관하게 전 테넌트 행을 돌려주므로, 술어가 없으면 <b>모든 잡이 마지막으로 순회된 테넌트를 캡처</b>해 남의 테넌트로
   * 발화한다. 정책이 켜진 뒤에는 같은 결과를 두 번 보장하는 방어적 중복이 된다.
   */
  public List<ProactiveJobResponse> findAllEnabled(long tenantId) {
    return dsl.select(
            PJ_ID,
            PJ_USER_ID,
            PJ_TEMPLATE_ID,
            RT_NAME,
            PJ_NAME,
            PJ_PROMPT,
            PJ_CRON_EXPRESSION,
            PJ_TIMEZONE,
            PJ_ENABLED,
            PJ_TRIGGER_TYPE,
            PJ_CONFIG,
            PJ_LAST_EXECUTED_AT,
            PJ_NEXT_EXECUTE_AT,
            PJ_CREATED_AT,
            PJ_UPDATED_AT)
        .from(PROACTIVE_JOB)
        .leftJoin(REPORT_TEMPLATE)
        .on(PJ_TEMPLATE_ID.eq(RT_ID))
        .where(PJ_ENABLED.isTrue())
        .and(PJ_TENANT_ID.eq(tenantId))
        .fetch(r -> toResponse(r, null));
  }

  /**
   * Proactive Job을 새로 생성한다.
   *
   * <p>{@code enabled}가 null이면 기본값 {@code true}를 적용한다. 호출자가 {@code false}를 명시하면 비활성 상태로 저장된다
   * (#220).
   */
  public Long create(
      Long userId,
      String name,
      String prompt,
      Long templateId,
      String cronExpression,
      String timezone,
      Boolean enabled,
      String triggerType,
      Map<String, Object> config) {
    try {
      String configJson = config != null ? objectMapper.writeValueAsString(config) : "{}";
      return dsl.insertInto(PROACTIVE_JOB)
          .set(PJ_USER_ID, userId)
          .set(PJ_NAME, name)
          .set(PJ_PROMPT, prompt)
          .set(PJ_TEMPLATE_ID, templateId)
          .set(PJ_CRON_EXPRESSION, cronExpression)
          .set(PJ_TIMEZONE, timezone != null ? timezone : "Asia/Seoul")
          .set(PJ_ENABLED, enabled != null ? enabled : true)
          // triggerType 미지정 시 DB 컬럼 기본값과 동일하게 SCHEDULE로 명시 저장 (#655)
          .set(PJ_TRIGGER_TYPE, triggerType != null ? triggerType : "SCHEDULE")
          .set(PJ_CONFIG, JSONB.valueOf(configJson))
          .returning(PJ_ID)
          .fetchOne(r -> r.get(PJ_ID));
    } catch (Exception e) {
      throw new RuntimeException("Failed to serialize config", e);
    }
  }

  /**
   * 수정 시각을 행 잠금(FOR UPDATE)과 함께 읽는다 — 편집 충돌(낙관적 잠금) 비교용(WD-364, 파이프라인 WD-353 과 같은 방식).
   *
   * <p>잠금 없이 읽고 비교하면 동시에 들어온 두 저장이 같은 값을 읽어 둘 다 통과한다. 잠금을 잡으면 나중 저장은 앞 저장이 커밋될 때까지 기다렸다가 바뀐 값을 읽어
   * 충돌로 거부된다. 소유자 확인을 마친 뒤에 부른다 — 먼저 부르면 남의 행에 대한 낡은 요청이 404 대신 409 를 받아 존재가 드러난다.
   *
   * <p>행이 없으면 빈 값, 행은 있는데 수정 시각이 비어 있으면(nullable 컬럼, V42) 값이 null 인 레코드다 — 둘을 구분해야 수정 시각이 빈 옛 행을
   * "없음"으로 잘못 거부하지 않는다.
   */
  public Optional<Record1<LocalDateTime>> lockUpdatedAtById(Long id) {
    return dsl.select(PJ_UPDATED_AT)
        .from(PROACTIVE_JOB)
        .where(PJ_ID.eq(id))
        .forUpdate()
        .fetchOptional();
  }

  /**
   * 작업을 고치고 새로 저장된 수정 시각을 돌려준다(WD-364).
   *
   * <p>Java 값이 아니라 DB 에 저장된 값(RETURNING)을 돌려준다 — PG timestamp 는 마이크로초로 반올림하므로 넣은 값을 그대로 돌려주면 다음 저장의
   * 기준 버전이 저장값과 어긋나 자기 저장과 충돌한다. 고친 행이 없으면(남의 작업) null.
   */
  public LocalDateTime update(
      Long id,
      Long userId,
      String name,
      String prompt,
      Long templateId,
      boolean clearTemplate,
      String cronExpression,
      String timezone,
      Boolean enabled,
      String triggerType,
      Map<String, Object> config) {
    try {
      var query = dsl.update(PROACTIVE_JOB).set(PJ_UPDATED_AT, ProactiveTime.nowUtc());
      if (name != null) query = query.set(PJ_NAME, name);
      if (prompt != null) query = query.set(PJ_PROMPT, prompt);
      if (templateId != null) {
        query = query.set(PJ_TEMPLATE_ID, templateId);
      } else if (clearTemplate) {
        // 화면에서 「기본 (선택 안 함)」으로 되돌린 명시적 요청(WD-136). templateId=null 만으로는 "미제공"과
        // 구분할 수 없어 예전에는 무시됐고, 저장 성공 토스트 뒤에도 옛 템플릿이 남았다.
        query = query.set(PJ_TEMPLATE_ID, (Long) null);
      }
      if (cronExpression != null) query = query.set(PJ_CRON_EXPRESSION, cronExpression);
      if (timezone != null) query = query.set(PJ_TIMEZONE, timezone);
      if (enabled != null) query = query.set(PJ_ENABLED, enabled);
      // triggerType이 null이면 기존 값을 유지한다(부분 수정 지원) — 회귀 원인이었던 필드이므로
      // 명시적으로 전달된 경우에만 갱신하도록 나머지 필드와 동일한 패턴을 따른다 (#655)
      if (triggerType != null) query = query.set(PJ_TRIGGER_TYPE, triggerType);
      if (config != null) {
        query = query.set(PJ_CONFIG, JSONB.valueOf(objectMapper.writeValueAsString(config)));
      }
      return query
          .where(PJ_ID.eq(id).and(PJ_USER_ID.eq(userId)))
          .returning(PJ_UPDATED_AT)
          .fetchOptional(PJ_UPDATED_AT)
          .orElse(null);
    } catch (Exception e) {
      throw new RuntimeException("Failed to serialize config", e);
    }
  }

  /**
   * 실행 기록(마지막 실행·다음 실행 예정)을 갱신한다.
   *
   * <p>{@code updated_at} 은 갱신하지 않는다(WD-364) — 실행은 사용자가 작업을 고친 것이 아니라 파생값을 남긴 것뿐이다({@link
   * #updateNextExecuteAt} 와 같은 원칙). 실행마다 수정 시각이 바뀌면 편집 화면을 연 사이 예약 실행·「지금 실행」이 한 번 돌았을 뿐인데, 아무도 작업을
   * 고치지 않았는데도 저장이 편집 충돌(409 EDIT_CONFLICT)로 거부된다. 목록의 "수정일"도 실행마다 흔들리지 않게 된다.
   */
  public void updateLastExecuted(
      Long id, LocalDateTime lastExecutedAt, LocalDateTime nextExecuteAt) {
    dsl.update(PROACTIVE_JOB)
        .set(PJ_LAST_EXECUTED_AT, lastExecutedAt)
        .set(PJ_NEXT_EXECUTE_AT, nextExecuteAt)
        .where(PJ_ID.eq(id))
        .execute();
  }

  /**
   * 다음 실행 예정 시각만 갱신한다 (#348).
   *
   * <p>스케줄 등록/해제 시점에 호출된다. {@code updateLastExecuted} 와 달리 실행 이력을 건드리지 않으므로, 실행되지 않은 잡에도 값을 채울 수
   * 있다. 스케줄이 해제되면 {@code null} 을 넣어 "다음 실행 없음"을 표현한다.
   *
   * <p>{@code updated_at} 은 갱신하지 않는다 — 사용자가 잡을 수정한 것이 아니라 스케줄러가 파생값을 다시 계산한 것뿐이라, 여기서 갱신하면 목록의
   * "수정일"이 재부팅마다 흔들린다.
   */
  public void updateNextExecuteAt(Long id, LocalDateTime nextExecuteAt) {
    dsl.update(PROACTIVE_JOB).set(PJ_NEXT_EXECUTE_AT, nextExecuteAt).where(PJ_ID.eq(id)).execute();
  }

  public void delete(Long id, Long userId) {
    dsl.deleteFrom(PROACTIVE_JOB).where(PJ_ID.eq(id).and(PJ_USER_ID.eq(userId))).execute();
  }

  // ── 관리자(proactive:admin) 경로(WD-192) ─────────────────────────────────────────
  // 요청 경로에서 user_id 조건 없이 도는 유일한 조회·변경들이다. RLS 에만 기대지 않고 tenant_id 를 명시한다
  // (findAllEnabled 선례) — 정책이 빠지거나 바뀌어도 다른 워크스페이스 작업이 보이거나 꺼지지 않게.

  private static final Table<?> USER_TABLE = table(name("user"));
  private static final Field<Long> U_ID = field(name("user", "id"), Long.class);
  private static final Field<String> U_NAME = field(name("user", "name"), String.class);

  /**
   * 관리자 목록용 최소 행 — 작업 내용(프롬프트·설정)은 읽지 않는다.
   *
   * @param ownerId 작성자 id (자격 판정용, 응답에는 싣지 않는다)
   */
  public record WorkspaceJobRow(
      Long id,
      String name,
      Long ownerId,
      String ownerName,
      Boolean enabled,
      LocalDateTime nextExecuteAt,
      String timezone) {}

  /** 이 워크스페이스의 스마트 작업 전부(최소 열 + 작성자 이름). 작성자 이름은 같은 질의에서 조인해 N+1 을 피한다. */
  public List<WorkspaceJobRow> findWorkspaceRows(long tenantId) {
    return dsl.select(
            PJ_ID, PJ_NAME, PJ_USER_ID, U_NAME, PJ_ENABLED, PJ_NEXT_EXECUTE_AT, PJ_TIMEZONE)
        .from(PROACTIVE_JOB)
        .leftJoin(USER_TABLE)
        .on(U_ID.eq(PJ_USER_ID))
        .where(PJ_TENANT_ID.eq(tenantId))
        .orderBy(PJ_ID.desc())
        .fetch(
            r ->
                new WorkspaceJobRow(
                    r.get(PJ_ID),
                    r.get(PJ_NAME),
                    r.get(PJ_USER_ID),
                    r.get(U_NAME),
                    r.get(PJ_ENABLED),
                    r.get(PJ_NEXT_EXECUTE_AT),
                    r.get(PJ_TIMEZONE)));
  }

  /** 이 워크스페이스의 작업 한 건(최소 행). 다른 워크스페이스·없는 작업이면 빈 값 — 호출자가 404 로 바꾼다. */
  public Optional<WorkspaceJobRow> findWorkspaceRow(Long id, long tenantId) {
    return dsl.select(
            PJ_ID, PJ_NAME, PJ_USER_ID, U_NAME, PJ_ENABLED, PJ_NEXT_EXECUTE_AT, PJ_TIMEZONE)
        .from(PROACTIVE_JOB)
        .leftJoin(USER_TABLE)
        .on(U_ID.eq(PJ_USER_ID))
        .where(PJ_ID.eq(id).and(PJ_TENANT_ID.eq(tenantId)))
        .fetchOptional(
            r ->
                new WorkspaceJobRow(
                    r.get(PJ_ID),
                    r.get(PJ_NAME),
                    r.get(PJ_USER_ID),
                    r.get(U_NAME),
                    r.get(PJ_ENABLED),
                    r.get(PJ_NEXT_EXECUTE_AT),
                    r.get(PJ_TIMEZONE)));
  }

  /** 관리자 끄기 — enabled 만 false 로 바꾼다. 켜기는 이 경로로 하지 않는다(자격 없는 사람이 자동 실행을 늘리면 안 된다). */
  public void disableInTenant(Long id, long tenantId) {
    dsl.update(PROACTIVE_JOB)
        .set(PJ_ENABLED, false)
        .set(PJ_UPDATED_AT, ProactiveTime.nowUtc())
        .where(PJ_ID.eq(id).and(PJ_TENANT_ID.eq(tenantId)))
        .execute();
  }

  /** 관리자 삭제 — 작성자와 무관하게 이 워크스페이스의 작업을 지운다. */
  public void deleteInTenant(Long id, long tenantId) {
    dsl.deleteFrom(PROACTIVE_JOB).where(PJ_ID.eq(id).and(PJ_TENANT_ID.eq(tenantId))).execute();
  }

  private ProactiveJobResponse toResponse(
      org.jooq.Record r,
      com.smartfirehub.proactive.dto.ProactiveJobExecutionResponse lastExecution) {
    try {
      JSONB configJsonb = r.get(PJ_CONFIG);
      Map<String, Object> config =
          configJsonb != null
              ? objectMapper.readValue(configJsonb.data(), new TypeReference<>() {})
              : Map.of();
      return new ProactiveJobResponse(
          r.get(PJ_ID),
          r.get(PJ_USER_ID),
          r.get(PJ_TEMPLATE_ID),
          r.get(RT_NAME),
          r.get(PJ_NAME),
          r.get(PJ_PROMPT),
          r.get(PJ_CRON_EXPRESSION),
          r.get(PJ_TIMEZONE),
          r.get(PJ_ENABLED),
          r.get(PJ_TRIGGER_TYPE),
          config,
          r.get(PJ_LAST_EXECUTED_AT),
          r.get(PJ_NEXT_EXECUTE_AT),
          r.get(PJ_CREATED_AT),
          r.get(PJ_UPDATED_AT),
          lastExecution);
    } catch (Exception e) {
      throw new RuntimeException("Failed to deserialize config", e);
    }
  }
}
