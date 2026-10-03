package com.smartfirehub.audit.repository;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.audit.dto.AuditLogResponse;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.util.LikePatternUtils;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 감사 로그 저장소.
 *
 * <p>클래스 레벨 {@code @Transactional} 이 필요한 이유: {@code AuditLogService.log} 에는
 * 트랜잭션이 없다. 로그인처럼 트랜잭션 안에서 호출되는 경로는 편승하지만, 알림·비동기 내보내기
 * 경로는 편승할 트랜잭션이 없어 RLS 정책 아래에서 tenant_id DEFAULT 가 NULL 이 되고
 * INSERT 가 WITH CHECK 를 통과하지 못한다. 전파는 REQUIRED 이므로 기존 경로의 동작은 불변이다.
 * (이슈 #384-3)
 */
@Transactional
@Repository
@RequiredArgsConstructor
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

  private static final Field<Long> AL_TENANT_ID = field(name("audit_log", "tenant_id"), Long.class);

  /** metadata 의 대상 아이디(운영자 계정 조치가 남긴다). 괄호로 연산 우선순위를 명시한다. */
  private static final Field<String> AL_TARGET_USERNAME =
      field("({0} ->> 'targetUsername')", String.class, AL_METADATA);

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

  /**
   * 감사 로그 전체 조회 (페이지네이션 + 복합 필터)
   *
   * @param search 사용자명/설명 검색어
   * @param userId 사용자 ID 정확 일치 필터 (null이면 무제한, #89)
   * @param actionType 액션 유형 필터
   * @param resource 리소스 유형 필터
   * @param result 결과(SUCCESS/FAILURE) 필터
   * @param startDate 날짜 범위 시작 (inclusive, null이면 무제한)
   * @param endDate 날짜 범위 종료 (inclusive, null이면 무제한)
   * @param page 페이지 번호 (0부터)
   * @param size 페이지 크기
   */
  public PageResponse<AuditLogResponse> findAll(
      String search,
      Long userId,
      String actionType,
      String resource,
      String result,
      LocalDateTime startDate,
      LocalDateTime endDate,
      int page,
      int size) {
    Condition condition = noCondition();

    if (search != null && !search.isBlank()) {
      String pattern = LikePatternUtils.containsPattern(search.trim());
      condition =
          condition.and(
              AL_USERNAME
                  .likeIgnoreCase(pattern, '\\')
                  .or(AL_DESCRIPTION.likeIgnoreCase(pattern, '\\')));
    }

    // 사용자별 정확 일치 필터 (#89): username free-text 검색과 달리 user_id 컬럼으로 직접 조회.
    if (userId != null) {
      condition = condition.and(AL_USER_ID.eq(userId));
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

    // 날짜 범위 필터: startDate 이상, endDate 이하
    if (startDate != null) {
      condition = condition.and(AL_ACTION_TIME.greaterOrEqual(startDate));
    }

    if (endDate != null) {
      condition = condition.and(AL_ACTION_TIME.lessOrEqual(endDate));
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

  /**
   * 플랫폼 감사 로그 조회(WD-4) — {@code tenant_id IS NULL} 행만.
   *
   * <p>RLS 형태 (b)(V99)는 GUC 가 없을 때 NULL 행만 보여 주지만, 이 조건을 쿼리에도 명시한다: 어떤 경로로든 GUC 가 남아 있으면 RLS 는 그
   * 테넌트 행만, 이 조건은 NULL 행만 고르므로 결과가 0행이 된다(fail-closed) — 테넌트 행이 새지 않는다.
   *
   * @param actor 행위자 username 부분일치(대소문자 무시). null/공백이면 무시
   * @param target 대상 — resource_id 정확 일치 또는 metadata.targetUsername 부분일치. null/공백이면 무시
   * @param actionType 액션 정확 일치. null/공백이면 무시
   * @param fromInclusive 이 시각 이상(null 이면 무제한)
   * @param toExclusive 이 시각 미만(null 이면 무제한)
   */
  public PageResponse<AuditLogResponse> findPlatform(
      String actor,
      String target,
      String actionType,
      LocalDateTime fromInclusive,
      LocalDateTime toExclusive,
      int page,
      int size) {
    Condition condition = AL_TENANT_ID.isNull();
    if (actor != null && !actor.isBlank()) {
      condition =
          condition.and(
              AL_USERNAME.likeIgnoreCase(LikePatternUtils.containsPattern(actor.trim()), '\\'));
    }
    if (target != null && !target.isBlank()) {
      String t = target.trim();
      condition =
          condition.and(
              AL_RESOURCE_ID
                  .eq(t)
                  .or(
                      AL_TARGET_USERNAME.likeIgnoreCase(
                          LikePatternUtils.containsPattern(t), '\\')));
    }
    if (actionType != null && !actionType.isBlank()) {
      condition = condition.and(AL_ACTION_TYPE.eq(actionType));
    }
    if (fromInclusive != null) {
      condition = condition.and(AL_ACTION_TIME.greaterOrEqual(fromInclusive));
    }
    if (toExclusive != null) {
      condition = condition.and(AL_ACTION_TIME.lessThan(toExclusive));
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
            // 같은 트랜잭션 행은 NOW() 가 같다 — id 로 순서를 고정해 페이지 경계가 흔들리지 않게 한다.
            .orderBy(AL_ACTION_TIME.desc(), AL_ID.desc())
            .offset((long) page * size)
            .limit(size)
            .fetch(this::mapToResponse);
    int totalPages = (int) Math.ceil((double) totalElements / size);
    return new PageResponse<>(content, page, size, totalElements, totalPages);
  }
}
