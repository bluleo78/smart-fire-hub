package com.smartfirehub.apiconnection.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.*;

import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * ApiConnectionRepository 통합 테스트.
 * Phase 9 리디자인: baseUrl/헬스체크 필드 저장, 상태 갱신, 헬스체크 대상 조회를 검증한다.
 */
class ApiConnectionRepositoryTest extends IntegrationTestBase {

  @Autowired private ApiConnectionRepository repository;

  @Autowired private DSLContext dsl;

  private Long testUserId;

  private static final Table<?> USER_TABLE = table(name("user"));
  private static final Field<Long>   U_ID       = field(name("user", "id"), Long.class);
  private static final Field<String> U_USERNAME = field(name("user", "username"), String.class);
  private static final Field<String> U_PASSWORD = field(name("user", "password"), String.class);
  private static final Field<String> U_NAME     = field(name("user", "name"), String.class);
  private static final Field<String> U_EMAIL    = field(name("user", "email"), String.class);

  private static final Table<?> API_CONNECTION = table(name("api_connection"));
  private static final Field<Long>   AC_CREATED_BY    = field(name("api_connection", "created_by"), Long.class);
  private static final Field<String> AC_BASE_URL       = field(name("api_connection", "base_url"), String.class);
  private static final Field<String> AC_HEALTH_CHECK_PATH = field(name("api_connection", "health_check_path"), String.class);
  private static final Field<String> AC_LAST_STATUS    = field(name("api_connection", "last_status"), String.class);
  private static final Field<Long>   AC_LAST_LATENCY_MS = field(name("api_connection", "last_latency_ms"), Long.class);
  private static final Field<String> AC_LAST_ERROR_MESSAGE = field(name("api_connection", "last_error_message"), String.class);

  @BeforeEach
  void setUp() {
    testUserId =
        dsl.insertInto(USER_TABLE)
            .set(U_USERNAME, "repotest_" + System.nanoTime())
            .set(U_PASSWORD, "password")
            .set(U_NAME, "Repo Test User")
            .set(U_EMAIL, "repotest_" + System.nanoTime() + "@example.com")
            .returning(U_ID)
            .fetchOne(r -> r.get(U_ID));
  }

  @AfterEach
  void tearDown() {
    // FK 순서: api_connection 먼저 삭제 후 user 삭제
    dsl.deleteFrom(API_CONNECTION).where(AC_CREATED_BY.eq(testUserId)).execute();
    dsl.deleteFrom(USER_TABLE).where(U_ID.eq(testUserId)).execute();
  }

  /**
   * baseUrl/healthCheckPath를 포함한 save 호출 시 모든 필드가 DB에 저장되는지 확인한다.
   */
  @Test
  void create_withBaseUrl_persistsAllFields() {
    Long id = repository.save(
        "Test API",
        "desc",
        "API_KEY",
        "encrypted-config",
        testUserId,
        "https://api.example.com",
        "/health");

    assertThat(id).isNotNull();

    Record record = dsl.select(AC_BASE_URL, AC_HEALTH_CHECK_PATH)
        .from(API_CONNECTION)
        .where(field(name("api_connection", "id"), Long.class).eq(id))
        .fetchOne();

    assertThat(record).isNotNull();
    assertThat(record.get(AC_BASE_URL)).isEqualTo("https://api.example.com");
    assertThat(record.get(AC_HEALTH_CHECK_PATH)).isEqualTo("/health");
  }

  /**
   * updateHealthStatus 호출 시 last_status/last_latency_ms/last_error_message가 올바르게 갱신되는지 확인한다.
   */
  @Test
  void updateHealthStatus_setsLastStatusFields() {
    Long id = repository.save(
        "Health API",
        null,
        "BEARER",
        "encrypted-config",
        testUserId,
        "https://health.example.com",
        "/ping");

    repository.updateHealthStatus(id, "UP", 123L, null);

    Record record = dsl.select(AC_LAST_STATUS, AC_LAST_LATENCY_MS, AC_LAST_ERROR_MESSAGE)
        .from(API_CONNECTION)
        .where(field(name("api_connection", "id"), Long.class).eq(id))
        .fetchOne();

    assertThat(record).isNotNull();
    assertThat(record.get(AC_LAST_STATUS)).isEqualTo("UP");
    assertThat(record.get(AC_LAST_LATENCY_MS)).isEqualTo(123L);
    assertThat(record.get(AC_LAST_ERROR_MESSAGE)).isNull();

    // 오류 상태로 갱신
    repository.updateHealthStatus(id, "DOWN", null, "Connection refused");

    Record updated = dsl.select(AC_LAST_STATUS, AC_LAST_LATENCY_MS, AC_LAST_ERROR_MESSAGE)
        .from(API_CONNECTION)
        .where(field(name("api_connection", "id"), Long.class).eq(id))
        .fetchOne();

    assertThat(updated).isNotNull();
    assertThat(updated.get(AC_LAST_STATUS)).isEqualTo("DOWN");
    assertThat(updated.get(AC_LAST_LATENCY_MS)).isNull();
    assertThat(updated.get(AC_LAST_ERROR_MESSAGE)).isEqualTo("Connection refused");
  }

  /**
   * findHealthCheckable은 health_check_path가 NOT NULL인 레코드만 반환해야 한다.
   */
  @Test
  void findHealthCheckable_returnsOnlyRecordsWithHealthCheckPath() {
    // healthCheckPath 있는 레코드 2개
    Long id1 = repository.save("With HC 1", null, "API_KEY", "enc1", testUserId,
        "https://a.example.com", "/health");
    Long id2 = repository.save("With HC 2", null, "BEARER", "enc2", testUserId,
        "https://b.example.com", "/ping");
    // healthCheckPath 없는 레코드 1개
    Long id3 = repository.save("No HC", null, "API_KEY", "enc3", testUserId,
        "https://c.example.com", null);

    List<Record> checkable = repository.findHealthCheckable();

    List<Long> ids = checkable.stream()
        .map(r -> r.get(field(name("api_connection", "id"), Long.class)))
        .toList();

    assertThat(ids).contains(id1, id2);
    assertThat(ids).doesNotContain(id3);
  }
}
