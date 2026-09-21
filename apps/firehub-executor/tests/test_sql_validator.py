import pytest

from app.validators.sql_validator import SqlValidationError, validate, validate_pre_statement


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


def test_merge_and_incremental_sql_passes_blocklist():
    # 이번 기능이 실제로 보내는 MERGE 형태(ON CONFLICT ... DO UPDATE ... IS DISTINCT FROM)와
    # _updated_at 컬럼 참조가, 부분 문자열 블록리스트에 의해 오탐되지 않는지 고정한다.
    # 오탐되면 이 테스트가 실패로 알려준다 — Task 5(MergeSqlBuilder)가 참조할 계약이다.
    #
    # Fix round 2 — 서브쿼리 내용을 자기 줄에 얹도록 MergeSqlBuilder 를 바꿨다(후행 한 줄 주석이
    # 닫는 괄호를 삼키는 문제 대응). 실제 생성 형태와 어긋나지 않도록 개행을 그대로 반영한다 —
    # validate() 는 부분 문자열 검사라 개행 자체는 결과에 영향이 없지만(그래서 이 테스트는 여전히
    # 통과했었다), 이 고정 문자열이 "실제로 보내는 형태"를 대변한다는 주석의 취지를 지키려면
    # 최신 형태를 반영해야 한다.
    validate(
        'INSERT INTO "data"."out" AS t ("code", "name") '
        'SELECT "code", "name" FROM (\n'
        'SELECT code, name FROM "data"."src" '
        "WHERE _updated_at >= '-infinity'::timestamptz\n"
        ') AS _src '
        'ON CONFLICT ("code") DO UPDATE SET "name" = EXCLUDED."name" '
        'WHERE (t."name") IS DISTINCT FROM (EXCLUDED."name")'
    )


def test_merge_sql_with_trailing_line_comment_passes_blocklist():
    # Fix round 2, must 1 — 사용자 SELECT 가 후행 한 줄 주석으로 끝나는 형태도 MergeSqlBuilder 가
    # 만들어 보낼 수 있다(서브쿼리를 자기 줄에 얹어 주석이 닫는 괄호를 삼키지 않도록 고쳤다).
    # 이 형태도 블록리스트를 그대로 통과해야 한다 — "--" 자체는 차단 키워드가 아니다.
    validate(
        'INSERT INTO "data"."out" AS t ("code") '
        'SELECT "code" FROM (\n'
        'SELECT code FROM "data"."src" -- trailing comment\n'
        ') AS _src '
        'ON CONFLICT ("code") DO NOTHING'
    )


@pytest.mark.parametrize("stmt", ['DELETE FROM "data"."out_1"', 'DELETE FROM "data_t12"."abc"'])
def test_valid_pre_statement(stmt):
    validate_pre_statement(stmt)


@pytest.mark.parametrize(
    "stmt",
    [
        'DELETE FROM "public"."user"',
        'DELETE FROM "data"."out"; DROP TABLE x',
        'TRUNCATE "data"."out"',
        'DELETE FROM "data"."out" WHERE id > 0',
        # 끝 개행: re.match + `$` 는 이것을 통과시킨다. fullmatch 로 막혀 있어야 한다.
        'DELETE FROM "data"."out"\n',
    ],
)
def test_invalid_pre_statement(stmt):
    with pytest.raises(SqlValidationError):
        validate_pre_statement(stmt)
