package com.smartfirehub.dataimport.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Date;
import org.apache.poi.ss.usermodel.DateUtil;
import org.junit.jupiter.api.Test;

class LegacyExcelDataFormatterTest {

  private final LegacyExcelDataFormatter fmt = new LegacyExcelDataFormatter();

  @Test
  void integer_number_becomes_long_string() {
    String s = fmt.formatRawCellContents(123.0, -1, "General");
    assertThat(s).isEqualTo("123");
  }

  @Test
  void fractional_number_becomes_double_string() {
    String s = fmt.formatRawCellContents(1.5, -1, "General");
    assertThat(s).isEqualTo("1.5");
  }

  @Test
  void date_format_becomes_iso_local_date_time() {
    // Excel 날짜 1.0 == 1900-01-01 (POI base)
    double serial = DateUtil.getExcelDate(new Date(0L)); // 1970-01-01 00:00 UTC
    String s = fmt.formatRawCellContents(serial, 14, "m/d/yyyy");
    // ISO LocalDateTime, 시스템 타임존 의존 — 날짜 부분만 검증
    assertThat(s).contains("T").matches("\\d{4}-\\d{2}-\\d{2}T.*");
  }
}
