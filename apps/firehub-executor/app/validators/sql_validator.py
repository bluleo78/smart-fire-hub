import re

BLOCKED_KEYWORDS = [
    "DROP",
    "ALTER",
    "CREATE",
    "TRUNCATE",
    "GRANT",
    "REVOKE",
    "SET ROLE",
    "RESET ROLE",
    "SET SESSION AUTHORIZATION",
    "COPY",
    r"\COPY",
    "CREATE EXTENSION",
    "LOAD",
    "PG_READ_FILE",
    "PG_READ_BINARY_FILE",
    "PG_LS_DIR",
    "LO_IMPORT",
    "LO_EXPORT",
    "DBLINK",
    "DBLINK_CONNECT",
    "DO $$",
    "DO $",
    # 시스템 스키마 직접 접근 차단 — public."user" 등 메타데이터 테이블 접근 방지 (#90)
    "PUBLIC.",
    "INFORMATION_SCHEMA",
    "PG_CATALOG",
    "PG_EXECUTE",
]


class SqlValidationError(Exception):
    pass


def validate(script_content: str) -> None:
    if not script_content or not script_content.strip():
        raise SqlValidationError("SQL 스크립트가 비어 있습니다.")

    upper_script = script_content.upper()
    for keyword in sorted(BLOCKED_KEYWORDS, key=len, reverse=True):
        if keyword.upper() in upper_script:
            raise SqlValidationError(
                f"SQL 스크립트에 차단된 키워드가 포함되어 있습니다: {keyword}"
            )


# 출력 비우기 선행 문장(pre-statement)이 가질 수 있는 두 형태.
# 스키마는 테넌트 데이터 스키마(data, data_t<번호>)만 허용하고, 테이블명 문자 집합은
# API DataTableService.validateName(`[a-z][a-z0-9_]*`)의 소문자 규칙과 맞춘다.
# - `DELETE FROM "<schema>"."<table>"` — 출력 전체 비우기.
# - `DELETE FROM "<schema>"."<table>" WHERE false` — 잠금 전용(WD-426). 자기 출력을 읽는 REPLACE 는 비우기를 본 문장 안에서
#   하므로(API OutputClearStatement.reloadFromOwnOutput) 선행 문장으로는 아무 행도 지우지 않고 아래 output_lock_key 의
#   출력 직렬화 잠금(#731)만 잡게 한다. 접미사는 이 리터럴 그대로만 받는다(대소문자·공백·다른 조건·뒤 개행은 거부 — fullmatch).
_LOCK_ONLY_SUFFIX = " WHERE false"
_PRE_STATEMENT_PATTERN = re.compile(
    r'^DELETE FROM "(data|data_t\d+)"\."([a-z0-9_]+)"(?:' + re.escape(_LOCK_ONLY_SUFFIX) + r')?$'
)


def validate_pre_statement(stmt: str) -> None:
    """출력 비우기 선행 문장 검증.

    본 쿼리 validate() 는 사용자가 작성한 SQL 을 대상으로 하는 부분 문자열 블록리스트라
    느슨하다. 반면 pre-statement 는 API 서버가 기계적으로 생성하며 절대 사용자 입력이
    아니므로, 여기서는 화이트리스트 방식으로 플랫폼이 만드는 정확히 그 형태
    (조건 없는 `DELETE FROM "<schema>"."<table>"`, 식별자는 큰따옴표) 와 그 뒤에 정확히
    ` WHERE false` 만 붙은 잠금 전용 형태(WD-426) 만 허용하고 나머지는 전부 거부한다
    (세미콜론 체이닝·다른 WHERE 절·TRUNCATE 등).
    """
    # fullmatch 를 쓴다. match + `$` 는 문자열 끝 직전의 개행 하나를 허용해
    # `DELETE FROM "data"."out"\n` 같은 형태가 통과하기 때문이다(정확한 형태만 허용한다는 취지에 어긋남).
    if not _PRE_STATEMENT_PATTERN.fullmatch(stmt or ""):
        raise SqlValidationError(f"허용되지 않은 선행 문장입니다: {stmt}")


def output_lock_key(stmt: str) -> str:
    """선행 문장(출력 비우기 DELETE)이 가리키는 테이블의 직렬화 잠금 키 ``"<스키마>"."<테이블>"``.

    선행 문장에 적힌 한정 이름을 따옴표까지 그대로 쓴다 — API(SqlScriptExecutor.outputLockKey)와 같은 키다.
    잠금 전용 형태(` WHERE false`, WD-426)도 같은 키다 — 전체 비우기 실행과 자기 출력 다시 채우기 실행이 같은 출력에서
    서로 배타여야 하기 때문이다(키는 정규식의 스키마·테이블 그룹으로만 만들어 접미사가 섞이지 않는다).

    advisory 잠금 키 공간은 데이터베이스 전체가 공유하므로 스키마(테넌트)까지 포함한다.
    형태가 맞지 않는 문장은 validate_pre_statement 와 같은 오류로 거부한다 — 검증을 거치지 않은
    문자열에서 키를 뽑는 일이 없게 한다.
    """
    match = _PRE_STATEMENT_PATTERN.fullmatch(stmt or "")
    if not match:
        raise SqlValidationError(f"허용되지 않은 선행 문장입니다: {stmt}")
    return f'"{match.group(1)}"."{match.group(2)}"'


