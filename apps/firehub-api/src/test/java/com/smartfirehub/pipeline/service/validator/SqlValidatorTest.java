package com.smartfirehub.pipeline.service.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.exception.UnsafeSqlException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SqlValidatorTest {

  private final SqlValidator validator = new SqlValidator();

  /**
   * 무인자 생성자(= 프로덕션 파이프라인 정책) 검증기는 허용 스키마를 {@code DataSchema.current()} 로
   * 해석하므로 <b>테넌트 컨텍스트를 요구</b>한다(P3-b1 R7). 이 클래스는 스프링 컨텍스트를 띄우지 않는
   * 순수 단위 테스트라 {@code IntegrationTestBase} 의 테넌트 설정을 받지 못하므로 직접 세운다.
   *
   * <p>단언을 약화시키는 장치가 아니다 — 프로덕션에서 이 검증기가 호출되는 지점은 언제나 테넌트
   * 스코프 안이며(요청 필터 또는 배경 잡의 테넌트 순회), 여기서 흉내 내는 것은 그 전제뿐이다.
   */
  @BeforeEach
  void setTenantContext() {
    TenantContext.set(1L);
  }

  @AfterEach
  void clearTenantContext() {
    TenantContext.clear();
  }

  // --- 허용되는 구문 ---

  @Test
  void allows_simple_select_on_data_schema() {
    assertThatCode(() -> validator.validate("SELECT * FROM data.t")).doesNotThrowAnyException();
  }

  @Test
  void allows_select_with_where_order() {
    assertThatCode(() -> validator.validate("SELECT a, b FROM data.t WHERE c > 1 ORDER BY a"))
        .doesNotThrowAnyException();
  }

  @Test
  void allows_insert_select_within_data_schema() {
    assertThatCode(() -> validator.validate("INSERT INTO data.t (a, b) SELECT a, b FROM data.s"))
        .doesNotThrowAnyException();
  }

  @Test
  void allows_update_on_data_schema() {
    assertThatCode(() -> validator.validate("UPDATE data.t SET a = 1 WHERE id = 2"))
        .doesNotThrowAnyException();
  }

  @Test
  void allows_delete_on_data_schema() {
    assertThatCode(() -> validator.validate("DELETE FROM data.t WHERE id = 2"))
        .doesNotThrowAnyException();
  }

  @Test
  void allows_cte_referencing_only_data_schema() {
    assertThatCode(
            () ->
                validator.validate(
                    "WITH cte AS (SELECT id, x FROM data.s) "
                        + "SELECT t.id, cte.x FROM data.t JOIN cte USING (id)"))
        .doesNotThrowAnyException();
  }

  @Test
  void allows_trailing_semicolon() {
    assertThatCode(() -> validator.validate("SELECT * FROM data.t;")).doesNotThrowAnyException();
  }

  // --- 거부되는 구문 ---

  @Test
  void rejects_multiple_statements() {
    assertThatThrownBy(() -> validator.validate("SELECT 1; SELECT 2"))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("멀티 스테이트먼트");
  }

  @Test
  void rejects_statement_appended_after_select() {
    assertThatThrownBy(() -> validator.validate("SELECT * FROM data.t; DROP TABLE data.t"))
        .isInstanceOf(UnsafeSqlException.class);
  }

  @Test
  void rejects_public_schema_reference() {
    assertThatThrownBy(() -> validator.validate("SELECT * FROM public.\"user\""))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("data");
  }

  @Test
  void rejects_information_schema_reference() {
    assertThatThrownBy(() -> validator.validate("SELECT * FROM information_schema.tables"))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("data");
  }

  @Test
  void rejects_unqualified_table_reference() {
    assertThatThrownBy(() -> validator.validate("SELECT * FROM t"))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("스키마");
  }

  @Test
  void rejects_cte_with_public_reference_inside() {
    assertThatThrownBy(
            () ->
                validator.validate("WITH cte AS (SELECT * FROM public.\"user\") SELECT * FROM cte"))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("data");
  }

  @Test
  void rejects_drop_statement() {
    assertThatThrownBy(() -> validator.validate("DROP TABLE data.t"))
        .isInstanceOf(UnsafeSqlException.class);
  }

  @Test
  void rejects_truncate_statement() {
    assertThatThrownBy(() -> validator.validate("TRUNCATE data.t"))
        .isInstanceOf(UnsafeSqlException.class);
  }

  @Test
  void rejects_do_block() {
    assertThatThrownBy(() -> validator.validate("DO $$ BEGIN END $$"))
        .isInstanceOf(UnsafeSqlException.class);
  }

  @Test
  void rejects_pg_read_file_in_select() {
    assertThatThrownBy(() -> validator.validate("SELECT pg_read_file('/etc/passwd')"))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("pg_read_file");
  }

  @Test
  void rejects_dblink_in_select() {
    assertThatThrownBy(() -> validator.validate("SELECT dblink_connect('host=evil.com dbname=x')"))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("dblink_connect");
  }

  @Test
  void rejects_lo_import_in_select() {
    assertThatThrownBy(() -> validator.validate("SELECT lo_import('/etc/passwd')"))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("lo_import");
  }

  @Test
  void rejects_garbage_input() {
    assertThatThrownBy(() -> validator.validate("not a sql"))
        .isInstanceOf(UnsafeSqlException.class);
  }

  @Test
  void rejects_null() {
    assertThatThrownBy(() -> validator.validate(null))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("비어");
  }

  @Test
  void rejects_blank() {
    assertThatThrownBy(() -> validator.validate("   "))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("비어");
  }

  // --- Task 1: 파라미터화(allowedSchema / allowUnqualifiedTables) 검증 ---

  /**
   * (1) 다른 스키마 참조는 표기 변형(따옴표, 점 주변 공백)과 무관하게 거부되어야 한다.
   *
   * <p>문자열 대조({@code contains("PUBLIC.")})는 이런 변형에 뚫리지만, AST 기반 검증기는 스키마 이름을 정규화해 비교하므로 뚫리지 않아야 한다.
   */
  @Test
  void rejects_other_schema_reference_regardless_of_quoting_variant() {
    // "허용되지 않는 스키마"로 좁혀 단언한다 — "data" 만 검사하면 미한정 거부 메시지("...data.user 형식으로...")에도
    // 매치되어, 파서가 이 표기를 미한정으로 오분류해도(=검증 우회) 테스트가 초록으로 남는 결함이 있었다.
    assertThatThrownBy(() -> validator.validate("SELECT * FROM \"public\".\"user\""))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("허용되지 않는 스키마");
    assertThatThrownBy(() -> validator.validate("SELECT * FROM public . \"user\""))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("허용되지 않는 스키마");
  }

  /**
   * (1-b) 위 거부가 permissive 모드({@code allowUnqualifiedTables=true})에서도 유지되는지 고정한다.
   *
   * <p>Task 3/4 가 배선할 모드가 바로 이 모드이며, 여기서 표기 변형이 오분류되면 "다른 에러"가 아니라 **통과**(=검증 우회)가 된다. 아래 네 형태는
   * PostgreSQL 이 동등하게 해석하는 {@code public."user"} 변형이다({@code smartfirehub.public."user"} 3파트
   * 변형은 m5(#385 코드리뷰) 수정 후 다른 메시지("점이 2개 이상")로 먼저 거부되므로
   * {@link #rejects_threePartTableName} 로 따로 뺐다).
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT * FROM \"public\".\"user\"",
        "SELECT * FROM public . \"user\"",
        "SELECT * FROM \"public\" . \"user\"",
        "SELECT * FROM PUBLIC.\"user\""
      })
  void rejects_other_schema_reference_variants_in_permissive_mode(String sql) {
    SqlValidator permissive = new SqlValidator("data", true);
    assertThatThrownBy(() -> permissive.validate(sql))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("허용되지 않는 스키마");
  }

  /** (1-c) {@code allowedSchema} 가 실제로 쓰인다 — "data" 하드코딩이 남아 있으면 이 케이스가 거꾸로 통과/거부된다. */
  @Test
  void allowed_schema_is_actually_parameterized() {
    SqlValidator analytics = new SqlValidator("analytics", false);
    assertThatCode(() -> analytics.validate("SELECT * FROM analytics.t")).doesNotThrowAnyException();
    assertThatThrownBy(() -> analytics.validate("SELECT * FROM data.t"))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("허용되지 않는 스키마");
  }

  /** (2) set_config / current_setting 호출은 BLOCKED_FUNCTIONS 에 의해 거부된다. */
  @Test
  void rejects_set_config_and_current_setting_calls() {
    assertThatThrownBy(() -> validator.validate("SELECT set_config('search_path', 'public', false)"))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("set_config");
    assertThatThrownBy(() -> validator.validate("SELECT current_setting('search_path')"))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("current_setting");
  }

  /**
   * (2-b) {@code pg_sleep} 호출은 BLOCKED_FUNCTIONS 에 의해 거부된다(#385 Task 5, R5).
   *
   * <p>{@code statement_timeout} 이 쿼리 지속 시간은 묶어도 {@code pg_sleep} 이 점유하는 커넥션 자체는 막지 못하므로
   * 애플리케이션 레이어에서 조기 차단한다. 공유 deny-list 라 파이프라인 경로(무인자 생성자)에도 함께 적용됨을 이 테스트로 고정한다.
   */
  @Test
  void rejects_pg_sleep_call() {
    assertThatThrownBy(() -> validator.validate("SELECT pg_sleep(5)"))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("pg_sleep");
  }

  /** (3) 롤 변경문(SET ROLE / RESET ROLE)은 SELECT/INSERT/UPDATE/DELETE 가 아니므로 문 타입 검사에서 거부된다. */
  @Test
  void rejects_role_change_statements() {
    assertThatThrownBy(() -> validator.validate("SET ROLE app_tenant"))
        .isInstanceOf(UnsafeSqlException.class);
    assertThatThrownBy(() -> validator.validate("RESET ROLE"))
        .isInstanceOf(UnsafeSqlException.class);
  }

  /**
   * (4) {@code allowUnqualifiedTables} 정책에 따라 스키마 없는 테이블 참조 허용 여부가 갈린다.
   *
   * <p>기본 생성자(무인자)는 기존 동작({@code allowUnqualifiedTables=false})을 그대로 유지해야 파이프라인 경로가 무변경이다.
   */
  @Test
  void unqualified_table_reference_policy_is_configurable() {
    SqlValidator permissive = new SqlValidator("data", true);
    assertThatCode(() -> permissive.validate("SELECT * FROM t")).doesNotThrowAnyException();

    SqlValidator strict = new SqlValidator("data", false);
    assertThatThrownBy(() -> strict.validate("SELECT * FROM t"))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("스키마");

    // 기본(무인자) 생성자는 기존 동작(미한정 거부)을 그대로 유지한다.
    assertThatThrownBy(() -> validator.validate("SELECT * FROM t"))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("스키마");
  }

  /**
   * (5) permissive 모드에서도 미한정 {@code pg_} 접두어 이름은 거부된다.
   *
   * <p>{@code pg_catalog}는 {@code search_path} 설정과 무관하게 항상 암묵 검색되므로, 미한정 허용만으로는 카탈로그
   * 열람(예: {@code pg_tables}로 다른 스키마 테이블 이름 나열, {@code pg_roles}로 롤 목록 나열)을 막지 못한다(#385
   * Task 3 실측). 정상적인 미한정 사용자 테이블 이름은 계속 통과해야 한다(역방향 단언).
   */
  @Test
  void rejects_unqualified_pg_prefixed_name_even_in_permissive_mode() {
    SqlValidator permissive = new SqlValidator("data", true);

    assertThatThrownBy(() -> permissive.validate("SELECT * FROM pg_tables"))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("pg_catalog");
    assertThatThrownBy(() -> permissive.validate("SELECT * FROM pg_roles"))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("pg_catalog");

    // 역방향: 정상 미한정 사용자 테이블은 여전히 통과한다.
    assertThatCode(() -> permissive.validate("SELECT * FROM customers")).doesNotThrowAnyException();
  }

  // --- unqualifiedTableNames — PG 식별자 폴딩 규칙 (#385 Task 4 리뷰 지적) ---

  /** 따옴표 없는 이름은 소문자로 접힌다 — PG 파서가 따옴표 없는 식별자를 항상 소문자로 접기 때문. */
  @Test
  void unqualifiedTableNames_unquotedName_isLowercased() {
    SqlValidator permissive = new SqlValidator("data", true);

    assertThat(permissive.unqualifiedTableNames("SELECT * FROM Customers"))
        .containsExactly("customers");
  }

  /**
   * 따옴표로 감싼 혼합 대소문자 이름은 원문 그대로 보존된다 — 무조건 소문자화하면 {@code pg_class.relname}과 바이트 단위로
   * 어긋나 호출부의 카탈로그 대조가 실패한다(리뷰 지적: {@code "MyTable"}은 소문자화하면 다른 이름이 된다).
   */
  @Test
  void unqualifiedTableNames_quotedMixedCaseName_preservesCase() {
    SqlValidator permissive = new SqlValidator("data", true);

    assertThat(permissive.unqualifiedTableNames("SELECT * FROM \"MyTable\""))
        .containsExactly("MyTable");
  }

  /** 스키마 한정 이름은 결과에서 제외된다 — 이 메서드는 미한정 이름만 돌려준다. */
  @Test
  void unqualifiedTableNames_qualifiedName_excluded() {
    SqlValidator permissive = new SqlValidator("data", true);

    assertThat(permissive.unqualifiedTableNames("SELECT * FROM data.t, u")).containsExactly("u");
  }

  // --- 최종 리뷰 C1/C2/M4 — 각각 수정 전 실제로 통과했음을 실측한 우회 3종 ---

  /**
   * C1: {@code query_to_xml} 은 인자가 문자열 리터럴이라 그 안의 테이블 참조가 {@code
   * TablesNamesFinder}에 절대 안 잡힌다 — 스키마 화이트리스트가 완전히 무력화된다. 실측(수정 전):
   * {@code SELECT query_to_xml('SELECT count(*) c FROM public."user"', true, false, '')}가
   * {@code search_path='data'} 애드혹 경로에서 성공해 {@code <c>7777</c>}를 반환했다. 함수 자체를
   * {@code BLOCKED_FUNCTIONS}로 막는 것이 유일한 방어(형제 함수 계열 포함).
   */
  @Test
  void rejects_query_to_xml_and_sibling_functions() {
    SqlValidator permissive = new SqlValidator("data", true);

    assertThatThrownBy(
            () ->
                permissive.validate(
                    "SELECT query_to_xml('SELECT count(*) c FROM public.\"user\"', true, false,"
                        + " '')"))
        .isInstanceOf(UnsafeSqlException.class);
    assertThatThrownBy(() -> permissive.validate("SELECT table_to_xml('public.role', true, false, '')"))
        .isInstanceOf(UnsafeSqlException.class);
    assertThatThrownBy(() -> permissive.validate("SELECT pg_get_viewdef('public.some_view'::regclass)"))
        .isInstanceOf(UnsafeSqlException.class);
  }

  /**
   * C2: 함수 이름을 따옴표로 감싸면 {@code BlockedFunctionFinder}가 {@code toLowerCase()}만 하고 따옴표를 벗기지
   * 않아 deny-list 전체가 뚫렸다. 실측(수정 전): 검증기가 {@code SELECT "pg_sleep"(5)}를 통과시켰고, DB 에서
   * {@code SELECT "current_setting"('search_path')}가 {@code data, public}을 반환했으며,
   * {@code SELECT "resolve_trigger_tenant_by_token_hash"('deadbeef')}가 실행됐다 — Task 4 에서 추가한
   * 정의자 함수 5개(변경 함수 {@code provision_tenant_defaults} 포함)까지 따옴표 한 쌍으로 무력화됐다.
   */
  @Test
  void rejects_quoted_blocked_function_names() {
    SqlValidator permissive = new SqlValidator("data", true);

    assertThatThrownBy(() -> permissive.validate("SELECT \"pg_sleep\"(5)"))
        .isInstanceOf(UnsafeSqlException.class);
    assertThatThrownBy(() -> permissive.validate("SELECT \"current_setting\"('search_path')"))
        .isInstanceOf(UnsafeSqlException.class);
    assertThatThrownBy(
            () ->
                permissive.validate(
                    "SELECT \"provision_tenant_defaults\"(1)"))
        .isInstanceOf(UnsafeSqlException.class);
  }

  /**
   * M4: {@code SELECT ... INTO}는 {@code Select}로 모델링돼 {@code requireDmlOrSelect}를 통과하고,
   * {@code TablesNamesFinder.getTables}가 INTO 대상을 보고하지 않아 스키마 화이트리스트가 적용되지 않았다.
   * 실측(수정 전): 검증기가 {@code SELECT * INTO public.pwned FROM data.t}를 통과시켰고, DB 에서
   * {@code SELECT 1 AS x INTO data.zz_probe}가 실제로 테이블을 생성했다. {@code detectQueryType}이 이
   * 문장을 SELECT 로 분류해 {@code readOnly=true}(MCP 도구) 게이트까지 통과한다는 점도 별도로 고정한다 —
   * {@code validate()} 가 readOnly 여부와 무관하게 거부해야 그 게이트를 우회할 수 없다.
   */
  @Test
  void rejects_select_into() {
    SqlValidator permissive = new SqlValidator("data", true);

    assertThatThrownBy(() -> permissive.validate("SELECT * INTO public.pwned FROM data.t"))
        .isInstanceOf(UnsafeSqlException.class);
    // readOnly 게이트가 이 문장을 SELECT 로 오분류해도(SqlValidationUtils.detectQueryType), validate()
    // 자체가 무조건 거부하므로 readOnly=true 경로에서도 결과는 동일해야 한다 — 별도로 못박는다.
    assertThatThrownBy(() -> permissive.validate("SELECT * INTO TEMP zz_probe FROM data.t"))
        .isInstanceOf(UnsafeSqlException.class);
  }

  // --- 재재리뷰 C1 — 절 단위 visitor 가 놓쳤던 9개 위치. AstNodeCollector 전수 walk 로 고정 ---

  /**
   * C1 실측(수정 전 전부 통과): {@code ORDER BY}/{@code GROUP BY}/{@code LIMIT} 안의 함수·서브쿼리는
   * {@code TablesNamesFinder} 기반 절 단위 visitor 가 아예 방문하지 않는 절이었다. {@code ORDER BY (SELECT
   * count(*) FROM public.usr)}는 스키마 화이트리스트까지 뚫었다(테이블 참조가 FROM 절 밖에 있다는 이유만으로).
   */
  @Test
  void rejects_dangerous_calls_in_orderBy_groupBy_limit() {
    SqlValidator permissive = new SqlValidator("data", true);

    assertThatThrownBy(
            () ->
                permissive.validate(
                    "SELECT a FROM data.t ORDER BY"
                        + " query_to_xml('SELECT * FROM public.usr',true,false,'')"))
        .isInstanceOf(UnsafeSqlException.class);
    assertThatThrownBy(() -> permissive.validate("SELECT a FROM data.t GROUP BY pg_sleep(1)"))
        .isInstanceOf(UnsafeSqlException.class);
    assertThatThrownBy(
            () ->
                permissive.validate(
                    "SELECT a FROM data.t LIMIT (SELECT count(*) FROM public.usr)"))
        .isInstanceOf(UnsafeSqlException.class);
    assertThatThrownBy(
            () -> permissive.validate("SELECT a FROM data.t ORDER BY (SELECT count(*) FROM public.usr)"))
        .as("서브쿼리 안 테이블 참조 — 스키마 화이트리스트까지 뚫렸던 케이스")
        .isInstanceOf(UnsafeSqlException.class);
  }

  /**
   * C1 실측(수정 전 전부 통과): {@code IS [NOT] NULL} 피연산자, {@code FILTER (WHERE ...)}, UPDATE/DELETE 의
   * {@code WHERE}, {@code RETURNING} — 전부 절 단위 visitor 사각이었다.
   */
  @Test
  void rejects_dangerous_calls_in_isNull_filter_whereReturning() {
    SqlValidator permissive = new SqlValidator("data", true);

    assertThatThrownBy(
            () -> permissive.validate("SELECT * FROM data.t WHERE pg_sleep(5) IS NULL"))
        .isInstanceOf(UnsafeSqlException.class);
    assertThatThrownBy(
            () ->
                permissive.validate(
                    "SELECT count(*) FILTER (WHERE pg_sleep(1) IS NULL) FROM data.t"))
        .isInstanceOf(UnsafeSqlException.class);
    assertThatThrownBy(
            () -> permissive.validate("UPDATE data.t SET a = 1 WHERE pg_sleep(5) IS NULL"))
        .isInstanceOf(UnsafeSqlException.class);
    assertThatThrownBy(() -> permissive.validate("DELETE FROM data.t WHERE pg_sleep(5) IS NULL"))
        .isInstanceOf(UnsafeSqlException.class);
    assertThatThrownBy(
            () -> permissive.validate("INSERT INTO data.t (a) VALUES (1) RETURNING pg_sleep(5)"))
        .isInstanceOf(UnsafeSqlException.class);
  }

  /**
   * 부수 효과 회귀 방지: 절 단위 visitor({@code TablesNamesFinder} 상속)를 버리면서 그 유틸리티의 알려진
   * 버그(윈도 프레임 {@code ROWS BETWEEN ... PRECEDING}에서 {@code WindowOffset.getExpression()}이 null 일
   * 때 NPE)도 함께 사라졌다 — 정상 윈도 SQL 이 "SQL 테이블 분석 실패"로 오탐 거부되던 선행 결함이었다(재재리뷰어
   * 실측, 이 diff 이전부터 존재). 리플렉션 순회는 null 을 만나면 그냥 멈추므로 해소된다.
   */
  @Test
  void allows_windowFunctionWithRowsBetweenFrame_regressionFixed() {
    SqlValidator permissive = new SqlValidator("data", true);

    assertThatCode(
            () ->
                permissive.validate(
                    "SELECT sum(a) OVER (ORDER BY b ROWS BETWEEN 1 PRECEDING AND CURRENT ROW)"
                        + " FROM data.t"))
        .doesNotThrowAnyException();
  }

  /**
   * 회귀 방지: CTE 는 {@link AstNodeCollector}가 스키마 검사 대상에서 제외해야 한다 — 절 단위 visitor 시절엔
   * {@code TablesNamesFinder}가 자동으로 처리해 주던 것을, 전수 walk 로 바꾸면서 직접 구현했다(CTE 별칭도
   * 일반 {@code Table} 노드로 파싱되기 때문). strict 모드(미한정 거부)에서도 CTE 참조 자체는 스키마 위반으로
   * 오인되면 안 된다.
   */
  @Test
  void allows_cteReference_notMisclassifiedAsUnqualifiedTable() {
    SqlValidator strict = new SqlValidator();

    assertThatCode(
            () -> strict.validate("WITH cte AS (SELECT * FROM data.t) SELECT * FROM cte"))
        .doesNotThrowAnyException();
  }

  // --- 재재리뷰 M2 — nextval/currval 리터럴 인자 검사 ---

  /**
   * nextval/currval 은 인자가 문자열 리터럴이라 그 안의 스키마를 AST 테이블 참조로는 볼 수 없다 — 그냥
   * 허용목록에 이름만 올리면 {@code nextval('public.어떤_시퀀스')}로 남의 시퀀스를 조작할 수 있다(재재리뷰어가
   * {@code nextval('public.slack_workspace_id_seq')}로 실제 754→755 진행시켰다). 리터럴을 파싱해 스키마를
   * 검사하고, 계산된 표현식(리터럴이 아닌 인자)은 정적으로 검증할 수 없으므로 무조건 거부한다.
   */
  @Test
  void nextval_literalArgument_checkedAgainstSchema() {
    SqlValidator strict = new SqlValidator("data", false);
    SqlValidator permissive = new SqlValidator("data", true);

    // data 스키마 한정 — 항상 허용
    assertThatCode(() -> strict.validate("SELECT nextval('data.my_seq')"))
        .doesNotThrowAnyException();

    // public 스키마 한정 — 항상 거부(재재리뷰가 찾은 실제 우회)
    assertThatThrownBy(
            () -> strict.validate("SELECT nextval('public.slack_workspace_id_seq')"))
        .isInstanceOf(UnsafeSqlException.class);
    assertThatThrownBy(
            () -> permissive.validate("SELECT currval('public.oauth_state_id_seq')"))
        .isInstanceOf(UnsafeSqlException.class);

    // 미한정 — C2(#385 코드리뷰) 수정 후에는 permissive 에서도 항상 거부한다. 처음엔 테이블과 같은 정책
    // (permissive 허용)을 그대로 썼는데, 애널리틱스(("data", true) + search_path='data','public')에서
    // SELECT nextval('slack_workspace_id_seq')가 public 시퀀스를 실제로 증가시켰다(코드리뷰 실측) —
    // 시퀀스는 테이블과 달리 dev 이력에 정당한 미한정 사용례가 없어 항상 스키마 한정을 요구하기로 판단을
    // 뒤집었다.
    assertThatThrownBy(() -> strict.validate("SELECT nextval('my_seq')"))
        .isInstanceOf(UnsafeSqlException.class);
    assertThatThrownBy(() -> permissive.validate("SELECT nextval('my_seq')"))
        .as("미한정 시퀀스는 permissive 모드에서도 거부되어야 한다(코드리뷰 C2)")
        .isInstanceOf(UnsafeSqlException.class);

    // 계산된 표현식(리터럴 아님) — 항상 거부
    assertThatThrownBy(() -> permissive.validate("SELECT nextval(seq_name_column)"))
        .isInstanceOf(UnsafeSqlException.class);
  }

  // --- #387-2 — 함수 허용목록 보강 ---

  /**
   * #387-2 로 추가한 안전 함수들이 통과한다.
   *
   * <p>주의: 이 목록은 <b>오늘 알려진 16개</b>를 닫을 뿐이다. 이슈의 진단대로 큐레이션 허용목록은
   * LLM 이 새로 쓰는 SQL 의 함수 집합을 원리적으로 한정하지 못한다 — 이 테스트가 초록이라고
   * "함수 허용목록 문제가 해결됐다"고 읽으면 안 된다.
   */
  @Test
  void allows_functions_added_for_issue_387() {
    List<String> exprs =
        List.of(
            "encode(a::bytea, 'hex')", "decode(a, 'hex')", "gen_random_uuid()",
            "uuid_generate_v4()", "json_array_length(a::json)", "jsonb_array_length(a::jsonb)",
            "array_to_json(ARRAY[1,2])", "jsonb_pretty(a::jsonb)", "to_tsvector(a)",
            "plainto_tsquery(a)", "ts_rank(to_tsvector(a), plainto_tsquery(a))",
            "date_bin('1 hour', a::timestamp, '2000-01-01'::timestamp)", "similarity(a, b)",
            "levenshtein(a, b)", "digest(a, 'sha256')", "sha256(a::bytea)");
    for (String expr : exprs) {
      assertThatCode(() -> new SqlValidator().validate("SELECT " + expr + " FROM data.t"))
          .as("허용되어야 하는 함수: %s", expr)
          .doesNotThrowAnyException();
    }
  }

  /** pg_typeof 는 의도적으로 제외된 상태를 유지한다(카탈로그 정보 노출, 파일 Javadoc 277-278). */
  @Test
  void still_rejects_pg_typeof() {
    assertThatThrownBy(() -> new SqlValidator().validate("SELECT pg_typeof(a) FROM data.t"))
        .isInstanceOf(UnsafeSqlException.class);
  }

  // --- #387-5 — 시퀀스 인자의 인용 식별자 점 파싱 ---

  /** 인용 식별자 안의 점을 스키마 구분자로 오인하지 않는다(#387-5). */
  @Test
  void accepts_sequence_with_dot_inside_quoted_name() {
    assertThatCode(
            () -> new SqlValidator().validate("SELECT nextval('data.\"my.seq\"') FROM data.t"))
        .doesNotThrowAnyException();
  }

  /** 다단 FQN 시퀀스는 거부한다 — 테이블 경로와 같은 규칙(requireDataSchemaOnly 선례). */
  @Test
  void rejects_sequence_with_multiple_unquoted_dots() {
    assertThatThrownBy(
            () -> new SqlValidator().validate("SELECT nextval('db.data.seq') FROM data.t"))
        .isInstanceOf(UnsafeSqlException.class);
  }

  /** 스키마 밖 시퀀스는 계속 거부한다(회귀 가드). */
  @Test
  void still_rejects_sequence_outside_allowed_schema() {
    assertThatThrownBy(
            () -> new SqlValidator().validate("SELECT nextval('public.seq') FROM data.t"))
        .isInstanceOf(UnsafeSqlException.class);
  }

  // --- 재재재리뷰 C — 깊이 상한 도달은 fail-closed(거부), fail-open(조용한 통과) 아님 ---

  /** {@code WHERE a IN (SELECT ...)} 를 {@code depth} 단 중첩하고 최내부에 {@code innermost}를 심는다. */
  private static String deepNestedWhereIn(int depth, String innermost) {
    StringBuilder sb = new StringBuilder("SELECT a FROM data.t WHERE a IN (");
    for (int i = 0; i < depth; i++) {
      sb.append("SELECT a FROM data.t WHERE a IN (");
    }
    sb.append(innermost);
    sb.append(")".repeat(depth + 1));
    return sb.toString();
  }

  /**
   * 재재재리뷰 실측(수정 전 전부 통과) — {@code WHERE a IN (SELECT ...)} 169단 중첩 + 최내부 {@code FROM
   * public.usr}. DB 는 같은 형태 200단을 실제로 실행해 {@code public."user"} 7853행을 반환했다. 최초 구현은
   * {@code MAX_DEPTH} 도달 시 조용히 {@code return}(fail-open)해서 그 아래 서브트리 전체가 미검사 통과였다 —
   * 스키마 화이트리스트가 뚫린다.
   *
   * <p>중첩 단수는 600 단이다(F6, 수정 라운드 1 — 이전엔 169 단). 상한이 {@code MAX_TRAVERSAL_DEPTH}(순회
   * 깊이)로 500→1500 상향되면서 169 단(순회 깊이 실측 약 514)은 더 이상 상한에 닿지 않아 이 가드가
   * "상한 지점 아래에 묻힌 위반이 조용히 통과하는가"를 더 이상 지키지 못하는 상태였다 — 600 단(순회 깊이
   * 실측 약 1807)으로 올려 여전히 상한을 확실히 넘긴다.
   */
  @Test
  void rejects_schemaViolation_buriedBeyondMaxDepth() {
    SqlValidator permissive = new SqlValidator("data", true);

    assertThatThrownBy(
            () -> permissive.validate(deepNestedWhereIn(600, "SELECT a FROM public.usr")))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("순회 깊이");
  }

  /**
   * 같은 뿌리 — {@code SELECT ... INTO}를 깊이 묻어도 M4 차단이 유지돼야 한다(10단에선 이미 거부됨). 중첩
   * 단수는 위 {@link #rejects_schemaViolation_buriedBeyondMaxDepth}와 같은 이유로 600 단이다(F6).
   */
  @Test
  void rejects_selectInto_buriedBeyondMaxDepth() {
    SqlValidator permissive = new SqlValidator("data", true);
    StringBuilder sql = new StringBuilder("SELECT a FROM data.t WHERE a IN (");
    for (int i = 0; i < 600; i++) {
      sql.append("SELECT a FROM data.t WHERE a IN (");
    }
    sql.append("SELECT a INTO public.pwned FROM data.t");
    sql.append(")".repeat(601));

    assertThatThrownBy(() -> permissive.validate(sql.toString()))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("순회 깊이");
  }

  /**
   * 같은 뿌리 — {@link SqlValidator#unqualifiedTableNames}이 깊이 상한 초과 시 <b>빈 집합을 조용히
   * 반환</b>하는 쪽이 더 위험했다(호출부인 애널리틱스 카탈로그 대조가 "미한정 참조 없음"으로 잘못 읽는다).
   * 이제 예외로 거부해 호출부가 그 사실을 알 수 있다.
   *
   * <p>중첩 단수는 600 단(#387-4 이전엔 169 단) — 상한이 {@code MAX_TRAVERSAL_DEPTH}(순회 깊이, 이전 이름
   * {@code MAX_DEPTH})로 500→1500 상향되면서(위 클래스 상단 문서 참고) 169 단(순회 깊이 실측 514)은 더 이상
   * 상한을 넘지 못한다. 600 단(순회 깊이 실측 약 1807)으로 올려 여전히 상한을 확실히 넘긴다 — 이 메서드는
   * {@code requireDataSchemaOnly} 같은 별도 검사가 없어서 상한 자체가 유일한 방어선이다.
   */
  @Test
  void unqualifiedTableNames_throwsInsteadOfSilentlyEmpty_beyondMaxDepth() {
    SqlValidator permissive = new SqlValidator("data", true);

    assertThatThrownBy(
            () ->
                permissive.unqualifiedTableNames(
                    deepNestedWhereIn(600, "SELECT a FROM some_unqualified_table_xyz")))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("순회 깊이");
  }

  /** 양성 대조 — 상한 근처에도 못 미치는 정상 중첩 쿼리는 여전히 통과해야 한다. */
  @Test
  void allows_ordinaryNestedSubquery_wellWithinMaxDepth() {
    SqlValidator permissive = new SqlValidator("data", true);

    assertThatCode(
            () -> permissive.validate("SELECT a FROM data.t WHERE a IN (SELECT a FROM data.t)"))
        .doesNotThrowAnyException();
  }

  // --- #387-4 — 깊이 상한은 SQL 중첩이 아니라 리플렉션 순회 홉 수를 센다 ---

  /**
   * 평평한 OR 연쇄가 "너무 깊게 중첩됨"으로 거부되지 않는다(#387-4).
   *
   * <p>이전 상한 500(당시 이름 {@code MAX_DEPTH})은 SQL 중첩이 아니라 리플렉션 순회 홉을 세고 있어, 중첩이
   * 0 인 이 쿼리가 450항은 통과하고 500항은 거부됐다({@code OrExpression}이 left-deep 이진 트리를 만들기
   * 때문). 800항으로 잡아 이전 임계를 확실히 넘긴다.
   */
  @Test
  void allows_flat_or_chain_that_previously_tripped_depth_limit() {
    StringBuilder sql = new StringBuilder("SELECT * FROM data.t WHERE ");
    for (int i = 0; i < 800; i++) {
      if (i > 0) {
        sql.append(" OR ");
      }
      sql.append("a = ").append(i);
    }
    assertThatCode(() -> new SqlValidator().validate(sql.toString())).doesNotThrowAnyException();
  }

  /**
   * 상한 자체는 살아 있어야 한다 — {@code StackOverflowError}(500)가 아니라 거부(400)로 끝난다(#387-4).
   *
   * <p>새 상한({@code MAX_TRAVERSAL_DEPTH} = 1500)을 확실히 넘기도록 5000항을 쓴다 — 실측한
   * {@code StackOverflowError} 임계(테스트 스레드 기준 최소 약 5,390)보다 한참 낮은 지점에서 먼저 거부돼야
   * 한다.
   */
  @Test
  void rejects_expression_beyond_traversal_limit_without_stack_overflow() {
    StringBuilder sql = new StringBuilder("SELECT * FROM data.t WHERE ");
    for (int i = 0; i < 5000; i++) {
      if (i > 0) {
        sql.append(" OR ");
      }
      sql.append("a = ").append(i);
    }
    assertThatThrownBy(() -> new SqlValidator().validate(sql.toString()))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("순회 깊이");
  }

  // --- 코드리뷰 C1 — CTE 별칭은 스코프 단위로 제외한다(전역 제외 아님) ---

  /**
   * 실측(수정 전, PG {@code search_path='data','public'}): {@code SELECT count(*) FROM (WITH role AS
   * (SELECT 1 AS x) SELECT x FROM role) s, role} 가 {@code public.role} 3행을 반환했다. 최초 구현은
   * CTE 별칭 "role"을 트리 전역에서 걷어 스코프 밖의 두 번째 {@code role}(진짜 테이블 참조)까지 {@link
   * SqlValidator#unqualifiedTableNames}에서 지워버렸다 — 애널리틱스 카탈로그 백스톱이 그 이름 자체를 못 봐서
   * 무동작이 됐다. 스코프 인식 수정 후에는 스코프 밖 참조가 목록에 남아야 한다.
   */
  @Test
  void unqualifiedTableNames_keepsOutOfScopeNameEvenIfSameAsCteAlias() {
    SqlValidator permissive = new SqlValidator("data", true);

    assertThat(
            permissive.unqualifiedTableNames(
                "SELECT count(*) FROM (WITH role AS (SELECT 1 AS x) SELECT x FROM role) s, role"))
        .as("스코프 밖의 진짜 'role' 참조는 CTE 별칭과 이름이 같아도 목록에 남아야 한다")
        .contains("role");
  }

  /** 같은 뿌리 — {@code pg_} 접두어 가드도 CTE 별칭 이름 충돌로 스코프 밖에서 뚫리면 안 된다. */
  @Test
  void rejects_pgPrefixedTable_evenWhenSameNameUsedAsCteAliasInDifferentScope() {
    SqlValidator strict = new SqlValidator();

    assertThatThrownBy(
            () ->
                strict.validate(
                    "SELECT count(*) FROM (WITH pg_roles AS (SELECT 1 AS x) SELECT x FROM"
                        + " pg_roles) s, pg_roles"))
        .isInstanceOf(UnsafeSqlException.class);
  }

  /** 양성 대조 — 스코프 안에서 CTE 를 참조하는 통상적인 쿼리는 여전히 통과해야 한다(회귀 방지). */
  @Test
  void allows_cteReference_withinItsOwnScope() {
    SqlValidator strict = new SqlValidator();

    assertThatCode(
            () ->
                strict.validate(
                    "SELECT x FROM (WITH cte AS (SELECT 1 AS x) SELECT x FROM cte) s"))
        .doesNotThrowAnyException();
  }

  // --- 코드리뷰 M3 — 윈도 호출(AnalyticExpression)도 함수 이름 검사 대상이다 ---

  /**
   * {@code SELECT count(*) OVER ()} 는 JSqlParser 5.0 에서 {@code Function} 이 아니라 {@code
   * AnalyticExpression} 을 만든다 — 허용목록/deny-list/{@code SIMPLE_IDENTIFIER} 이스케이프 검사 셋 다
   * 우회했다(수정 전). PG 가 {@code OVER} 뒤에 집계/윈도 함수를 요구해 지금 당장 시연 가능한 익스플로잇은
   * 아니지만, 사용자 정의 public 집계처럼 알려지지 않은 이름이 무검사로 통과하면 안 된다.
   */
  @Test
  void rejects_unknownAnalyticFunctionCall() {
    SqlValidator permissive = new SqlValidator("data", true);

    assertThatThrownBy(
            () -> permissive.validate("SELECT some_unknown_agg(a) OVER () FROM data.t"))
        .isInstanceOf(UnsafeSqlException.class);
  }

  /** 양성 대조 — 알려진 집계의 윈도 호출은 여전히 통과해야 한다. */
  @Test
  void allows_knownAggregateAsAnalyticExpression() {
    SqlValidator permissive = new SqlValidator("data", true);

    assertThatCode(() -> permissive.validate("SELECT count(*) OVER () FROM data.t"))
        .doesNotThrowAnyException();
  }

  // --- 코드리뷰 m4 — current_user 등 의사 상수는 Column 노드라 함수 검사를 안 탄다 ---

  /**
   * {@code current_user}/{@code session_user}/{@code current_catalog}/{@code current_schema} 는
   * 함수 호출이 아니라 한정자 없는 {@code Column} 으로 파싱돼 함수 허용목록을 아예 지나가지 않는다(수정
   * 전 실측: 두 정책 모두 통과해 DB 롤 이름 반환).
   */
  @Test
  void rejects_reservedPseudoConstants() {
    SqlValidator permissive = new SqlValidator("data", true);

    assertThatThrownBy(() -> permissive.validate("SELECT current_user"))
        .isInstanceOf(UnsafeSqlException.class);
    assertThatThrownBy(() -> permissive.validate("SELECT session_user"))
        .isInstanceOf(UnsafeSqlException.class);
    assertThatThrownBy(() -> permissive.validate("SELECT current_catalog"))
        .isInstanceOf(UnsafeSqlException.class);
    assertThatThrownBy(() -> permissive.validate("SELECT current_schema"))
        .isInstanceOf(UnsafeSqlException.class);
  }

  /** 양성 대조 — 한정된 컬럼 참조(t.current_user 같은 실제 컬럼명)는 의사 상수가 아니므로 막지 않는다. */
  @Test
  void allows_qualifiedColumnNamedLikeReservedConstant() {
    SqlValidator permissive = new SqlValidator("data", true);

    assertThatCode(() -> permissive.validate("SELECT t.current_user FROM data.t"))
        .doesNotThrowAnyException();
  }

  // --- 코드리뷰 m5 — 점이 2개 이상인 FQN(3파트 이름)은 거부한다 ---

  /**
   * {@code data.public.role} 은 {@code indexOf('.')}로 앞부분만 자르면 스키마 "data"(허용) + 이름
   * "public.role"(미검사)로 쪼개져 통과해버린다. 오늘은 PostgreSQL 이 cross-database reference 를 막아
   * 주지만, 이 클래스의 전제는 "AST 화이트리스트가 정본"이다.
   */
  @Test
  void rejects_threePartTableName() {
    SqlValidator strict = new SqlValidator();

    assertThatThrownBy(() -> strict.validate("SELECT * FROM data.public.role"))
        .isInstanceOf(UnsafeSqlException.class);
  }

  /** 양성 대조 — 따옴표 안의 점(테이블 이름 자체에 점이 포함된 경우)은 여러 파트로 세지 않는다. */
  @Test
  void allows_quotedTableNameContainingDot() {
    SqlValidator strict = new SqlValidator();

    assertThatCode(() -> strict.validate("SELECT * FROM data.\"my.table\""))
        .doesNotThrowAnyException();
  }

  /**
   * m5 후속 — 미한정 이름 자체에 점이 포함된 경우({@code "my.table"})는 "스키마 없음" 메시지로 거부돼야
   * 한다("허용되지 않는 스키마 참조"가 아니라). {@code indexOf('.')}가 따옴표 안의 점을 스키마 구분자로
   * 오인해 엉뚱한 메시지를 냈던 결함(수정 전)을 고정한다.
   */
  @Test
  void rejects_unqualifiedNameContainingDot_withCorrectMessage() {
    SqlValidator strict = new SqlValidator();
    SqlValidator permissive = new SqlValidator("data", true);

    assertThatThrownBy(() -> strict.validate("SELECT * FROM \"my.table\""))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("스키마가 없습니다")
        .hasMessageNotContaining("허용되지 않는 스키마 참조");
    assertThatCode(() -> permissive.validate("SELECT * FROM \"my.table\""))
        .as("미한정 이름은 permissive 에서 정책대로 통과해야 한다(점이 있다고 다르게 취급하면 안 됨)")
        .doesNotThrowAnyException();
  }

  // --- 코드리뷰 후속 B1 — 자기그림자(self-shadowing) CTE ---

  /**
   * PostgreSQL 은 비재귀 CTE 의 본문을 <b>그 CTE 자신의 스코프 밖</b>에서 해석한다. psql 실측:
   * {@code WITH "user" AS (SELECT * FROM "user") SELECT * FROM "user"} → 7900행대(= {@code
   * public."user"} 전체), {@code WITH pg_roles AS (SELECT * FROM pg_roles) SELECT * FROM pg_roles}
   * → 17행(pg_ 가드도 우회). 최초 스코프 구현은 노드 진입 즉시 전체 별칭을 push 해 이 케이스를 스코프
   * 안으로 오분류했다 — {@code unqualifiedTableNames()}가 빈 집합을 반환해 카탈로그 백스톱도 무력화됐다.
   */
  @Test
  void unqualifiedTableNames_doesNotHideSelfShadowingCteBody() {
    SqlValidator permissive = new SqlValidator("data", true);

    assertThat(
            permissive.unqualifiedTableNames(
                "WITH \"user\" AS (SELECT * FROM \"user\") SELECT * FROM \"user\""))
        .as("비재귀 CTE 본문 안의 자기 이름 참조는 스코프 밖(진짜 테이블)이므로 목록에 남아야 한다")
        .contains("user");
  }

  /** 같은 뿌리 — pg_ 가드는 자기그림자로도 뚫리면 안 된다(strict 모드에서 즉시 거부되는지 직접 확인). */
  @Test
  void rejects_selfShadowingCte_withPgPrefixedAlias() {
    SqlValidator strict = new SqlValidator();

    assertThatThrownBy(
            () -> strict.validate("WITH pg_roles AS (SELECT * FROM pg_roles) SELECT * FROM pg_roles"))
        .isInstanceOf(UnsafeSqlException.class);
  }

  /** 서브쿼리 안에 중첩해도 자기그림자가 재현되는지(스코프 계산이 중첩 깊이와 무관해야 한다). */
  @Test
  void unqualifiedTableNames_doesNotHideSelfShadowingCte_whenNestedInSubquery() {
    SqlValidator permissive = new SqlValidator("data", true);

    assertThat(
            permissive.unqualifiedTableNames(
                "SELECT * FROM (WITH \"user\" AS (SELECT * FROM \"user\") SELECT * FROM \"user\") s"))
        .contains("user");
  }

  /** 양성 대조 — {@code RECURSIVE}로 표시된 CTE 의 자기 참조는 합법이므로 계속 허용해야 한다. */
  @Test
  void allows_recursiveCteSelfReference() {
    SqlValidator strict = new SqlValidator();

    assertThatCode(
            () ->
                strict.validate(
                    "WITH RECURSIVE t(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM t WHERE n < 5)"
                        + " SELECT * FROM t"))
        .doesNotThrowAnyException();
  }

  /** 양성 대조 — 순차적으로 이전 CTE 를 참조하는(자기 자신 아님) 통상적인 다중 CTE 는 계속 허용해야 한다. */
  @Test
  void allows_sequentialCteReferencingEarlierCte() {
    SqlValidator strict = new SqlValidator();

    assertThatCode(
            () ->
                strict.validate(
                    "WITH a AS (SELECT * FROM data.t), b AS (SELECT * FROM a) SELECT * FROM b"))
        .doesNotThrowAnyException();
  }

  // --- 코드리뷰 후속 B2 — DML(WITH ... UPDATE/DELETE/INSERT) 회귀 ---

  /**
   * 이번 라운드가 만든 회귀 — {@code Update}/{@code Delete}/{@code Insert}는 {@code Select}가 아니라서
   * 그들이 소유한 {@code getWithItemsList()}가 스코프 push 대상에서 빠졌다(예전 전역 수집 방식은 이 형태를
   * 처리하고 있었다). {@code strict.validate()}가 정상 통과해야 한다(CTE 참조가 미한정 테이블로 오독되면
   * 안 됨) — 세 DML 타입 전부 확인.
   */
  @Test
  void allows_withUpdateDeleteInsert_cteNotMisreadAsUnqualifiedTable() {
    SqlValidator strict = new SqlValidator();

    assertThatCode(
            () ->
                strict.validate(
                    "WITH role AS (SELECT 1 AS x) UPDATE data.t SET a = 1 WHERE a IN (SELECT x"
                        + " FROM role)"))
        .as("WITH ... UPDATE")
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                strict.validate(
                    "WITH role AS (SELECT 1 AS x) DELETE FROM data.t WHERE a IN (SELECT x FROM"
                        + " role)"))
        .as("WITH ... DELETE")
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                strict.validate(
                    "WITH role AS (SELECT 1 AS x) INSERT INTO data.t (a) SELECT x FROM role"))
        .as("WITH ... INSERT")
        .doesNotThrowAnyException();
  }

  /** 같은 뿌리 — {@code unqualifiedTableNames()}도 DML-WITH 의 CTE 참조를 진짜 테이블로 새면 안 된다. */
  @Test
  void unqualifiedTableNames_excludesCteAlias_inDmlWithForms() {
    SqlValidator permissive = new SqlValidator("data", true);

    assertThat(
            permissive.unqualifiedTableNames(
                "WITH role AS (SELECT 1 AS x) UPDATE data.t SET a = 1 WHERE a IN (SELECT x FROM"
                    + " role)"))
        .as("role 은 스코프 안 CTE 참조라 목록에 없어야 한다")
        .doesNotContain("role");
  }

  // --- 코드리뷰 후속 m4 — current_role / 미한정 user ---

  @Test
  void rejects_currentRoleAndBareUser() {
    SqlValidator permissive = new SqlValidator("data", true);

    assertThatThrownBy(() -> permissive.validate("SELECT current_role"))
        .isInstanceOf(UnsafeSqlException.class);
    assertThatThrownBy(() -> permissive.validate("SELECT user"))
        .isInstanceOf(UnsafeSqlException.class);
  }

  /**
   * 양성 대조 — 인용된 {@code "user"}는 PG 가 의사 상수로 해석하지 않는다(psql 실측: {@code column "user"
   * does not exist}가 나는 것은 quoting 이 있을 때 뿐이다). 진짜 컬럼일 수 있으므로 막지 않는다.
   */
  @Test
  void allows_quotedUserColumnReference() {
    SqlValidator permissive = new SqlValidator("data", true);

    assertThatCode(() -> permissive.validate("SELECT \"user\" FROM data.t"))
        .doesNotThrowAnyException();
  }

  // --- 수정 라운드 1 F1 — INSERT 대상 컬럼/CTE 컬럼 별칭 카브아웃 되돌림(#387-3 재검토) ---
  // allows_user_as_insert_target_column / allows_user_as_cte_column_alias 는 삭제됐다. 그 두 자리는
  // PG 예약어라 맨몸 user 가 문법적으로 올 수 없다(PG16 실측 — RESERVED_PSEUDO_CONSTANTS 문서 참고).
  // "예외 처리가 필요하다"는 이전 전제가 틀렸으므로 카브아웃과 그 카브아웃을 초록으로 고정하던 테스트를
  // 함께 되돌린다.

  /** 값 자리의 맨몸 user 는 계속 거부한다 — 사용자 판정(차단 유지, #387-3). */
  @Test
  void still_rejects_bare_user_in_select_list() {
    assertThatThrownBy(() -> new SqlValidator().validate("SELECT user FROM data.t"))
        .isInstanceOf(UnsafeSqlException.class);
  }

  /** ORDER BY 의 맨몸 user 도 계속 거부한다(#387-3). */
  @Test
  void still_rejects_bare_user_in_order_by() {
    assertThatThrownBy(() -> new SqlValidator().validate("SELECT a FROM data.t ORDER BY user"))
        .isInstanceOf(UnsafeSqlException.class);
  }

  // --- 코드리뷰 후속 B1 보강 — WITH 절 내부의 "전방 참조" 가시성 (비재귀 vs RECURSIVE) ---

  /**
   * 비재귀 WITH 에서 <b>뒤에 정의된</b> 별칭을 앞 항목이 참조하면, PostgreSQL 은 그것을 CTE 가 아니라 진짜
   * 테이블로 해석한다. psql 실측({@code search_path='data','public'}):
   *
   * <pre>
   * WITH b AS (SELECT * FROM pg_roles), pg_roles AS (SELECT 1 x) SELECT count(*) FROM b;  -- 17행
   * </pre>
   *
   * 17행은 진짜 {@code pg_catalog.pg_roles} 다(뒤의 CTE 였다면 1행). 따라서 전방 참조를 스코프로 인정하면
   * B1 과 똑같은 구멍이 "자기 자신" 대신 "뒤 형제" 자리로 옮겨갈 뿐이다 — 앞→뒤 단조(monotone) 규칙이
   * 필요한 이유이고, 이 테스트가 그 규칙을 고정한다.
   */
  @Test
  void rejects_nonRecursiveCte_forwardReferenceResolvesToRealTable() {
    SqlValidator strict = new SqlValidator();

    assertThatThrownBy(
            () ->
                strict.validate(
                    "WITH b AS (SELECT * FROM pg_roles), pg_roles AS (SELECT 1 AS x)"
                        + " SELECT * FROM b"))
        .as("앞 항목이 참조한 pg_roles 는 뒤 CTE 가 아니라 진짜 카탈로그다 — pg_ 가드가 걸려야 한다")
        .isInstanceOf(UnsafeSqlException.class);
  }

  /** 같은 뿌리 — 카탈로그 백스톱이 보도록 미한정 이름 목록에도 전방 참조가 진짜 테이블로 남아야 한다. */
  @Test
  void unqualifiedTableNames_keepsForwardReferencedNameInNonRecursiveWith() {
    SqlValidator permissive = new SqlValidator("data", true);

    assertThat(
            permissive.unqualifiedTableNames(
                "WITH b AS (SELECT * FROM a), a AS (SELECT 1 AS x) SELECT * FROM b"))
        .as("a 는 b 의 본문 시점에 아직 정의 전이라 진짜 테이블 참조다")
        .contains("a");
  }

  /**
   * 반대 방향 양성 대조 — {@code RECURSIVE} 절에서는 전방 참조가 <b>실제로 CTE 를 가리킨다</b>. psql 실측:
   *
   * <pre>
   * WITH RECURSIVE b AS (SELECT * FROM pg_roles), pg_roles AS (SELECT 1 x) SELECT count(*) FROM b;
   * </pre>
   *
   * 이 쿼리는 <b>1행</b>을 반환했다(진짜 카탈로그였다면 17행) — 즉 {@code WITH RECURSIVE} 는 목록 전체가
   * 상호 참조 가능하다. 그래서 재귀 절에는 위의 단조 규칙을 적용하지 않는다(적용하면 합법 쿼리를 거부하는
   * 오탐이 된다). 이 테스트가 그 비대칭을 고정한다.
   */
  @Test
  void allows_recursiveCte_forwardReferenceBetweenSiblings() {
    SqlValidator strict = new SqlValidator();

    assertThatCode(
            () ->
                strict.validate(
                    "WITH RECURSIVE b AS (SELECT * FROM c), c AS (SELECT 1 AS x)"
                        + " SELECT * FROM b"))
        .as("RECURSIVE 절의 전방 참조는 PG 가 CTE 로 해석한다 — 거부하면 오탐")
        .doesNotThrowAnyException();
  }

  /**
   * 파싱 실패가 OS 스레드를 남기지 않음을 증명한다(#387-1).
   *
   * <p>왜 이 형태인가: jsqlparser 5.0 의 {@code parseStatements(String)} 은 내부에서 만든 {@code
   * ExecutorService} 를 {@code try/finally} 없이 종료해, 파싱이 예외를 던지면 non-daemon 코어 스레드가 영구히 남는다(이슈
   * 실측: 실패 200회 → 151스레드 잔존). 그래서 단언은 "여전히 거부한다"가 아니라 <b>스레드 수가 기준선으로 돌아온다</b>여야
   * 한다 — 전자는 누수에 대해 아무것도 말하지 않는다.
   *
   * <p>스레드 종료는 비동기라 즉시 단언하면 플레이크가 된다 → 최대 5초까지 폴링한다.
   */
  @Test
  void failed_parses_do_not_leak_os_threads() {
    SqlValidator validator = new SqlValidator();
    long baseline = liveThreadCount();

    // 파싱 자체가 실패하는(문법이 깨진) SQL 200회. 검증 단계에서 거부되는 SQL 이 아니라
    // 파서가 예외를 던지는 SQL 이어야 누수 경로를 탄다.
    for (int i = 0; i < 200; i++) {
      final int n = i;
      assertThatThrownBy(() -> validator.validate("SELECT FROM WHERE ((( " + n))
          .isInstanceOf(UnsafeSqlException.class);
    }

    long peak = baseline;
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      peak = liveThreadCount();
      if (peak <= baseline + 8) {
        break; // 여유 8개는 테스트 실행기·JIT 등 무관한 스레드 변동 허용치
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }

    assertThat(peak)
        .as("파싱 실패 200회 뒤 살아 있는 스레드 수가 기준선으로 돌아와야 한다(기준선 %d)", baseline)
        .isLessThanOrEqualTo(baseline + 8);
  }

  /** 이 JVM 에 살아 있는 스레드 수. 누수 판정의 유일한 관측 수단이다. */
  private static long liveThreadCount() {
    return Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive).count();
  }
}
