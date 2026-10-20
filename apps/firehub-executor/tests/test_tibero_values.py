"""Tibero 값 변환 헬퍼 단위 테스트(드라이버 불필요)."""
from datetime import date, datetime
from decimal import Decimal
from zoneinfo import ZoneInfo

import pytest

from app.db.backends.tibero_values import bind_value, convert_row, convert_value


@pytest.mark.parametrize(
    "value,type_code,precision,scale,expected",
    [
        (Decimal("1"), Decimal, 1, 0, True),
        (Decimal("0"), Decimal, 1, 0, False),
        (Decimal("42"), Decimal, 19, 0, 42),
        (Decimal("5"), Decimal, 38, 0, 5),
        (Decimal("5"), Decimal, None, None, 5),
        (Decimal("5"), Decimal, 0, -127, 5),
        (Decimal("2.5"), Decimal, None, None, Decimal("2.5")),
        (Decimal("5.000000"), Decimal, 18, 6, Decimal("5.000000")),
        (1.5, float, 15, None, 1.5),
        # scale 0 이어도 소수 값은 잘라 내지 않고 Decimal 그대로 둔다.
        (Decimal("12.75"), Decimal, 10, 0, Decimal("12.75")),
        (Decimal("12.00"), Decimal, 10, 0, 12),
    ],
)
def test_convert_value_numbers(value, type_code, precision, scale, expected):
    out = convert_value(value, type_code, precision, scale)
    assert out == expected
    assert type(out) is type(expected)


@pytest.mark.parametrize(
    "value,precision,scale,expected_str",
    [
        (Decimal("1.5"), 18, 6, "1.500000"),
        (Decimal("5.000000"), 18, 6, "5.000000"),
        (Decimal("5"), 18, 2, "5.00"),
        (1.5, 18, 6, "1.500000"),
        (3, 18, 2, "3.00"),
    ],
)
def test_convert_value_positive_scale_is_quantized(value, precision, scale, expected_str):
    """PG numeric(p,s) 는 자릿수를 고정해 JSON 이 "1.500000" 이 된다 — str 로 단언한다."""
    out = convert_value(value, Decimal, precision, scale)
    assert isinstance(out, Decimal)
    assert str(out) == expected_str


@pytest.mark.parametrize("value,expected", [(1, True), (0, False)])
def test_convert_value_number1_int_becomes_bool(value, expected):
    out = convert_value(value, int, 1, 0)
    assert out is expected


def test_convert_value_temporal():
    d = convert_value(datetime(2024, 1, 2, 0, 0), datetime, 19, 0)
    assert d == date(2024, 1, 2) and type(d) is date
    t = convert_value(datetime(2024, 1, 2, 0, 0), datetime, 26, 6)
    assert t == datetime(2024, 1, 2, 0, 0) and type(t) is datetime
    assert convert_value(date(2024, 1, 2), date, 10, 0) == date(2024, 1, 2)


@pytest.mark.parametrize("v", [None, "한글", b"\x01"])
def test_convert_value_passthrough(v):
    assert convert_value(v, str, 10, 0) == v


def test_convert_row_applies_per_column():
    desc = [
        ("a", Decimal, None, None, 1, 0, True),
        ("b", Decimal, None, None, 18, 6, True),
        ("c", str, None, None, None, None, True),
    ]
    row = convert_row((Decimal("1"), Decimal("1.50"), "x"), desc)
    assert row == (True, Decimal("1.500000"), "x")
    assert str(row[1]) == "1.500000"
    assert type(row[0]) is bool


def test_bind_value():
    assert bind_value(True) == 1 and type(bind_value(True)) is int
    assert bind_value(False) == 0
    assert bind_value(datetime(2024, 1, 2, 9, 0, tzinfo=ZoneInfo("Asia/Seoul"))) == datetime(2024, 1, 2, 0, 0)
    n = datetime(2024, 1, 2, 9, 0)
    assert bind_value(n) is n
    assert bind_value(Decimal("1.5")) == Decimal("1.5")
    assert bind_value("s") == "s"