# 자기 출력 다시 채우기 문장(WD-426)의 정확한 형태 — API OutputClearStatement.reloadFromOwnOutput 이 만드는 그대로다.
#   WITH "_fh_self_src" AS (\n<사용자 SELECT>\n), "_fh_self_clear" AS (DELETE FROM <스키마>."<테이블>" RETURNING 1)
#   INSERT INTO <스키마>."<테이블>" ("<컬럼>", ...) SELECT * FROM "_fh_self_src" WHERE (SELECT count(*) FROM "_fh_self_clear") >= 0
# (실제로는 꼬리가 한 줄이다.) 본문의 스키마는 API DataSchema.qualify 처럼 인용 없이 오고, 비우기와 적재 대상은 같은 테이블이어야
# 한다(역참조). 사용자 SELECT 는 탐욕 매칭(DOTALL)이라 안쪽에 같은 꼬리 문구가 있어도 마지막 꼬리를 고른다 — API reloadSourceOf 의
# lastIndexOf 와 같다. 컬럼은 인용 식별자만 받는다.
_RELOAD_HEAD = 'WITH "_fh_self_src" AS (\n'
_RELOAD_STATEMENT_PATTERN = re.compile(
    r'WITH "_fh_self_src" AS \(\n(?P<select>.*)\n\), "_fh_self_clear" AS \(DELETE FROM '
    r'(?P<schema>data|data_t\d+)\."(?P<table>[a-z0-9_]+)" RETURNING 1\) '
    r'INSERT INTO (?P=schema)\."(?P=table)" \("(?:[^"]|"")+"(?:, "(?:[^"]|"")+")*\) '
    r'SELECT \* FROM "_fh_self_src" WHERE \(SELECT count\(\*\) FROM "_fh_self_clear"\) >= 0',
    re.DOTALL,
)


def validate_pre_statement_pairing(query: str, pre_statements) -> None:
    """잠금 전용 선행 문장과 자기 출력 다시 채우기 문장이 서로의 짝으로만 오는지 검증한다(WD-426, 양방향).

    API SqlScriptExecutor 의 같은 규칙(requireReloadOfLockedOutput 과 그 반대쪽)을 이 실행기에도 둔다. 각 선행 문장의 형태는
    validate_pre_statement 가 먼저 본다 — 여기서는 조합만 본다.

    - 본 쿼리가 다시 채우기 형태면 선행 문장은 같은 ``"<스키마>"."<테이블>"`` 의 잠금 전용 문장 정확히 하나여야 한다. 잠금이 없으면 같은
      출력에 겹친 두 실행이 같은 옛 행을 읽어 각자 넣어 행이 중복되고(#731), 전체 비우기가 함께 오면 사용자 SELECT 가 비운 테이블을 읽는다.
    - 선행 문장에 잠금 전용 문장이 있으면 본 쿼리는 그 출력을 다시 채우는 형태여야 한다. 일반 INSERT·DML 과 오면 REPLACE 인데 출력이
      비워지지 않아 이전 출력에 새 결과가 덧붙는다(행 중복).
    - 다시 채우기 머리(예약 CTE 이름)로 시작하는데 정확한 형태가 아니면 거부한다 — 이 실행기는 본 쿼리를 파싱하지 않아(블록리스트만)
      꼬리만 살짝 다른 데이터 변경 CTE 가 전체 비우기와 함께 통과할 수 있다. API 로컬 경로는 같은 문장을 파서가 거부한다.
    """
    statements = list(pre_statements or ())
    reload = _RELOAD_STATEMENT_PATTERN.fullmatch(query or "")
    if reload is None and (query or "").startswith(_RELOAD_HEAD):
        raise SqlValidationError("출력 다시 채우기 문장의 형태가 아닙니다.")
    if reload is not None:
        expected = f'DELETE FROM "{reload.group("schema")}"."{reload.group("table")}"{_LOCK_ONLY_SUFFIX}'
        if statements != [expected]:
            raise SqlValidationError("출력 다시 채우기 문장에는 같은 출력의 잠금 전용 선행 문장이 하나 있어야 합니다.")
        return
    if any((stmt or "").endswith(_LOCK_ONLY_SUFFIX) for stmt in statements):
        raise SqlValidationError("잠금 전용 선행 문장은 같은 출력을 다시 채우는 문장과만 함께 실행할 수 있습니다.")
