"""Tibero SQL 조립 헬퍼 단위 테스트."""
import pytest

from app.db.backends.tibero_sql import (
    connection_string,
    insert_sql,
    quote,
    validate_name,
)


def test_validate_name_uppercases():
    assert validate_name("order_items") == "ORDER_ITEMS"


@pytest.mark.parametrize("bad", ["Sales", "1a", "a-b", 'a"b', "a" * 31, "", "a\n", "a\nb", "sales\n"])
def test_validate_name_rejects(bad):
    with pytest.raises(ValueError):
        validate_name(bad)


def test_quote_rejects_quote_and_nul():
    assert quote("A") == '"A"'
    for bad in ['A"B', "A\x00B"]:
        with pytest.raises(ValueError):
            quote(bad)


def test_insert_sql_shape():
    assert insert_sql("GENIA_T7", "sales", ["region", "amount"]) == (
        'INSERT INTO "GENIA_T7"."SALES" ("ID", "REGION", "AMOUNT", "_UPDATED_AT") '
        'VALUES ("GENIA_T7"."SQ_683D10AA89".NEXTVAL, ?, ?, SYSTIMESTAMP)'
    )


@pytest.mark.parametrize("cols", [["id", "x"], ["_updated_at"], ["created_at"], ["import_id"]])
def test_insert_sql_rejects_system_columns(cols):
    with pytest.raises(ValueError):
        insert_sql("GENIA_T7", "sales", cols)


def test_insert_sql_rejects_bad_column_name():
    with pytest.raises(ValueError):
        insert_sql("GENIA_T7", "sales", ['x"; DROP'])


def test_connection_string_braces_every_value():
    s = connection_string("h", 8629, "tibero", "GENIA_T7", "a;b}c")
    assert s.startswith("DRIVER={Tibero};SERVER={h};PORT={8629};DB={tibero};UID={GENIA_T7};")
    assert "PWD={a;b}}c};" in s
