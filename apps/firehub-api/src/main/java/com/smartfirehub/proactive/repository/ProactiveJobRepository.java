package com.smartfirehub.proactive.repository;

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

  public List<ProactiveJobResponse> findAllEnabled() {
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
        .fetch(r -> toResponse(r, null));
  }

  public Long create(
      Long userId,
      String name,
      String prompt,
      Long templateId,
      String cronExpression,
      String timezone,
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
          .set(PJ_ENABLED, true)
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
      var query = dsl.update(PROACTIVE_JOB).set(PJ_UPDATED_AT, LocalDateTime.now());
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
        .set(PJ_UPDATED_AT, LocalDateTime.now())
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
