package com.smartfirehub.pipeline.service.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.pipeline.exception.UnsafeSqlException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SqlValidatorTest {

  private final SqlValidator validator = new SqlValidator();

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
   * <p>Task 3/4 가 배선할 모드가 바로 이 모드이며, 여기서 표기 변형이 오분류되면 "다른 에러"가 아니라 **통과**(=검증 우회)가 된다. 아래 다섯 형태는
   * PostgreSQL 이 동등하게 해석하는 {@code public."user"} 변형이다.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT * FROM \"public\".\"user\"",
        "SELECT * FROM public . \"user\"",
        "SELECT * FROM \"public\" . \"user\"",
        "SELECT * FROM PUBLIC.\"user\"",
        "SELECT * FROM smartfirehub.public.\"user\""
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

    // 미한정 — 테이블과 동일 정책(permissive 만 허용)
    assertThatThrownBy(() -> strict.validate("SELECT nextval('my_seq')"))
        .isInstanceOf(UnsafeSqlException.class);
    assertThatCode(() -> permissive.validate("SELECT nextval('my_seq')"))
        .doesNotThrowAnyException();

    // 계산된 표현식(리터럴 아님) — 항상 거부
    assertThatThrownBy(() -> permissive.validate("SELECT nextval(seq_name_column)"))
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
   */
  @Test
  void rejects_schemaViolation_buriedBeyondMaxDepth() {
    SqlValidator permissive = new SqlValidator("data", true);

    assertThatThrownBy(
            () -> permissive.validate(deepNestedWhereIn(169, "SELECT a FROM public.usr")))
        .isInstanceOf(UnsafeSqlException.class);
  }

  /** 같은 뿌리 — {@code SELECT ... INTO}를 깊이 묻어도 M4 차단이 유지돼야 한다(10단에선 이미 거부됨). */
  @Test
  void rejects_selectInto_buriedBeyondMaxDepth() {
    SqlValidator permissive = new SqlValidator("data", true);
    StringBuilder sql = new StringBuilder("SELECT a FROM data.t WHERE a IN (");
    for (int i = 0; i < 169; i++) {
      sql.append("SELECT a FROM data.t WHERE a IN (");
    }
    sql.append("SELECT a INTO public.pwned FROM data.t");
    sql.append(")".repeat(170));

    assertThatThrownBy(() -> permissive.validate(sql.toString())).isInstanceOf(UnsafeSqlException.class);
  }

  /**
   * 같은 뿌리 — {@link SqlValidator#unqualifiedTableNames}이 깊이 상한 초과 시 <b>빈 집합을 조용히
   * 반환</b>하는 쪽이 더 위험했다(호출부인 애널리틱스 카탈로그 대조가 "미한정 참조 없음"으로 잘못 읽는다).
   * 이제 예외로 거부해 호출부가 그 사실을 알 수 있다.
   */
  @Test
  void unqualifiedTableNames_throwsInsteadOfSilentlyEmpty_beyondMaxDepth() {
    SqlValidator permissive = new SqlValidator("data", true);

    assertThatThrownBy(
            () ->
                permissive.unqualifiedTableNames(
                    deepNestedWhereIn(169, "SELECT a FROM some_unqualified_table_xyz")))
        .isInstanceOf(UnsafeSqlException.class);
  }

  /** 양성 대조 — 상한 근처에도 못 미치는 정상 중첩 쿼리는 여전히 통과해야 한다. */
  @Test
  void allows_ordinaryNestedSubquery_wellWithinMaxDepth() {
    SqlValidator permissive = new SqlValidator("data", true);

    assertThatCode(
            () -> permissive.validate("SELECT a FROM data.t WHERE a IN (SELECT a FROM data.t)"))
        .doesNotThrowAnyException();
  }
}
