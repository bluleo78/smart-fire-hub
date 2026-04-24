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
