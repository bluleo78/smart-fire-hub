package com.smartfirehub.proactive.repository;

import com.smartfirehub.proactive.util.ProactiveTime;
import static org.jooq.impl.DSL.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.proactive.dto.ProactiveMessageResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Table;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 프로액티브 메시지(사용자 알림함) 저장소.
 *
 * <p>클래스 레벨 {@code @Transactional} 이 필요한 이유: V103 으로 {@code tenant_id} 가 생겼고
 * V104 에서 RLS 가 걸린다. 테넌트 값은 트랜잭션-로컬 GUC 이므로, 트랜잭션 없이 도는 발송
 * 경로({@code @Scheduled} 디스패치 워커, {@code @Async} 잡 실행기)에서는 INSERT 가 NOT NULL
 * 위반으로 깨진다. 전파 REQUIRED 이므로 컨트롤러 경로의 동작은 불변이다.
 */
@Transactional
@Repository
@RequiredArgsConstructor
public class ProactiveMessageRepository {

  private final DSLContext dsl;
  private final ObjectMapper objectMapper;

  private static final Table<?> PROACTIVE_MESSAGE = table(name("proactive_message"));
  private static final Field<Long> PM_ID = field(name("proactive_message", "id"), Long.class);
  private static final Field<Long> PM_USER_ID =
      field(name("proactive_message", "user_id"), Long.class);
  private static final Field<Long> PM_EXECUTION_ID =
      field(name("proactive_message", "execution_id"), Long.class);
  private static final Field<String> PM_TITLE =
      field(name("proactive_message", "title"), String.class);
  private static final Field<JSONB> PM_CONTENT =
      field(name("proactive_message", "content"), JSONB.class);
  private static final Field<String> PM_MESSAGE_TYPE =
      field(name("proactive_message", "message_type"), String.class);
  private static final Field<Boolean> PM_READ =
      field(name("proactive_message", "read"), Boolean.class);
  private static final Field<LocalDateTime> PM_READ_AT =
      field(name("proactive_message", "read_at"), LocalDateTime.class);
  private static final Field<LocalDateTime> PM_CREATED_AT =
      field(name("proactive_message", "created_at"), LocalDateTime.class);

  private static final Table<?> PROACTIVE_JOB_EXECUTION = table(name("proactive_job_execution"));
  private static final Field<Long> PJE_ID =
      field(name("proactive_job_execution", "id"), Long.class);
  private static final Field<Long> PJE_JOB_ID =
      field(name("proactive_job_execution", "job_id"), Long.class);

  private static final Table<?> PROACTIVE_JOB = table(name("proactive_job"));
  private static final Field<Long> PJ_ID = field(name("proactive_job", "id"), Long.class);
  private static final Field<String> PJ_NAME = field(name("proactive_job", "name"), String.class);

  public Long create(
      Long userId,
      Long executionId,
      String title,
      Map<String, Object> content,
      String messageType) {
    try {
      String contentJson = content != null ? objectMapper.writeValueAsString(content) : "{}";
      return dsl.insertInto(PROACTIVE_MESSAGE)
          .set(PM_USER_ID, userId)
          .set(PM_EXECUTION_ID, executionId)
          .set(PM_TITLE, title)
          .set(PM_CONTENT, JSONB.valueOf(contentJson))
          .set(PM_MESSAGE_TYPE, messageType != null ? messageType : "REPORT")
          .set(PM_READ, false)
          .returning(PM_ID)
          .fetchOne(r -> r.get(PM_ID));
    } catch (Exception e) {
      throw new RuntimeException("Failed to serialize content", e);
    }
  }

  public List<ProactiveMessageResponse> findUnreadByUserId(Long userId) {
    return dsl.select(
            PM_ID,
            PM_USER_ID,
            PJE_JOB_ID,
            PM_EXECUTION_ID,
            PM_TITLE,
            PM_CONTENT,
            PM_MESSAGE_TYPE,
            PM_READ,
            PM_READ_AT,
            PJ_NAME,
            PM_CREATED_AT)
        .from(PROACTIVE_MESSAGE)
        .leftJoin(PROACTIVE_JOB_EXECUTION)
        .on(PM_EXECUTION_ID.eq(PJE_ID))
        .leftJoin(PROACTIVE_JOB)
        .on(PJE_JOB_ID.eq(PJ_ID))
        .where(PM_USER_ID.eq(userId).and(PM_READ.isFalse()))
        .orderBy(PM_ID.desc())
        .fetch(r -> toResponse(r));
  }

  public int countUnreadByUserId(Long userId) {
    return dsl.fetchCount(
        dsl.selectOne()
            .from(PROACTIVE_MESSAGE)
            .where(PM_USER_ID.eq(userId).and(PM_READ.isFalse())));
  }

  public void markAsRead(Long id, Long userId) {
    dsl.update(PROACTIVE_MESSAGE)
        .set(PM_READ, true)
        .set(PM_READ_AT, ProactiveTime.nowUtc())
        .where(PM_ID.eq(id).and(PM_USER_ID.eq(userId)))
        .execute();
  }

  public void markAllAsRead(Long userId) {
    dsl.update(PROACTIVE_MESSAGE)
        .set(PM_READ, true)
        .set(PM_READ_AT, ProactiveTime.nowUtc())
        .where(PM_USER_ID.eq(userId).and(PM_READ.isFalse()))
        .execute();
  }

  public List<ProactiveMessageResponse> findByUserId(Long userId, int limit, int offset) {
    return dsl.select(
            PM_ID,
            PM_USER_ID,
            PJE_JOB_ID,
            PM_EXECUTION_ID,
            PM_TITLE,
            PM_CONTENT,
            PM_MESSAGE_TYPE,
            PM_READ,
            PM_READ_AT,
            PJ_NAME,
            PM_CREATED_AT)
        .from(PROACTIVE_MESSAGE)
        .leftJoin(PROACTIVE_JOB_EXECUTION)
        .on(PM_EXECUTION_ID.eq(PJE_ID))
        .leftJoin(PROACTIVE_JOB)
        .on(PJE_JOB_ID.eq(PJ_ID))
        .where(PM_USER_ID.eq(userId))
        .orderBy(PM_ID.desc())
        .limit(limit)
        .offset(offset)
        .fetch(r -> toResponse(r));
  }

  private ProactiveMessageResponse toResponse(org.jooq.Record r) {
    try {
      JSONB contentJsonb = r.get(PM_CONTENT);
      Map<String, Object> content =
          contentJsonb != null
              ? objectMapper.readValue(contentJsonb.data(), new TypeReference<>() {})
              : Map.of();
      return new ProactiveMessageResponse(
          r.get(PM_ID),
          r.get(PM_USER_ID),
          r.get(PJE_JOB_ID), // execution JOIN을 통해 상위 잡 ID 포함
          r.get(PM_EXECUTION_ID),
          r.get(PM_TITLE),
          content,
          r.get(PM_MESSAGE_TYPE),
          r.get(PM_READ),
          r.get(PM_READ_AT),
          r.get(PJ_NAME),
          r.get(PM_CREATED_AT));
    } catch (Exception e) {
      throw new RuntimeException("Failed to deserialize content", e);
    }
  }
}
