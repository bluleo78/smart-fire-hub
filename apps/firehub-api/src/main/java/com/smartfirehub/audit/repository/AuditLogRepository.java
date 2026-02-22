package com.smartfirehub.audit.repository;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.audit.dto.AuditLogResponse;
import com.smartfirehub.global.dto.PageResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

@Repository
public class AuditLogRepository {

  private final DSLContext dsl;

  private static final Table<?> AUDIT_LOG = table(name("audit_log"));
  private static final Field<Long> AL_ID = field(name("audit_log", "id"), Long.class);
  private static final Field<Long> AL_USER_ID = field(name("audit_log", "user_id"), Long.class);
  private static final Field<String> AL_USERNAME =
      field(name("audit_log", "username"), String.class);
  private static final Field<String> AL_ACTION_TYPE =
      field(name("audit_log", "action_type"), String.class);
  private static final Field<String> AL_RESOURCE =
      field(name("audit_log", "resource"), String.class);
  private static final Field<String> AL_RESOURCE_ID =
      field(name("audit_log", "resource_id"), String.class);
  private static final Field<String> AL_DESCRIPTION =
      field(name("audit_log", "description"), String.class);
  private static final Field<LocalDateTime> AL_ACTION_TIME =
      field(name("audit_log", "action_time"), LocalDateTime.class);
  private static final Field<String> AL_IP_ADDRESS =
      field(name("audit_log", "ip_address"), String.class);
  private static final Field<String> AL_USER_AGENT =
      field(name("audit_log", "user_agent"), String.class);
  private static final Field<String> AL_RESULT = field(name("audit_log", "result"), String.class);
  private static final Field<String> AL_ERROR_MESSAGE =
      field(name("audit_log", "error_message"), String.class);
  private static final Field<JSONB> AL_METADATA = field(name("audit_log", "metadata"), JSONB.class);

  public AuditLogRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  private AuditLogResponse mapToResponse(Record r) {
    JSONB jsonb = r.get(AL_METADATA);
    return new AuditLogResponse(
        r.get(AL_ID),
        r.get(AL_USER_ID),
        r.get(AL_USERNAME),
        r.get(AL_ACTION_TYPE),
        r.get(AL_RESOURCE),
        r.get(AL_RESOURCE_ID),
        r.get(AL_DESCRIPTION),
        r.get(AL_ACTION_TIME),
        r.get(AL_IP_ADDRESS),
        r.get(AL_USER_AGENT),
        r.get(AL_RESULT),
        r.get(AL_ERROR_MESSAGE),
        jsonb != null ? jsonb.data() : null);
  }

  public Long save(
      Long userId,
      String username,
      String actionType,
      String resource,
      String resourceId,
      String description,
      String ipAddress,
      String userAgent,
      String result,
      String errorMessage,
      JSONB metadata) {
    return dsl.insertInto(AUDIT_LOG)
        .set(AL_USER_ID, userId)
        .set(AL_USERNAME, username)
        .set(AL_ACTION_TYPE, actionType)
        .set(AL_RESOURCE, resource)
        .set(AL_RESOURCE_ID, resourceId)
        .set(AL_DESCRIPTION, description)
        .set(AL_IP_ADDRESS, ipAddress)
        .set(AL_USER_AGENT, userAgent)
        .set(AL_RESULT, result)
        .set(AL_ERROR_MESSAGE, errorMessage)
        .set(AL_METADATA, metadata)
        .returning(AL_ID)
        .fetchOne()
        .get(AL_ID);
  }

  public Optional<AuditLogResponse> findById(Long id) {
    return dsl.select(
            AL_ID,
            AL_USER_ID,
            AL_USERNAME,
            AL_ACTION_TYPE,
            AL_RESOURCE,
            AL_RESOURCE_ID,
            AL_DESCRIPTION,
            AL_ACTION_TIME,
            AL_IP_ADDRESS,
            AL_USER_AGENT,
            AL_RESULT,
            AL_ERROR_MESSAGE,
            AL_METADATA)
        .from(AUDIT_LOG)
        .where(AL_ID.eq(id))
        .fetchOptional(this::mapToResponse);
  }

  public List<AuditLogResponse> findByResource(
      String actionType, String resource, String resourceId) {
    var condition = AL_RESOURCE.eq(resource);

    if (actionType != null) {
      condition = condition.and(AL_ACTION_TYPE.eq(actionType));
    }

    if (resourceId != null) {
      condition = condition.and(AL_RESOURCE_ID.eq(resourceId));
    }

    return dsl.select(
            AL_ID,
            AL_USER_ID,
            AL_USERNAME,
            AL_ACTION_TYPE,
            AL_RESOURCE,
            AL_RESOURCE_ID,
            AL_DESCRIPTION,
            AL_ACTION_TIME,
            AL_IP_ADDRESS,
            AL_USER_AGENT,
            AL_RESULT,
            AL_ERROR_MESSAGE,
            AL_METADATA)
        .from(AUDIT_LOG)
        .where(condition)
        .orderBy(AL_ACTION_TIME.desc())
        .fetch(this::mapToResponse);
  }

  public PageResponse<AuditLogResponse> findAll(
      String search, String actionType, String resource, String result, int page, int size) {
    Condition condition = noCondition();

    if (search != null && !search.isBlank()) {
      String pattern = "%" + search.trim() + "%";
      condition =
          condition.and(
              AL_USERNAME.likeIgnoreCase(pattern).or(AL_DESCRIPTION.likeIgnoreCase(pattern)));
    }

    if (actionType != null && !actionType.isBlank()) {
      condition = condition.and(AL_ACTION_TYPE.eq(actionType));
    }

    if (resource != null && !resource.isBlank()) {
      condition = condition.and(AL_RESOURCE.eq(resource));
    }

    if (result != null && !result.isBlank()) {
      condition = condition.and(AL_RESULT.eq(result));
    }

    long totalElements = dsl.selectCount().from(AUDIT_LOG).where(condition).fetchOne(0, long.class);

    List<AuditLogResponse> content =
        dsl.select(
                AL_ID,
                AL_USER_ID,
                AL_USERNAME,
                AL_ACTION_TYPE,
                AL_RESOURCE,
                AL_RESOURCE_ID,
                AL_DESCRIPTION,
                AL_ACTION_TIME,
                AL_IP_ADDRESS,
                AL_USER_AGENT,
                AL_RESULT,
                AL_ERROR_MESSAGE,
                AL_METADATA)
            .from(AUDIT_LOG)
            .where(condition)
            .orderBy(AL_ACTION_TIME.desc())
            .offset(page * size)
            .limit(size)
            .fetch(this::mapToResponse);

    int totalPages = (int) Math.ceil((double) totalElements / size);
    return new PageResponse<>(content, page, size, totalElements, totalPages);
  }
}
