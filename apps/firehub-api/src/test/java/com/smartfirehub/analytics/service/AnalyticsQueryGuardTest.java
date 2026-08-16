package com.smartfirehub.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.analytics.dto.AnalyticsQueryResponse;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * 애널리틱스 직접 실행 경로({@link AnalyticsQueryExecutionService#execute})의 {@link
 * com.smartfirehub.pipeline.service.validator.SqlValidator} 배선을 검증한다. (이슈 #385 Task 4)
 *
 * <p>{@code app.executor.enabled} 테스트 기본값은 {@code false}(application-test.yml 에 별도 오버라이드
 * 없음 — {@link AnalyticsQueryExecutionService#executorEnabled} 의 {@code @Value} 기본값 그대로) 이므로 이 클래스의
 * 모든 호출은 {@code executeDirectly} 경로를 탄다.
 *
 * <p>{@code @Transactional}을 클래스에 붙여 각 테스트를 롤백한다 — 이 서비스는 예외를 던지지 않고 {@link
 * AnalyticsQueryResponse#error()} 필드로 실패를 알리는 기존 계약이라({@code execute()}가 검증 실패도 캐치해 에러 응답으로 변환)
 * {@code DataTableQueryServiceGuardTest}처럼 `assertThatThrownBy`로 볼 수 없다 — 응답의 error 필드로 판정한다.
 */
@Transactional
class AnalyticsQueryGuardTest extends IntegrationTestBase {

  @Autowired private AnalyticsQueryExecutionService executionService;
  @Autowired private DatasetService datasetService;
  @Autowired private DSLContext dsl;

  private Long testUserId;

  @BeforeEach
  void setUp() {
    testUserId =
        dsl.insertInto(DSL.table(DSL.name("user")))
            .set(DSL.field(DSL.name("user", "username"), String.class), "analyticsguard")
            .set(DSL.field(DSL.name("user", "password"), String.class), "password")
            .set(DSL.field(DSL.name("user", "name"), String.class), "Analytics Guard")
            .set(DSL.field(DSL.name("user", "email"), String.class), "analyticsguard@example.com")
            .returning(DSL.field(DSL.name("user", "id"), Long.class))
            .fetchOne()
            .get(DSL.field(DSL.name("user", "id"), Long.class));

    datasetService.createDataset(
        new CreateDatasetRequest(
            "Analytics Guard DS",
            "analytics_guard_test",
            null,
            null,
            "TABLE",
            "SOURCE",
            List.of(new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null)),
            null),
        testUserId);
    dsl.execute("INSERT INTO data.analytics_guard_test (name) VALUES ('Row1')");
  }

  // =========================================================================
  // 다른 스키마 참조 — 부분문자열 대조("PUBLIC.")를 뚫는 동등 표기 변형
  // =========================================================================

  /** 보안: 따옴표로 감싼 표기는 문자열 대조를 피해가지만 AST 검증기는 잡아야 한다. */
  @Test
  void execute_publicSchemaQuotedVariant_rejected() {
    AnalyticsQueryResponse response =
        executionService.execute("SELECT * FROM \"public\".\"user\"", 10, false);

    assertThat(response.error()).isNotNull();
  }

  /** 보안: 점 주변 공백을 넣은 표기도 문자열 대조를 피해가지만 AST 검증기는 잡아야 한다. */
  @Test
  void execute_publicSchemaSpacedVariant_rejected() {
    AnalyticsQueryResponse response =
        executionService.execute("SELECT * FROM public . \"user\"", 10, false);

    assertThat(response.error()).isNotNull();
  }

  // =========================================================================
  // 차단 함수 — set_config (기존 부분문자열 대조 목록에 없던 함수)
  // =========================================================================

  /** 보안: set_config 호출은 GUC/search_path 조작으로 이어질 수 있어 거부되어야 한다. */
  @Test
  void execute_setConfig_rejected() {
    AnalyticsQueryResponse response =
        executionService.execute("SELECT set_config('search_path', 'public', false)", 10, false);

    assertThat(response.error()).isNotNull();
  }

  // =========================================================================
  // search_path 두 스키마('data','public') 하의 미한정 이름 — public 그림자 차단
  // =========================================================================

  /**
   * 보안 핵심 — R1 이 실측으로 닫은 구멍. search_path 를 {@code 'data'} 단독으로 좁히면 PostGIS 함수 해석이 깨져(직접 psql
   * 실측: {@code function st_asgeojson(public.geometry) does not exist}) {@code 'data','public'} 를 유지할
   * 수밖에 없는데, 그러면 미한정 {@code role}(public 스키마의 실제 RBAC 테이블, data 스키마엔 없음)이 search_path 를 통해
   * 조용히 {@code public.role}로 해석되어 다른 도메인 테이블이 새는 경로가 된다. {@link SqlValidator}는 이름 해석을 못 하므로
   * (AST 만으로는 미한정 이름이 어느 스키마인지 알 수 없다) 서비스 레이어의 카탈로그 조회로 막는다.
   */
  @Test
  void execute_unqualifiedNameShadowedByPublicOnlyTable_rejected() {
    AnalyticsQueryResponse response = executionService.execute("SELECT * FROM role", 10, false);

    assertThat(response.error()).isNotNull();
  }

  // =========================================================================
  // 회귀 방지 — 정상 미한정 쿼리는 여전히 통과한다
  // =========================================================================

  /** 회귀 방지: data 스키마에 실재하는 테이블을 스키마 없이 참조하는 기존 사용자 쿼리는 계속 통과해야 한다(Task 2 판정 — 미한정 허용). */
  @Test
  void execute_unqualifiedDataTable_stillPasses() {
    AnalyticsQueryResponse response =
        executionService.execute("SELECT * FROM analytics_guard_test", 100, false);

    assertThat(response.error()).isNull();
    assertThat(response.rows()).hasSize(1);
  }

  /** 회귀 방지: 정규화된 data.* 참조도 계속 통과해야 한다(기존 동작). */
  @Test
  void execute_qualifiedDataTable_stillPasses() {
    AnalyticsQueryResponse response =
        executionService.execute("SELECT * FROM data.analytics_guard_test", 100, false);

    assertThat(response.error()).isNull();
    assertThat(response.rows()).hasSize(1);
  }

  /**
   * 회귀 방지: data 스키마에 테이블이 실재하지 않고 public 스키마에도 없는 이름은 카탈로그 검사가 아니라 Postgres 가 자연스럽게
   * "relation does not exist"(42P01)를 던져야 한다 — 존재하지 않는 임의 이름까지 우리 카탈로그 검사가 가로채면 기존
   * {@code executeDirectly_undefinedTable_returnsCleanErrorWithSqlState42P01} 류의 계약이 깨진다.
   */
  @Test
  void execute_unqualifiedNameNotInDataNorPublic_naturalNotFoundError() {
    AnalyticsQueryResponse response =
        executionService.execute("SELECT * FROM no_such_table_guard_xyz", 10, false);

    assertThat(response.error()).isNotNull();
    assertThat(response.error()).contains("SQLState: 42P01");
  }
}
