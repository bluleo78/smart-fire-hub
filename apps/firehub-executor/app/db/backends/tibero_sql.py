"""Tibero SQL 조립용 순수 헬퍼(드라이버·네트워크 없음).

api ``TiberoNames`` / ``TiberoDmlSql`` / ``TiberoDialect`` 의 미러다. 규약이 갈라지면 같은
테이블을 api 와 executor 가 다른 이름으로 가리키게 되므로 공용 픽스처
(tibero-executor-vectors.json)로 양쪽을 고정한다.
"""
import hashlib
import re
from typing import List

# api TiberoDialect.SESSION_INIT_SQL 과 문자열까지 같아야 한다(픽스처로 고정).
# 서버 기본 NLS_DATE_FORMAT 이 YYYY/MM/DD 라서 세션 초기화가 필수다.
SESSION_INIT_SQL = (
    "BEGIN EXECUTE IMMEDIATE 'ALTER SESSION SET NLS_DATE_FORMAT=''YYYY-MM-DD'''; "
    "EXECUTE IMMEDIATE 'ALTER SESSION SET NLS_TIMESTAMP_FORMAT=''YYYY-MM-DD HH24:MI:SS.FF6'''; "
    "EXECUTE IMMEDIATE 'ALTER SESSION SET NLS_NUMERIC_CHARACTERS=''.,'''; END;"
)

# odbcinst.ini 의 드라이버 섹션 이름(이미지 레이어, Task 6 에서 등록).
ODBC_DRIVER_NAME = "Tibero"

# Tibero 식별자 최대 길이.
MAX_IDENTIFIER_LENGTH = 30

# 사용자 컬럼으로 받을 수 없는 시스템 컬럼(ID·시스템 타임스탬프는 INSERT 문이 직접 채운다).
SYSTEM_COLUMNS = frozenset({"id", "import_id", "created_at", "_updated_at"})

# ODBC 연결 문자열 키 이름. 실서버에서 키가 다르면 이 한 곳만 고친다.
_CONN_KEYS = {
    "driver": "DRIVER",
    "host": "SERVER",
    "port": "PORT",
    "database": "DB",
    "username": "UID",
    "password": "PWD",
}

_NAME_RE = re.compile(r"[a-z][a-z0-9_]*")


def validate_name(name: str) -> str:
    """메타데이터 이름을 검증하고 대문자 물리 이름을 돌려준다.

    fullmatch 를 쓰는 이유: ``$`` 는 끝의 개행 앞에서도 매치돼 "sales\\n" 이 통과한다.
    소문자 식별자만 허용해 인용 식별자의 대소문자 혼동과 주입을 원천 차단한다.
    """
    if not isinstance(name, str) or not _NAME_RE.fullmatch(name):
        raise ValueError(f"허용되지 않는 식별자입니다(소문자 영문으로 시작, 소문자·숫자·밑줄만 가능): {name!r}")
    if len(name) > MAX_IDENTIFIER_LENGTH:
        raise ValueError(f"식별자는 {MAX_IDENTIFIER_LENGTH}자 이하여야 합니다: {name!r}")
    return name.upper()


def quote(physical: str) -> str:
    """물리 이름을 큰따옴표로 인용한다. 따옴표·NUL 이 있으면 거부한다."""
    if '"' in physical or "\x00" in physical:
        raise ValueError(f"식별자에 따옴표 또는 NUL 문자를 쓸 수 없습니다: {physical!r}")
    return f'"{physical}"'


def sequence_name(schema: str, table: str) -> str:
    """테이블의 ID 시퀀스 이름. api ``TiberoNames.sequenceName`` 의 미러다.

    ``SQ_`` + SHA-256("SQ|SCHEMA|TABLE", 대문자) hex 앞 10자(대문자).
    """
    key = "|".join(p.upper() for p in ("SQ", schema, table))
    return "SQ_" + hashlib.sha256(key.encode("utf-8")).hexdigest()[:10].upper()


def insert_sql(schema: str, table: str, columns: List[str]) -> str:
    """일괄 INSERT 문을 만든다(api ``TiberoDmlSql.insert`` 와 같은 규칙).

    ``"ID"`` 는 시퀀스 NEXTVAL, ``"_UPDATED_AT"`` 은 SYSTIMESTAMP 로 채우고 ``CREATED_AT`` 은
    컬럼 기본값에 맡긴다. 값 자리는 ODBC 위치 바인드(``?``)다.
    """
    for c in columns:
        if c in SYSTEM_COLUMNS:
            raise ValueError(f"시스템 컬럼은 직접 삽입할 수 없습니다: {c}")
    physical_schema = schema.upper()
    physical_table = validate_name(table)
    user_cols = [quote(validate_name(c)) for c in columns]
    qschema = quote(physical_schema)
    seq = f'{qschema}.{quote(sequence_name(physical_schema, table))}.NEXTVAL'
    col_list = ", ".join(['"ID"', *user_cols, '"_UPDATED_AT"'])
    values = ", ".join([seq, *["?"] * len(user_cols), "SYSTIMESTAMP"])
    return f'INSERT INTO {qschema}.{quote(physical_table)} ({col_list}) VALUES ({values})'


def _braced(value: object) -> str:
    """ODBC 연결 문자열 값을 중괄호로 감싼다(내부의 ``}`` 는 ``}}`` 로 이스케이프)."""
    return "{" + str(value).replace("}", "}}") + "}"


def connection_string(host: str, port: int, database: str, username: str, password: str) -> str:
    """ODBC 연결 문자열. 모든 값을 중괄호로 감싸 ``;`` ``}`` 가 든 비밀번호도 안전하게 한다."""
    parts = [
        (_CONN_KEYS["driver"], ODBC_DRIVER_NAME),
        (_CONN_KEYS["host"], host),
        (_CONN_KEYS["port"], port),
        (_CONN_KEYS["database"], database),
        (_CONN_KEYS["username"], username),
        (_CONN_KEYS["password"], password),
    ]
    return "".join(f"{k}={_braced(v)};" for k, v in parts)
