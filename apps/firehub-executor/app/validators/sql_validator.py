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


# 출력 비우기 선행 문장(pre-statement)이 가질 수 있는 유일한 형태.
# 스키마는 테넌트 데이터 스키마(data, data_t<번호>)만 허용하고, 테이블명 문자 집합은
# API DataTableService.validateName(`[a-z][a-z0-9_]*`)의 소문자 규칙과 맞춘다.
_PRE_STATEMENT_PATTERN = re.compile(r'^DELETE FROM "(data|data_t\d+)"\."[a-z0-9_]+"$')


def validate_pre_statement(stmt: str) -> None:
    """출력 비우기 선행 문장 검증.

    본 쿼리 validate() 는 사용자가 작성한 SQL 을 대상으로 하는 부분 문자열 블록리스트라
    느슨하다. 반면 pre-statement 는 API 서버가 기계적으로 생성하며 절대 사용자 입력이
    아니므로, 여기서는 화이트리스트 방식으로 플랫폼이 만드는 정확히 그 형태
    (조건 없는 `DELETE FROM "<schema>"."<table>"`, 식별자는 큰따옴표) 만 허용하고
    나머지는 전부 거부한다 (세미콜론 체이닝·WHERE 절·TRUNCATE 등).
    """
    # fullmatch 를 쓴다. match + `$` 는 문자열 끝 직전의 개행 하나를 허용해
    # `DELETE FROM "data"."out"\n` 같은 형태가 통과하기 때문이다(정확한 형태만 허용한다는 취지에 어긋남).
    if not _PRE_STATEMENT_PATTERN.fullmatch(stmt or ""):
        raise SqlValidationError(f"허용되지 않은 선행 문장입니다: {stmt}")
