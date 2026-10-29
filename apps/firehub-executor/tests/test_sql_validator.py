import pytest

from app.validators.sql_validator import (
    SqlValidationError,
    output_lock_key,
    validate,
    validate_pre_statement,
    validate_pre_statement_pairing,
)


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


@pytest.mark.parametrize(
    "stmt",
    [
        'DELETE FROM "data"."out_1"',
        'DELETE FROM "data_t12"."abc"',
        # 잠금 전용 형태(WD-426) — 아무 행도 지우지 않고 출력 직렬화 잠금만 잡게 한다.
        'DELETE FROM "data"."out_1" WHERE false',
        'DELETE FROM "data_t12"."abc" WHERE false',
    ],
)
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
        # 잠금 전용 접미사(WD-426)는 ` WHERE false` 리터럴 그대로만 받는다 — 다른 조건·대소문자·체이닝·뒤 개행은 거부.
        'DELETE FROM "data"."out" WHERE false OR true',
        'DELETE FROM "data"."out" WHERE 1=1',
        'DELETE FROM "data"."out" WHERE FALSE',
        'DELETE FROM "data"."out" WHERE FALSE;DELETE FROM "data"."out"',
        'DELETE FROM "data"."out" WHERE false;DELETE FROM "data"."out"',
        'DELETE FROM "data"."out" WHERE false; DROP TABLE x',
        'DELETE FROM "data"."out" WHERE false\n',
        'DELETE FROM "data"."out" WHERE false WHERE false',
        'DELETE FROM "data"."out"  WHERE false',
        'DELETE FROM "public"."user" WHERE false',
    ],
)
def test_invalid_pre_statement(stmt):
    with pytest.raises(SqlValidationError):
        validate_pre_statement(stmt)
    # 키도 같은 검증으로 거부한다 — 검증을 거치지 않은 문자열에서 잠금 키를 뽑지 않는다.
    with pytest.raises(SqlValidationError):
        output_lock_key(stmt)


@pytest.mark.parametrize("schema", ["data", "data_t12"])
def test_lock_only_pre_statement_uses_same_lock_key_as_delete_all(schema):
    # 잠금 전용 실행(자기 출력 다시 채우기)과 전체 비우기 실행이 같은 출력에서 서로 배타여야 한다(#731) — 키가 같아야 한다.
    # 키는 API SqlScriptExecutor.outputLockKey 와 같은 "<스키마>"."<테이블>" 이다(접미사가 섞이지 않는다).
    delete_all = f'DELETE FROM "{schema}"."out_1"'
    assert output_lock_key(delete_all + " WHERE false") == output_lock_key(delete_all) == f'"{schema}"."out_1"'


def _reload(schema="data", clear_table="out_1", insert_table=None, select='SELECT code, name FROM data."out_1"'):
    """API OutputClearStatement.reloadFromOwnOutput 이 만드는 자기 출력 다시 채우기 문장(WD-426, OutputClearStatementTest 가 고정)."""
    insert_table = insert_table or clear_table
    return (
        f'WITH "_fh_self_src" AS (\n{select}\n), "_fh_self_clear" AS (DELETE FROM {schema}."{clear_table}" RETURNING 1) '
        f'INSERT INTO {schema}."{insert_table}" ("code", "name") SELECT * FROM "_fh_self_src" '
        'WHERE (SELECT count(*) FROM "_fh_self_clear") >= 0'
    )


@pytest.mark.parametrize("schema", ["data", "data_t12"])
def test_reload_statement_pairs_with_lock_only_of_same_output(schema):
    # 다시 채우기 문장 + 같은 출력의 잠금 전용 선행 문장 하나 — 러너가 보내는 유일한 조합이다.
    validate_pre_statement_pairing(_reload(schema), [f'DELETE FROM "{schema}"."out_1" WHERE false'])


def test_reload_statement_keeps_last_tail_when_select_contains_tail_text():
    # 사용자 SELECT 안에 꼬리 문구가 문자열로 들어 있어도 마지막 꼬리를 고른다(API reloadSourceOf 의 lastIndexOf 와 같다).
    select = "SELECT code, '\n), \"_fh_self_clear\" AS (DELETE FROM data.\"x\" RETURNING 1)' AS name FROM data.\"out_1\""
    validate_pre_statement_pairing(_reload(select=select), ['DELETE FROM "data"."out_1" WHERE false'])


