package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.TurkishDefaultLocale;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 터키어 기본 로캘에서도 INSERT 자리표시자 캐스트가 Locale.ROOT 와 같은지(WD-653) — Spring 컨텍스트 없이 순수 함수만 본다. */
@TurkishDefaultLocale
class PostgresDataRowStoreLocaleTest {

  @ParameterizedTest
  @CsvSource({
    "timestamp, ?::timestamp",
    "bigint, ?::bigint",
    "integer, ?::bigint",
    "int8, ?::bigint",
    "boolean, ?::boolean",
    "'numeric(18,6)', ?::numeric",
    "date, ?::date",
    "'geometry(Geometry, 4326)', 'ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)'",
    "text, ?"
  })
  void insertPlaceholder_castsSameAsRoot_underTurkishLocale(String type, String expected) {
    assertThat(PostgresDataRowStore.insertPlaceholder("col", Map.of("col", type)))
        .isEqualTo(expected);
  }
}
