package com.smartfirehub.dataimport.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.TimeZone;
import org.junit.jupiter.api.Test;

class LegacyExcelDataFormatterTest {

  private final LegacyExcelDataFormatter fmt = new LegacyExcelDataFormatter();

  /** 2025-01-21 의 엑셀 1900 체계 일련값. */
  private static final double SERIAL_2025_01_21 = 45678d;

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

  /** WD-252: 지수 표기·Long 포화·부동소수 꼬리 없이 엑셀이 보여 주는 값과 같은 평문으로 바뀐다. */
  @Test
  void numbers_are_plain_text_without_exponent_saturation_or_float_tail() {
    assertThat(fmt.formatRawCellContents(1e20, 0, "General")).isEqualTo("100000000000000000000");
    assertThat(fmt.formatRawCellContents(-1e19, 0, "General")).isEqualTo("-10000000000000000000");
    assertThat(fmt.formatRawCellContents(12345678.5, 0, "General")).isEqualTo("12345678.5");
    assertThat(fmt.formatRawCellContents(0.00001, 0, "General")).isEqualTo("0.00001");
    assertThat(fmt.formatRawCellContents(0.1 + 0.2, 0, "General")).isEqualTo("0.3");
    assertThat(fmt.formatRawCellContents(-0.0, 0, "General")).isEqualTo("0");
    // 2^53 이하 정수는 15자리 반올림 없이 그대로 — 예전에도 정확히 저장되던 값이 바뀌지 않게
    assertThat(fmt.formatRawCellContents(1234567890123456d, 0, "General"))
        .isEqualTo("1234567890123456");
    assertThat(fmt.formatRawCellContents(9007199254740992d, 0, "General"))
        .isEqualTo("9007199254740992");
    assertThat(fmt.formatRawCellContents(-12, 0, "General")).isEqualTo("-12");
    assertThat(fmt.formatRawCellContents(0.15, 9, "0%")).isEqualTo("0.15");
  }

  @Test
  void date_only_format_at_midnight_becomes_yyyy_mm_dd() {
    assertThat(fmt.formatRawCellContents(SERIAL_2025_01_21, 14, "m/d/yy")).isEqualTo("2025-01-21");
    assertThat(fmt.formatRawCellContents(SERIAL_2025_01_21, 164, "yyyy\\-mm\\-dd"))
        .isEqualTo("2025-01-21");
  }

  @Test
  void date_time_or_hidden_time_becomes_full_date_time() {
    // 날짜시각 서식의 자정은 시각까지
    assertThat(fmt.formatRawCellContents(SERIAL_2025_01_21, 22, "m/d/yy h:mm"))
        .isEqualTo("2025-01-21 00:00:00");
    // 날짜만 보이는 서식이라도 숨은 시각이 있으면 값이 바뀌지 않게 시각까지
    assertThat(fmt.formatRawCellContents(SERIAL_2025_01_21 + 0.25, 14, "m/d/yy"))
        .isEqualTo("2025-01-21 06:00:00");
    assertThat(fmt.formatRawCellContents(SERIAL_2025_01_21 + 0.5, 164, "yyyy-mm-dd hh:mm:ss"))
        .isEqualTo("2025-01-21 12:00:00");
  }

  /** WD-252: h:mm 서식 0.5 는 1899-12-31T11:27:52(옛 서울 시간대 규칙)가 아니라 시각만. */
  @Test
  void time_only_value_becomes_hh_mm_ss() {
    assertThat(fmt.formatRawCellContents(0.5, 20, "h:mm")).isEqualTo("12:00:00");
    assertThat(fmt.formatRawCellContents(0.0, 20, "h:mm")).isEqualTo("00:00:00");
    // 10:30:14.9995 → 초 반올림
    assertThat(fmt.formatRawCellContents((10 * 3600 + 30 * 60 + 14.9995) / 86400d, 21, "h:mm:ss"))
        .isEqualTo("10:30:15");
  }

  @Test
  void use_1904_windowing_shifts_serial_by_1462_days() {
    LegacyExcelDataFormatter fmt1904 = new LegacyExcelDataFormatter(true);
    assertThat(fmt1904.formatRawCellContents(SERIAL_2025_01_21 - 1462, 14, "m/d/yy"))
        .isEqualTo("2025-01-21");
    LegacyExcelDataFormatter later = new LegacyExcelDataFormatter();
    later.setUse1904Windowing(true);
    assertThat(later.formatRawCellContents(SERIAL_2025_01_21 - 1462, 14, "m/d/yy"))
        .isEqualTo("2025-01-21");
  }

  @Test
  void negative_serial_with_date_format_falls_back_to_number() {
    assertThat(fmt.formatRawCellContents(-1, 14, "m/d/yy")).isEqualTo("-1");
  }

  /** JVM 기본 시간대가 달라도 결과가 같아야 한다(예전엔 systemDefault 를 거쳐 서울에서만 +8:27:52 가 끼었다). */
  @Test
  void result_does_not_depend_on_default_time_zone() {
    TimeZone original = TimeZone.getDefault();
    try {
      for (String zone : new String[] {"Asia/Seoul", "UTC", "America/New_York"}) {
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        LegacyExcelDataFormatter f = new LegacyExcelDataFormatter();
        assertThat(f.formatRawCellContents(0.5, 20, "h:mm")).as(zone).isEqualTo("12:00:00");
        assertThat(f.formatRawCellContents(SERIAL_2025_01_21, 14, "m/d/yy"))
            .as(zone)
            .isEqualTo("2025-01-21");
        assertThat(f.formatRawCellContents(SERIAL_2025_01_21 + 0.25, 22, "m/d/yy h:mm"))
            .as(zone)
            .isEqualTo("2025-01-21 06:00:00");
      }
    } finally {
      TimeZone.setDefault(original);
    }
  }

  @Test
  void has_time_part_ignores_literals_and_colour_brackets() {
    assertThat(LegacyExcelDataFormatter.hasTimePart("yyyy-mm-dd")).isFalse();
    assertThat(LegacyExcelDataFormatter.hasTimePart("[Red]yyyy\"년\" m\"월\" d\"일\"")).isFalse();
    assertThat(LegacyExcelDataFormatter.hasTimePart("[$-412]yyyy-mm-dd")).isFalse();
    assertThat(LegacyExcelDataFormatter.hasTimePart("yyyy-mm-dd h:mm")).isTrue();
    assertThat(LegacyExcelDataFormatter.hasTimePart("[h]:mm")).isTrue();
    assertThat(LegacyExcelDataFormatter.hasTimePart("m/d/yy AM/PM")).isTrue();
  }

  @Test
  void error_cell_marker_round_trips_to_display() {
    String marker = LegacyExcelDataFormatter.errorCellFromXssf("ERROR:#DIV/0!");
    assertThat(marker).contains("\u0000").isNotEqualTo("#DIV/0!");
    assertThat(LegacyExcelDataFormatter.errorCodeOf(marker)).isEqualTo("#DIV/0!");
    // 검증이 trim 해도 표식이 살아 있어야 한다
    assertThat(LegacyExcelDataFormatter.errorCodeOf(marker.trim())).isEqualTo("#DIV/0!");
    assertThat(LegacyExcelDataFormatter.toDisplay(marker)).isEqualTo("#DIV/0!");
    // 일반 문자열은 오류 셀이 아니다
    assertThat(LegacyExcelDataFormatter.errorCodeOf("ERROR:#DIV/0!")).isNull();
    assertThat(LegacyExcelDataFormatter.toDisplay("hello")).isEqualTo("hello");
    assertThat(LegacyExcelDataFormatter.toDisplay(null)).isNull();
  }
}
