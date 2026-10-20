"""Tibero(ODBC) 값 변환 순수 헬퍼.

목표: psycopg2 가 같은 PG 타입에 주는 Python 타입과 같게 만들어, 응답 JSON 이 백엔드와
무관하게 같게 한다(Decimal, date, datetime, bool, int). 변환은 값이 아니라 **컬럼 메타**로
판정한다 — 값으로 판정하면 우연히 정수인 NUMBER(10,2) 가 int 로 바뀐다.

주의: 실서버 pyodbc 의 오류 메시지 형식은 미검증이므로 여기서 문자열을 가정하지 않는다.
"""
from datetime import date, datetime, timezone
from decimal import Decimal
from typing import Any, Sequence


def convert_value(value: Any, type_code: Any, precision: int | None, scale: int | None) -> Any:
    """컬럼 메타(precision/scale)에 따라 값 하나를 PG 와 같은 Python 타입으로 바꾼다."""
    if value is None:
        return None
    # bool 은 int 의 하위 타입이라 먼저 걸러 그대로 둔다.
    if isinstance(value, (Decimal, int, float)) and not isinstance(value, bool):
        # NUMBER(1,0) 은 Boolean 으로 쓰이는 컬럼이다(PG boolean 에 대응). 드라이버가 int 로 줘도 같다.
        if precision == 1 and scale == 0 and not isinstance(value, float):
            return value != 0
        if isinstance(value, Decimal) or (isinstance(scale, int) and scale > 0):
            d = value if isinstance(value, Decimal) else Decimal(str(value))
            if scale == 0:
                # scale 0 이어도 드라이버·메타가 소수를 줄 수 있다(예: 계산식 컬럼). 값을 잘라 내면 데이터가 바뀌므로
                # 정수값일 때만 int 로 바꾸고, 소수는 Decimal 그대로 둔다.
                return int(d) if d == d.to_integral_value() else d
            if scale is None or scale < 0:
                # 무제약 NUMBER(COUNT 등)는 정수값일 때만 int 로, 소수는 Decimal 유지.
                return int(d) if d == d.to_integral_value() else d
            # 양수 scale: PG numeric(p,s) 처럼 자릿수를 고정한다(1.5 -> "1.500000", JSON 문자열이 같아진다).
            return d.quantize(Decimal(1).scaleb(-scale))
        return value
    # Oracle 계열 DATE 는 시각을 가진 datetime 으로 오지만 scale 0 이면 날짜 컬럼이다.
    # 자정 TIMESTAMP(scale>0)는 date 로 바꾸지 않는다.
    if isinstance(value, datetime) and scale == 0:
        return value.date()
    return value


def convert_row(row: Sequence[Any], description: Sequence[tuple]) -> tuple:
    """pyodbc description ``(name, type_code, display_size, internal_size, precision, scale, null_ok)``
    으로 한 행의 각 값을 변환한다."""
    return tuple(
        convert_value(v, d[1], d[4], d[5]) for v, d in zip(row, description)
    )


def bind_value(value: Any) -> Any:
    """INSERT 바인드 값 정규화: bool → 0/1(Tibero 에 BOOLEAN 이 없다), tz 인식 datetime → naive UTC."""
    if isinstance(value, bool):
        return 1 if value else 0
    if isinstance(value, datetime) and value.tzinfo is not None:
        # WD-77 TODO: PG 운영 세션 시간대가 UTC 라는 전제의 변환이다. 첫 Tibero 쓰기(WD-77)에서
        # 운영 PG 세션 time zone 을 반드시 검증하고, 다르면 이 헬퍼 한 곳만 고친다.
        return value.astimezone(timezone.utc).replace(tzinfo=None)
    return value
