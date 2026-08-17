package com.smartfirehub.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.analytics.dto.AnalyticsQueryResponse;
import com.smartfirehub.support.IntegrationTestBase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 애널리틱스 직접 실행 경로({@link AnalyticsQueryExecutionService#execute})의 {@link
 * com.smartfirehub.pipeline.service.validator.SqlValidator} 배선을 검증한다. (이슈 #385 Task 4)
 *
 * <p>{@code app.executor.enabled} 테스트 기본값은 {@code false}(application-test.yml 에 별도 오버라이드
 * 없음 — {@link AnalyticsQueryExecutionService#executorEnabled} 의 {@code @Value} 기본값 그대로) 이므로 이 클래스의
 * 모든 호출은 {@code executeDirectly} 경로를 탄다.
 *
 * <p><b>클래스 레벨 {@code @Transactional} 을 붙이지 않는다</b> — 이 이니셔티브에서 6회 재발한 패턴이다
 * ({@code DataTableQueryServiceGuardTest} 가 이미 "붙이지 않는다"고 명시해 둔 것과 동일 이유): 클래스
 * {@code @Transactional} 은 테스트 트랜잭션이 세션 GUC/카탈로그 가시성을 실제 프로덕션 커밋 경계와 다르게
 * 만들어 배선 결함을 영구히 가릴 수 있다. 대신 {@link TestInstance.Lifecycle#PER_CLASS} + 인스턴스
 * {@code @BeforeAll}/{@code @AfterAll} 로 픽스처를 **한 번만** 커밋하고 명시적으로 지운다 — 이 클래스의
 * 모든 테스트는 SELECT 뿐이라(픽스처를 변형하는 테스트가 없다) 테스트 간 격리가 애초에 필요 없다.
 * {@code DatasetService}(감사 로그·검색 색인 재구축까지 트리거)도 쓰지 않고 raw DDL 로 최소 픽스처만
 * 만든다 — {@code DataTableQueryServiceGuardTest} 와 같은 이유.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnalyticsQueryGuardTest extends IntegrationTestBase {

  @Autowired private AnalyticsQueryExecutionService executionService;
  @Autowired private DSLContext dsl;

  private static final String TEST_TABLE = "analytics_guard_test";
  private static final String GEOM_TABLE = "analytics_guard_geom_test";

  @BeforeAll
  void setUp() {
    // 이전 실행이 tearDown 도달 전에 죽었을 경우를 대비해 생성 전에도 정리한다(자기가 만든 테이블만).
    dsl.execute("DROP TABLE IF EXISTS data." + TEST_TABLE);
    dsl.execute("CREATE TABLE data." + TEST_TABLE + " (id BIGSERIAL PRIMARY KEY, name TEXT)");
    dsl.execute("INSERT INTO data." + TEST_TABLE + " (name) VALUES ('Row1')");

    // 공간 경로 픽스처(#385 최종 리뷰 단계3) — 허용목록 전환이 애널리틱스의 GeoJSON 래핑
    // (public.ST_AsGeoJSON 호출, buildGeoJsonWrappedSql)과 사용자가 직접 쓰는 PostGIS 함수 호출을
    // 깨지 않는지 확인해야 한다. StoredUserSqlReplayTest 코퍼스는 dev 이력에 공간 쿼리가 0건이라
    // 이 경로를 전혀 덮지 않는다(원장 R1 명시) — 별도로 여기서 확인한다.
    dsl.execute("DROP TABLE IF EXISTS data." + GEOM_TABLE);
    dsl.execute(
        "CREATE TABLE data." + GEOM_TABLE + " (id BIGSERIAL PRIMARY KEY, geom GEOMETRY(Point, 4326))");
    dsl.execute(
        "INSERT INTO data."
            + GEOM_TABLE
            + " (geom) VALUES (ST_SetSRID(ST_MakePoint(126.978, 37.566), 4326))");
  }

  @AfterAll
  void tearDown() {
    dsl.execute("DROP TABLE IF EXISTS data." + TEST_TABLE);
    dsl.execute("DROP TABLE IF EXISTS data." + GEOM_TABLE);
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

  /**
   * C1(#385 코드리뷰) — CTE 별칭을 전역으로 걷던 최초 구현은 스코프 밖의 동명 실테이블까지 검사 목록에서
   * 지워버렸다. 코드리뷰 실측(PG, search_path='data','public'): {@code SELECT count(*) FROM (WITH role
   * AS (SELECT 1 AS x) SELECT x FROM role) s, role} 가 {@code public.role} 3행을 반환했다 — 두 번째
   * {@code role}(파생 테이블의 CTE 스코프 밖에 있는 진짜 테이블 참조)이 "CTE 별칭과 이름이 같다"는 이유로
   * {@code unqualifiedTableNames()}에서 빠져 이 서비스의 카탈로그 백스톱(위 테스트가 검증하는 로직)이 그
   * 이름 자체를 보지 못했다. 스코프 인식 수정 후에는 이 경로 레벨에서도 거부돼야 한다.
   */
  @Test
  void execute_cteAliasCollisionWithOutOfScopeRealTable_rejected() {
    AnalyticsQueryResponse response =
        executionService.execute(
            "SELECT count(*) FROM (WITH role AS (SELECT 1 AS x) SELECT x FROM role) s, role",
            10,
            false);

    assertThat(response.error()).isNotNull();
  }

  /**
   * 리뷰 지적 — {@code information_schema.tables} 는 시퀀스(relkind {@code S})를 담지 않아 이전 구현이 이 케이스를
   * 놓쳤다(리뷰어 실측: {@code SELECT last_value, log_cnt FROM oauth_state_id_seq} 가 370/27 을 반환). {@code
   * pg_class.relkind}로 시퀀스까지 포함하도록 고친 뒤 이 케이스가 막히는지 실제 배선 레벨로 고정한다.
   * {@code oauth_state_id_seq} 는 {@code public} 스키마 실제 시퀀스이고 {@code data} 스키마엔 존재하지 않는다.
   */
  @Test
  void execute_unqualifiedSequenceShadowedByPublicOnlySequence_rejected() {
    AnalyticsQueryResponse response =
        executionService.execute("SELECT last_value FROM oauth_state_id_seq", 10, false);

    assertThat(response.error()).isNotNull();
  }

  /**
   * 리뷰 지적 — 미한정 함수 호출은 전혀 막지 않고 있었다. {@code provision_tenant_defaults}는 {@code public} 의
   * {@code SECURITY DEFINER} 변경 함수이고 {@code PUBLIC EXECUTE}를 갖고 있어(권한 실측) 미한정 호출이 실제로 실행된다.
   * {@code SqlValidator.BLOCKED_FUNCTIONS}에 추가한 뒤 이 경로에서도 막히는지 고정한다.
   */
  @Test
  void execute_unqualifiedSecurityDefinerFunctionCall_rejected() {
    AnalyticsQueryResponse response =
        executionService.execute("SELECT provision_tenant_defaults(1)", 10, false);

    assertThat(response.error()).isNotNull();
  }

  // =========================================================================
  // 회귀 방지 — 정상 미한정 쿼리는 여전히 통과한다
  // =========================================================================

  /** 회귀 방지: data 스키마에 실재하는 테이블을 스키마 없이 참조하는 기존 사용자 쿼리는 계속 통과해야 한다(Task 2 판정 — 미한정 허용). */
  @Test
  void execute_unqualifiedDataTable_stillPasses() {
    AnalyticsQueryResponse response =
        executionService.execute("SELECT * FROM " + TEST_TABLE, 100, false);

    assertThat(response.error()).isNull();
    assertThat(response.rows()).hasSize(1);
  }

  /** 회귀 방지: 정규화된 data.* 참조도 계속 통과해야 한다(기존 동작). */
  @Test
  void execute_qualifiedDataTable_stillPasses() {
    AnalyticsQueryResponse response =
        executionService.execute("SELECT * FROM data." + TEST_TABLE, 100, false);

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

  // =========================================================================
  // 공간 경로 — 함수 허용목록 전환이 PostGIS 사용을 깨지 않는지 (#385 최종 리뷰 단계3)
  // =========================================================================

  /** 회귀 방지: 사용자가 SQL 에서 직접 PostGIS 함수를 호출하는 정상 사용례가 허용목록 전환 후에도 통과해야 한다. */
  @Test
  void execute_userWrittenPostgisFunctionCall_stillPasses() {
    AnalyticsQueryResponse response =
        executionService.execute("SELECT ST_AsGeoJSON(geom) FROM data." + GEOM_TABLE, 10, false);

    assertThat(response.error()).isNull();
    assertThat(response.rows()).hasSize(1);
  }

  /**
   * 회귀 방지(허용목록 가드 아님 — 리뷰 지적으로 정정): geometry 컬럼을 raw 로 조회하면 jOOQ/JDBC 가 바이너리
   * 파싱에 실패해 {@link AnalyticsQueryExecutionService#executeDirectly}가 자동으로 {@code
   * public.ST_AsGeoJSON(...)}로 감싸 재시도한다({@code buildGeoJsonWrappedSql}). <b>이 래핑 SQL 은
   * {@code validate()}를 다시 타지 않는다</b> — 뮤테이션 테스트로 실측: {@code ALLOWED_FUNCTIONS}에서
   * {@code st_asgeojson}을 빼도 이 테스트는 계속 초록이었다(사용자가 제출한 원본 SQL {@code SELECT * FROM
   * data.t}에 애초에 그 함수 이름이 없으니 검증기가 볼 이유가 없다). 즉 이 테스트는 <b>허용목록을 가드하지
   * 않는다</b> — 그건 위 {@link #execute_userWrittenPostgisFunctionCall_stillPasses}의 몫이다. 이 테스트가
   * 실제로 잡는 것은 "raw geometry 조회 시 자동 래핑 폴백 메커니즘 자체가 여전히 DB 레벨에서 동작하는가"
   * 뿐이다.
   */
  @Test
  void execute_rawGeometrySelect_autoWrapFallback_stillWorksAtDbLevel() {
    AnalyticsQueryResponse response =
        executionService.execute("SELECT * FROM data." + GEOM_TABLE, 10, false);

    assertThat(response.error()).isNull();
    assertThat(response.rows()).hasSize(1);
  }
}
