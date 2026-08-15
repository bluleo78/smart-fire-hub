package com.smartfirehub.proactive.repository;

import com.smartfirehub.proactive.util.ProactiveTime;
import static org.jooq.impl.DSL.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Table;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 프로액티브 잡 저장소.
 *
 * <p>클래스 레벨 {@code @Transactional} 이 필요한 이유: V103 으로 {@code proactive_job} 에
 * {@code tenant_id} 가 생겼고 V104 에서 RLS 정책이 걸린다. 테넌트 값은 <b>트랜잭션-로컬 GUC</b>
 * ({@code app.tenant_id}) 이고 그 GUC 는 {@code TenantAwareTransactionManager.doBegin} 에서만
 * 주입되므로, 트랜잭션 없이 도는 배경 경로(부팅 시 스케줄 재등록 {@code @PostConstruct},
 * 크론 발화 콜백, {@code @Async} 실행기)에서는 INSERT 가 NOT NULL 위반으로 깨지고 SELECT 는
 * 예외도 로그도 없이 0행이 된다. 전파는 REQUIRED 라 이미 트랜잭션 안인 컨트롤러 경로의
 * 동작은 불변이다. 선례: {@code ReportTemplateRepository}.
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
   * <p><b>왜 RLS 에 맡기지 않고 {@code tenant_id} 술어를 명시하는가</b>: 호출자
   * ({@code ProactiveJobSchedulerService.reloadAllSchedules})는 ACTIVE 테넌트를 순회하며 잡마다 발화
   * 시점 테넌트를 캡처한다. V104(정책) 이전에는 이 조회가 컨텍스트와 무관하게 전 테넌트 행을
   * 돌려주므로, 술어가 없으면 <b>모든 잡이 마지막으로 순회된 테넌트를 캡처</b>해 남의 테넌트로
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
          .set(PJ_CONFIG, JSONB.valueOf(configJson))
          .returning(PJ_ID)
          .fetchOne(r -> r.get(PJ_ID));
    } catch (Exception e) {
      throw new RuntimeException("Failed to serialize config", e);
    }
  }

  public void update(
      Long id,
      Long userId,
      String name,
      String prompt,
      Long templateId,
      String cronExpression,
      String timezone,
      Boolean enabled,
      Map<String, Object> config) {
    try {
      var query = dsl.update(PROACTIVE_JOB).set(PJ_UPDATED_AT, ProactiveTime.nowUtc());
      if (name != null) query = query.set(PJ_NAME, name);
      if (prompt != null) query = query.set(PJ_PROMPT, prompt);
      if (templateId != null) query = query.set(PJ_TEMPLATE_ID, templateId);
      if (cronExpression != null) query = query.set(PJ_CRON_EXPRESSION, cronExpression);
      if (timezone != null) query = query.set(PJ_TIMEZONE, timezone);
      if (enabled != null) query = query.set(PJ_ENABLED, enabled);
      if (config != null) {
        query = query.set(PJ_CONFIG, JSONB.valueOf(objectMapper.writeValueAsString(config)));
      }
      query.where(PJ_ID.eq(id).and(PJ_USER_ID.eq(userId))).execute();
    } catch (Exception e) {
      throw new RuntimeException("Failed to serialize config", e);
    }
  }

  public void updateLastExecuted(
      Long id, LocalDateTime lastExecutedAt, LocalDateTime nextExecuteAt) {
    dsl.update(PROACTIVE_JOB)
        .set(PJ_LAST_EXECUTED_AT, lastExecutedAt)
        .set(PJ_NEXT_EXECUTE_AT, nextExecuteAt)
        .set(PJ_UPDATED_AT, ProactiveTime.nowUtc())
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
    dsl.update(PROACTIVE_JOB)
        .set(PJ_NEXT_EXECUTE_AT, nextExecuteAt)
        .where(PJ_ID.eq(id))
        .execute();
  }

  public void delete(Long id, Long userId) {
    dsl.deleteFrom(PROACTIVE_JOB).where(PJ_ID.eq(id).and(PJ_USER_ID.eq(userId))).execute();
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
