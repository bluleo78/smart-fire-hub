package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartfirehub.support.TurkishDefaultLocale;
import org.jooq.DataType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 터키어 기본 로캘에서도 파이프라인 타입 판정이 Locale.ROOT 와 같은지(WD-653, WD-635) — Spring 컨텍스트 없이 순수 함수만 본다.
 *
 * <p>DB 타입 이름은 실행 시 jOOQ 가 PG 결과에서 돌려주는 문자열({@code int8}·{@code timestamp} 등)을 그대로 고정하려고 {@link
 * DataType} 목으로 준다 — {@code SQLDataType} 상수는 {@code bigint} 처럼 다른 이름을 돌려준다.
 */
@TurkishDefaultLocale
class CaseConversionLocaleTest {

  @ParameterizedTest
  @CsvSource({
    "int8, INTEGER",
    "int4, INTEGER",
    "bigint, INTEGER",
    "serial, INTEGER",
    "timestamp, TIMESTAMP",
    "timestamp with time zone, TIMESTAMP",
    "date, DATE",
    "numeric, DECIMAL",
    "boolean, BOOLEAN",
    "varchar, TEXT",
    "text, TEXT"
  })
  void sqlColumnProbe_mapsDbTypeSameAsRoot_underTurkishLocale(String dbType, String expected) {
    DataType<?> dataType = mock(DataType.class);
    when(dataType.getTypeName()).thenReturn(dbType);

    assertThat(SqlColumnProbe.mapJooqTypeToAppType(dataType)).isEqualTo(expected);
  }

  /**
   * PYTHON 출력 컬럼 타입의 소문자 표기(i 가 든 integer·timestamp 포함)가 터키어 로캘에서도 허용된다 — 예전 Spring 테스트에서 옮긴 사례.
   */
  @ParameterizedTest
  @CsvSource({"integer", "timestamp", "decimal", "boolean", "date", "text", "INTEGER"})
  void pipelineService_acceptsLowerCaseOutputType_underTurkishLocale(String type) {
    assertThat(PipelineService.isValidOutputColumnType(type)).isTrue();
  }

  @Test
  void pipelineService_rejectsUnknownOrNullOutputType() {
    assertThat(PipelineService.isValidOutputColumnType("geometry")).isFalse();
    assertThat(PipelineService.isValidOutputColumnType(null)).isFalse();
  }
}
