package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.SqlQueryResponse;
import com.smartfirehub.pipeline.exception.UnsafeSqlException;
import com.smartfirehub.support.IntegrationTestBase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 데이터셋 애드혹 쿼리 경로(POST /api/v1/datasets/{id}/query)의 {@link SqlValidator} 배선을 검증한다. (이슈 #385
 * Task 3)
 *
 * <p><b>클래스 레벨 {@code @Transactional} 을 붙이지 않는다</b> — 이 이니셔티브에서 5회 재발한 패턴이다. 픽스처(data 스키마
 * 테스트 테이블)는 {@code CREATE TABLE}/{@code DROP TABLE} 로 직접 커밋해 두고, 검증 대상인 {@link
 * DataTableQueryService#executeQuery}는 자체 트랜잭션이 없는(호출자가 열어야 하는) 메서드이므로 {@link
 * TransactionTemplate} 으로 그 호출 하나만 감싼다 — 실제 프로덕션 경계인 {@code
 * DatasetDataService#executeQuery}(@Transactional)를 흉내 낸 것이다.
 *
 * <p>{@link DatasetService#createDataset}은 감사 로그 기록·검색 색인 재구축(비동기 이벤트)까지 함께 트리거해 이 테스트가
 * 필요로 하지 않는 부수효과(및 레이스 컨디션)를 끌어들이므로 쓰지 않는다 — {@link DataTableQueryService#executeQuery}는
 * datasetId 를 받지 않고 SQL 문자열만으로 {@code data} 스키마를 직접 조회하는 메서드이기 때문에, 실제 테이블만 있으면
 * 데이터셋 메타데이터 등록 없이도 검증할 수 있다.
 */
class DataTableQueryServiceGuardTest extends IntegrationTestBase {

  @Autowired private DataTableQueryService dataTableQueryService;
  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;

  private TransactionTemplate tx;
  private String testTableName;

  @BeforeEach
  void setUp() {
    tx = new TransactionTemplate(transactionManager);

    testTableName = "guard_query_table";
    // 이전 실행이 tearDown 도달 전에 죽었을 경우를 대비해 생성 전에도 정리한다.
    dsl.execute("DROP TABLE IF EXISTS data." + testTableName);
    dsl.execute("CREATE TABLE data." + testTableName + " (id BIGSERIAL PRIMARY KEY, name TEXT)");
    dsl.execute("INSERT INTO data." + testTableName + " (name) VALUES ('Row1')");
  }

  @AfterEach
  void tearDown() {
    dsl.execute("DROP TABLE IF EXISTS data." + testTableName);
  }

  // =========================================================================
  // 다른 스키마 참조 — 부분문자열 대조를 뚫는 동등 표기 변형 (R3 실측)
  // =========================================================================

  /** 보안: 따옴표로 스키마를 감싼 표기는 문자열 대조("PUBLIC.")를 피해가지만 AST 검증기는 잡아야 한다. */
  @Test
  void executeQuery_publicSchemaQuotedVariant_rejected() {
    assertThatThrownBy(
            () ->
                tx.execute(
                    status ->
                        dataTableQueryService.executeQuery(
                            "SELECT * FROM \"public\".\"user\"", 10)))
        .isInstanceOf(UnsafeSqlException.class);
  }

  /** 보안: 점 주변 공백을 넣은 표기도 문자열 대조를 피해가지만 AST 검증기는 잡아야 한다. */
  @Test
  void executeQuery_publicSchemaSpacedVariant_rejected() {
    assertThatThrownBy(
            () ->
                tx.execute(
                    status ->
                        dataTableQueryService.executeQuery("SELECT * FROM public . \"user\"", 10)))
        .isInstanceOf(UnsafeSqlException.class);
  }

  // =========================================================================
  // 차단 함수 — set_config (기존 부분문자열 대조 목록에 없던 함수)
  // =========================================================================

  /** 보안: set_config 호출은 GUC/search_path 조작으로 이어질 수 있어 거부되어야 한다. */
  @Test
  void executeQuery_setConfig_rejected() {
    assertThatThrownBy(
            () ->
                tx.execute(
                    status ->
                        dataTableQueryService.executeQuery(
                            "SELECT set_config('search_path', 'public', false)", 10)))
        .isInstanceOf(UnsafeSqlException.class);
  }

  // =========================================================================
  // pg_catalog 암묵적 검색 차단 — team lead 판정 2, 실제 배선 경로 레벨로 고정
  // =========================================================================

  /**
   * {@code pg_catalog}는 {@code search_path} 설정과 무관하게 항상 암묵 검색되므로, 미한정 이름이 카탈로그 뷰와 일치하면
   * 예전엔 통과했다(실측: {@code SELECT * FROM pg_tables} 가 172행 반환, 그중 104건이 {@code data}/{@code
   * pg_catalog}/{@code information_schema} 밖 스키마 — team lead 실측, #385). {@link
   * com.smartfirehub.pipeline.service.validator.SqlValidator}가 미한정 {@code pg_} 접두어 이름을 거부하도록
   * 고쳐 이 경로에서도 막힘을 확인한다. 규칙 자체의 단위 테스트는 {@code SqlValidatorTest}에 있다 — 여기서는 실제 애드혹
   * 쿼리 경로 배선을 통해서도 막히는지만 고정한다.
   */
  @Test
  void executeQuery_unqualifiedPgCatalogView_rejected() {
    assertThatThrownBy(
            () ->
                tx.execute(
                    status -> dataTableQueryService.executeQuery("SELECT * FROM pg_tables", 10)))
        .isInstanceOf(UnsafeSqlException.class);
  }

  // =========================================================================
  // 회귀 방지 — 정상 미한정 쿼리는 여전히 통과한다
  // =========================================================================

  /** 회귀 방지: 스키마 없이 data 스키마 테이블을 참조하는 기존 사용자 쿼리는 계속 통과해야 한다(Task 2 판정 — 미한정 허용). */
  @Test
  void executeQuery_unqualifiedDataTable_stillPasses() {
    SqlQueryResponse response =
        tx.execute(
            status -> dataTableQueryService.executeQuery("SELECT * FROM " + testTableName, 100));

    assertThat(response).isNotNull();
    assertThat(response.error()).isNull();
  }
}