@pytest.mark.parametrize(
    "pre_statements",
    [
        # 잠금 없음 — 겹친 두 실행이 같은 옛 행을 읽어 각자 넣는다(#731).
        [],
        # 전체 비우기 — 사용자 SELECT 가 비운 테이블을 읽는다.
        ['DELETE FROM "data"."out_1"'],
        # 다른 출력·다른 스키마의 잠금.
        ['DELETE FROM "data"."other" WHERE false'],
        ['DELETE FROM "data_t12"."out_1" WHERE false'],
        # 잠금 전용 + 그 밖의 선행 문장.
        ['DELETE FROM "data"."out_1" WHERE false', 'DELETE FROM "data"."out_1"'],
        ['DELETE FROM "data"."out_1" WHERE false', 'DELETE FROM "data"."out_1" WHERE false'],
    ],
)
def test_reload_statement_without_its_lock_only_pre_statement_is_rejected(pre_statements):
    with pytest.raises(SqlValidationError, match="같은 출력의 잠금 전용 선행 문장"):
        validate_pre_statement_pairing(_reload(), pre_statements)


@pytest.mark.parametrize(
    "query",
    [
        # 일반 INSERT·DML — REPLACE 인데 비워지지 않아 이전 출력에 새 결과가 덧붙는다.
        'INSERT INTO "data"."out_1" ("code") SELECT code FROM "data"."src"',
        'UPDATE "data"."out_1" SET name = name',
        # 다시 채우기와 비슷하지만 정확한 형태가 아닌 문장 — 비우기·적재 대상이 다르거나, 꼬리의 비우기 우선 조건이 빠졌다.
    ],
)
def test_lock_only_pre_statement_with_non_reload_query_is_rejected(query):
    with pytest.raises(SqlValidationError, match="잠금 전용 선행 문장은 같은 출력을 다시 채우는 문장과만"):
        validate_pre_statement_pairing(query, ['DELETE FROM "data"."out_1" WHERE false'])


_ALMOST_RELOADS = [
    # 다시 채우기 머리로 시작하지만 정확한 형태가 아닌 문장 — 비우기·적재 대상이 다르거나, 비우기 우선 조건이 빠졌거나, 스키마 인용·끝이 다르다.
    _reload(clear_table="out_1", insert_table="other"),
    _reload().replace(' WHERE (SELECT count(*) FROM "_fh_self_clear") >= 0', ""),
    _reload().replace('data."out_1" RETURNING 1', '"data"."out_1" RETURNING 1'),
    _reload() + ";",
]


@pytest.mark.parametrize("query", _ALMOST_RELOADS)
@pytest.mark.parametrize(
    "pre_statements",
    [[], ['DELETE FROM "data"."out_1"'], ['DELETE FROM "data"."out_1" WHERE false']],
)
def test_reload_head_without_exact_shape_is_rejected_with_any_pre_statements(query, pre_statements):
    # 실행기는 본 쿼리를 파싱하지 않으므로, 예약 머리로 시작하면 짝과 무관하게 정확한 형태만 받는다(API 로컬 경로는 파서가 거부).
    with pytest.raises(SqlValidationError, match="출력 다시 채우기 문장의 형태가 아닙니다"):
        validate_pre_statement_pairing(query, pre_statements)


def test_lock_only_pre_statement_with_reload_of_other_output_is_rejected():
    # 잠금은 out_1 인데 다시 채우는 출력은 other — 다시 채우기 쪽 규칙(같은 출력의 잠금 하나)으로 거부된다.
    with pytest.raises(SqlValidationError, match="같은 출력의 잠금 전용 선행 문장"):
        validate_pre_statement_pairing(
            _reload(clear_table="other", select='SELECT code, name FROM data."other"'),
            ['DELETE FROM "data"."out_1" WHERE false'],
        )


@pytest.mark.parametrize("pre_statements", [[], ['DELETE FROM "data"."out_1"']])
def test_ordinary_query_without_lock_only_pre_statement_passes_pairing(pre_statements):
    # 잠금 전용 문장이 없는 기존 조합(선행 없음·전체 비우기 + INSERT)은 그대로 통과한다.
    validate_pre_statement_pairing('INSERT INTO "data"."out_1" ("code") SELECT code FROM "data"."src"', pre_statements)
