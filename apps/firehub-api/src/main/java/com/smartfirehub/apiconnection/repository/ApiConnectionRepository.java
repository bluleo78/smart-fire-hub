package com.smartfirehub.apiconnection.repository;

import static org.jooq.impl.DSL.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.UpdateSetMoreStep;
import org.springframework.stereotype.Repository;

/** API 연결 정보 저장소. Phase 9 리디자인: baseUrl, 헬스체크 경로, 헬스체크 상태 필드 추가. */
@Repository
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class ApiConnectionRepository {

  private final DSLContext dsl;

  private static final Table<?> API_CONNECTION = table(name("api_connection"));
  private static final Field<Long> AC_ID = field(name("api_connection", "id"), Long.class);
  private static final Field<String> AC_NAME = field(name("api_connection", "name"), String.class);
  private static final Field<String> AC_AUTH_TYPE =
      field(name("api_connection", "auth_type"), String.class);
  private static final Field<String> AC_AUTH_CONFIG =
      field(name("api_connection", "auth_config"), String.class);
  private static final Field<String> AC_DESCRIPTION =
      field(name("api_connection", "description"), String.class);
  private static final Field<Long> AC_CREATED_BY =
      field(name("api_connection", "created_by"), Long.class);
  private static final Field<LocalDateTime> AC_CREATED_AT =
      field(name("api_connection", "created_at"), LocalDateTime.class);
  private static final Field<LocalDateTime> AC_UPDATED_AT =
      field(name("api_connection", "updated_at"), LocalDateTime.class);

  // Phase 9: 신규 컬럼 필드
  private static final Field<String> AC_BASE_URL =
      field(name("api_connection", "base_url"), String.class);
  private static final Field<String> AC_HEALTH_CHECK_PATH =
      field(name("api_connection", "health_check_path"), String.class);
  private static final Field<String> AC_LAST_STATUS =
      field(name("api_connection", "last_status"), String.class);
  private static final Field<LocalDateTime> AC_LAST_CHECKED_AT =
      field(name("api_connection", "last_checked_at"), LocalDateTime.class);
  private static final Field<Long> AC_LAST_LATENCY_MS =
      field(name("api_connection", "last_latency_ms"), Long.class);
  private static final Field<String> AC_LAST_ERROR_MESSAGE =
      field(name("api_connection", "last_error_message"), String.class);

  public List<Record> findAll() {
    return dsl
        .select(
            AC_ID,
            AC_NAME,
            AC_AUTH_TYPE,
            AC_AUTH_CONFIG,
            AC_DESCRIPTION,
            AC_CREATED_BY,
            AC_CREATED_AT,
            AC_UPDATED_AT,
            AC_BASE_URL,
            AC_HEALTH_CHECK_PATH,
            AC_LAST_STATUS,
            AC_LAST_CHECKED_AT,
            AC_LAST_LATENCY_MS,
            AC_LAST_ERROR_MESSAGE)
        .from(API_CONNECTION)
        .orderBy(AC_CREATED_AT.desc())
        .fetch()
        .stream()
        .map(r -> (Record) r)
        .toList();
  }

  public Optional<Record> findById(Long id) {
    return dsl.select(
            AC_ID,
            AC_NAME,
            AC_AUTH_TYPE,
            AC_AUTH_CONFIG,
            AC_DESCRIPTION,
            AC_CREATED_BY,
            AC_CREATED_AT,
            AC_UPDATED_AT,
            AC_BASE_URL,
            AC_HEALTH_CHECK_PATH,
            AC_LAST_STATUS,
            AC_LAST_CHECKED_AT,
            AC_LAST_LATENCY_MS,
            AC_LAST_ERROR_MESSAGE)
        .from(API_CONNECTION)
        .where(AC_ID.eq(id))
        .fetchOptional()
        .map(r -> (Record) r);
  }

  public List<Record> findByCreatedBy(Long userId) {
    return dsl
        .select(
            AC_ID,
            AC_NAME,
            AC_AUTH_TYPE,
            AC_AUTH_CONFIG,
            AC_DESCRIPTION,
            AC_CREATED_BY,
            AC_CREATED_AT,
            AC_UPDATED_AT,
            AC_BASE_URL,
            AC_HEALTH_CHECK_PATH,
            AC_LAST_STATUS,
            AC_LAST_CHECKED_AT,
            AC_LAST_LATENCY_MS,
            AC_LAST_ERROR_MESSAGE)
        .from(API_CONNECTION)
        .where(AC_CREATED_BY.eq(userId))
        .fetch()
        .stream()
        .map(r -> (Record) r)
        .toList();
  }

  /** API 연결을 저장한다. baseUrl은 필수, healthCheckPath는 선택. */
  public Long save(
      String name,
      String description,
      String authType,
      String encryptedAuthConfig,
      Long createdBy,
      String baseUrl,
      String healthCheckPath) {
    return dsl.insertInto(API_CONNECTION)
        .set(AC_NAME, name)
        .set(AC_DESCRIPTION, description)
        .set(AC_AUTH_TYPE, authType)
        .set(AC_AUTH_CONFIG, encryptedAuthConfig)
        .set(AC_CREATED_BY, createdBy)
        .set(AC_BASE_URL, baseUrl != null ? baseUrl : "")
        .set(AC_HEALTH_CHECK_PATH, healthCheckPath)
        .returning(AC_ID)
        .fetchOne(r -> r.get(AC_ID));
  }

  /**
   * API 연결을 갱신한다. null인 항목은 Map에서 제외하여 기존 값을 보존한다.
   *
   * @param baseUrl 변경할 Base URL (null이면 미변경)
   * @param healthCheckPath 변경할 헬스체크 경로 (null이면 미변경)
   */
  public void update(
      Long id,
      String name,
      String description,
      String authType,
      String encryptedAuthConfig,
      String baseUrl,
      String healthCheckPath) {
    // null인 항목은 스킵하여 기존 DB 값을 보존한다 (부분 수정 지원).
    // raw Map 캐스트 대신 명시적 체인으로 null 필드 누락 방지.
    log.info(
        "[ApiConnection.update] id={} name={} description={} authType={} hasAuthConfig={} baseUrl={} healthCheckPath={}",
        id,
        name,
        description,
        authType,
        encryptedAuthConfig != null,
        baseUrl,
        healthCheckPath);
    UpdateSetMoreStep<?> step = dsl.update(API_CONNECTION).set(AC_UPDATED_AT, LocalDateTime.now());

    if (name != null) step = step.set(AC_NAME, name);
    if (description != null) step = step.set(AC_DESCRIPTION, description);
    if (authType != null) step = step.set(AC_AUTH_TYPE, authType);
    if (encryptedAuthConfig != null) step = step.set(AC_AUTH_CONFIG, encryptedAuthConfig);
    if (baseUrl != null) step = step.set(AC_BASE_URL, baseUrl);
    if (healthCheckPath != null) step = step.set(AC_HEALTH_CHECK_PATH, healthCheckPath);

    step.where(AC_ID.eq(id)).execute();
  }

  /**
   * 헬스체크 결과를 DB에 반영한다. last_checked_at은 현재 시각으로 자동 설정된다.
   *
   * @param id API 연결 ID
   * @param status 헬스체크 상태 (예: "UP", "DOWN")
   * @param latencyMs 응답 지연 ms (오류 시 null)
   * @param errorMessage 오류 메시지 (정상 시 null)
   */
  public void updateHealthStatus(Long id, String status, Long latencyMs, String errorMessage) {
    dsl.update(API_CONNECTION)
        .set(AC_LAST_STATUS, status)
        .set(AC_LAST_CHECKED_AT, LocalDateTime.now())
        .set(AC_LAST_LATENCY_MS, latencyMs)
        .set(AC_LAST_ERROR_MESSAGE, errorMessage)
        .where(AC_ID.eq(id))
        .execute();
  }

  /** 헬스체크 대상 API 연결 목록을 반환한다. health_check_path가 설정된 레코드만 포함한다. */
  public List<Record> findHealthCheckable() {
    return dsl
        .select(
            AC_ID,
            AC_NAME,
            AC_BASE_URL,
            AC_HEALTH_CHECK_PATH,
            AC_LAST_STATUS,
            AC_LAST_CHECKED_AT,
            AC_LAST_LATENCY_MS,
            AC_LAST_ERROR_MESSAGE)
        .from(API_CONNECTION)
        .where(AC_HEALTH_CHECK_PATH.isNotNull().and(AC_HEALTH_CHECK_PATH.ne("")))
        .fetch()
        .stream()
        .map(r -> (Record) r)
        .toList();
  }

  public void deleteById(Long id) {
    dsl.deleteFrom(API_CONNECTION).where(AC_ID.eq(id)).execute();
  }
}
