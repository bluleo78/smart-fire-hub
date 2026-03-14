import pytest

from app.validators.sql_validator import SqlValidationError, validate


def test_valid_select_passes():
    validate("SELECT * FROM users")


def test_valid_insert_passes():
    validate("INSERT INTO users (name) VALUES ('Alice')")


def test_valid_update_passes():
    validate("UPDATE users SET name = 'Bob' WHERE id = 1")


def test_valid_delete_passes():
    validate("DELETE FROM users WHERE id = 1")


def test_empty_sql_rejected():
    with pytest.raises(SqlValidationError, match="비어 있습니다"):
        validate("")


def test_empty_whitespace_rejected():
    with pytest.raises(SqlValidationError, match="비어 있습니다"):
        validate("   ")


def test_drop_table_blocked():
    with pytest.raises(SqlValidationError, match="DROP"):
        validate("DROP TABLE users")


def test_alter_table_blocked():
    with pytest.raises(SqlValidationError, match="ALTER"):
        validate("ALTER TABLE users ADD COLUMN email TEXT")


def test_create_table_blocked():
    with pytest.raises(SqlValidationError, match="CREATE"):
        validate("CREATE TABLE new_table (id INT)")


def test_grant_blocked():
    with pytest.raises(SqlValidationError, match="GRANT"):
        validate("GRANT SELECT ON users TO bob")


def test_revoke_blocked():
    with pytest.raises(SqlValidationError, match="REVOKE"):
        validate("REVOKE SELECT ON users FROM bob")


def test_set_role_blocked():
    with pytest.raises(SqlValidationError, match="SET ROLE"):
        validate("SET ROLE admin")


def test_reset_role_blocked():
    with pytest.raises(SqlValidationError, match="RESET ROLE"):
        validate("RESET ROLE")


def test_copy_blocked():
    with pytest.raises(SqlValidationError, match="COPY"):
        validate("COPY users TO '/tmp/out.csv'")


def test_pg_read_file_blocked():
    with pytest.raises(SqlValidationError, match="PG_READ_FILE"):
        validate("SELECT pg_read_file('/etc/passwd')")


def test_dblink_blocked():
    with pytest.raises(SqlValidationError, match="DBLINK"):
        validate("SELECT dblink('host=remote', 'SELECT 1')")


def test_do_block_blocked():
    with pytest.raises(SqlValidationError, match="DO \\$"):
        validate("DO $$ BEGIN RAISE NOTICE 'x'; END $$")


def test_case_insensitive():
    with pytest.raises(SqlValidationError):
        validate("dRoP TABLE x")


def test_create_extension_blocked():
    with pytest.raises(SqlValidationError, match="CREATE"):
        validate("CREATE EXTENSION dblink")


def test_set_session_authorization_blocked():
    with pytest.raises(SqlValidationError, match="SET SESSION AUTHORIZATION"):
        validate("SET SESSION AUTHORIZATION alice")


def test_lo_import_blocked():
    with pytest.raises(SqlValidationError, match="LO_IMPORT"):
        validate("SELECT lo_import('/etc/passwd')")
