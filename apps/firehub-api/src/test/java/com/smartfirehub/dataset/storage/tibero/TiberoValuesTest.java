package com.smartfirehub.dataset.storage.tibero;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.storage.DataStoreFeatureUnsupportedException;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Tibero 값 바인딩·읽기 변환과 타입 매핑 단위 테스트(JDBC 객체는 Mockito). */
class TiberoValuesTest {

  private final PreparedStatement ps = mock(PreparedStatement.class);

  @Test
  void bind_booleanAs1Or0() throws Exception {
    TiberoValues.bind(ps, 1, true);
    TiberoValues.bind(ps, 2, false);
    verify(ps).setInt(1, 1);
    verify(ps).setInt(2, 0);
  }

  @Test
  void bind_localDateAsSqlDate() throws Exception {
    TiberoValues.bind(ps, 1, LocalDate.of(2024, 3, 5));
    verify(ps).setDate(1, java.sql.Date.valueOf(LocalDate.of(2024, 3, 5)));
  }

  @Test
  void bind_localDateTimeAsTimestamp() throws Exception {
    LocalDateTime t = LocalDateTime.of(2024, 3, 5, 1, 2, 3);
    TiberoValues.bind(ps, 1, t);
    verify(ps).setTimestamp(1, Timestamp.valueOf(t));
  }

  @Test
  void bind_nullWithTypeHint() throws Exception {
    TiberoValues.bind(ps, 1, null, Types.VARCHAR);
    verify(ps).setNull(1, Types.VARCHAR);
  }

  @Test
  void bind_nullUsesSetNull() throws Exception {
    TiberoValues.bind(ps, 1, null);
    verify(ps).setNull(1, Types.NULL);
  }

  @Test
  void bind_bigDecimalAndLongAsIs() throws Exception {
    TiberoValues.bind(ps, 1, new BigDecimal("1.5"));
    TiberoValues.bind(ps, 2, 7L);
    TiberoValues.bind(ps, 3, "x");
    verify(ps).setObject(1, new BigDecimal("1.5"));
    verify(ps).setObject(2, 7L);
    verify(ps).setObject(3, "x");
  }

  private ResultSet rs(int type, String typeName, int precision, int scale) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    ResultSetMetaData md = mock(ResultSetMetaData.class);
    when(rs.getMetaData()).thenReturn(md);
    when(md.getColumnType(1)).thenReturn(type);
    when(md.getColumnTypeName(1)).thenReturn(typeName);
    when(md.getPrecision(1)).thenReturn(precision);
    when(md.getScale(1)).thenReturn(scale);
    return rs;
  }

  @Test
  void read_number1_0_isBoolean() throws Exception {
    ResultSet rs = rs(Types.NUMERIC, "NUMBER", 1, 0);
    when(rs.getInt(1)).thenReturn(1);
    assertThat(TiberoValues.read(rs, 1)).isEqualTo(true);
  }

  @Test
  void read_number1_0_nullIsNull() throws Exception {
    ResultSet rs = rs(Types.NUMERIC, "NUMBER", 1, 0);
    when(rs.getInt(1)).thenReturn(0);
    when(rs.wasNull()).thenReturn(true);
    assertThat(TiberoValues.read(rs, 1)).isNull();
  }

  @Test
  void read_number19_0_isLong() throws Exception {
    ResultSet rs = rs(Types.NUMERIC, "NUMBER", 19, 0);
    when(rs.getLong(1)).thenReturn(42L);
    assertThat(TiberoValues.read(rs, 1)).isEqualTo(42L);
  }

  @Test
  void read_number18_6_isBigDecimal() throws Exception {
    ResultSet rs = rs(Types.NUMERIC, "NUMBER", 18, 6);
    when(rs.getBigDecimal(1)).thenReturn(new BigDecimal("1.500000"));
    assertThat(TiberoValues.read(rs, 1)).isEqualTo(new BigDecimal("1.500000"));
  }

  @Test
  void read_date_isLocalDate() throws Exception {
    ResultSet rs = rs(Types.DATE, "DATE", 0, 0);
    when(rs.getDate(1)).thenReturn(java.sql.Date.valueOf(LocalDate.of(2024, 1, 2)));
    assertThat(TiberoValues.read(rs, 1)).isEqualTo(LocalDate.of(2024, 1, 2));
  }

  @Test
  void read_oracleDateReportedAsTimestamp_isLocalDate() throws Exception {
    ResultSet rs = rs(Types.TIMESTAMP, "DATE", 0, 0);
    when(rs.getTimestamp(1)).thenReturn(Timestamp.valueOf(LocalDateTime.of(2024, 1, 2, 9, 0)));
    assertThat(TiberoValues.read(rs, 1)).isEqualTo(LocalDate.of(2024, 1, 2));
  }

  @Test
  void read_timestamp_isLocalDateTime() throws Exception {
    ResultSet rs = rs(Types.TIMESTAMP, "TIMESTAMP", 0, 6);
    LocalDateTime t = LocalDateTime.of(2024, 1, 2, 3, 4, 5);
    when(rs.getTimestamp(1)).thenReturn(Timestamp.valueOf(t));
    assertThat(TiberoValues.read(rs, 1)).isEqualTo(t);
  }

  @Test
  void read_varchar_isString() throws Exception {
    ResultSet rs = rs(Types.VARCHAR, "VARCHAR2", 0, 0);
    when(rs.getString(1)).thenReturn("hi");
    assertThat(TiberoValues.read(rs, 1)).isEqualTo("hi");
  }

  @Test
  void read_null_isNull() throws Exception {
    ResultSet rs = rs(Types.TIMESTAMP, "TIMESTAMP", 0, 6);
    when(rs.getTimestamp(1)).thenReturn(null);
    assertThat(TiberoValues.read(rs, 1)).isNull();
    ResultSet rs2 = rs(Types.NUMERIC, "NUMBER", 19, 0);
    when(rs2.getLong(1)).thenReturn(0L);
    when(rs2.wasNull()).thenReturn(true);
    assertThat(TiberoValues.read(rs2, 1)).isNull();
  }

  @Test
  void readRow_keysAreLowercaseLabels() throws Exception {
    ResultSet rs = rs(Types.VARCHAR, "VARCHAR2", 0, 0);
    ResultSetMetaData md = rs.getMetaData();
    when(md.getColumnCount()).thenReturn(1);
    when(md.getColumnLabel(1)).thenReturn("_UPDATED_AT");
    when(rs.getString(1)).thenReturn("v");
    Map<String, Object> row = TiberoValues.readRow(rs);
    assertThat(row).containsEntry("_updated_at", "v").hasSize(1);
  }

  @Test
  void columnDdl_mapping() {
    assertThat(TiberoDialect.columnDdl("TEXT", null)).isEqualTo("VARCHAR2(4000)");
    // VARCHAR(n) 은 PG 처럼 문자 수로 센다(CHAR 의미, A7). TEXT 는 바이트 한도 그대로.
    assertThat(TiberoDialect.columnDdl("VARCHAR", 100)).isEqualTo("VARCHAR2(100 CHAR)");
    assertThat(TiberoDialect.columnDdl("INTEGER", null)).isEqualTo("NUMBER(19)");
    assertThat(TiberoDialect.columnDdl("DECIMAL", null)).isEqualTo("NUMBER(18,6)");
    assertThat(TiberoDialect.columnDdl("BOOLEAN", null)).isEqualTo("NUMBER(1)");
    assertThat(TiberoDialect.columnDdl("DATE", null)).isEqualTo("DATE");
    assertThat(TiberoDialect.columnDdl("TIMESTAMP", null)).isEqualTo("TIMESTAMP");
  }

  @Test
  void indexKeyBytes_perLogicalType() {
    assertThat(TiberoDialect.indexKeyBytes("TEXT", null)).isEqualTo(4000);
    // AL32UTF8 CHAR 의미: n × 4, 4000 상한
    assertThat(TiberoDialect.indexKeyBytes("VARCHAR", 100)).isEqualTo(400);
    assertThat(TiberoDialect.indexKeyBytes("VARCHAR", 2000)).isEqualTo(4000);
    assertThat(TiberoDialect.indexKeyBytes("INTEGER", null)).isEqualTo(22);
    assertThat(TiberoDialect.indexKeyBytes("BOOLEAN", null)).isEqualTo(22);
    assertThat(TiberoDialect.indexKeyBytes("DATE", null)).isEqualTo(7);
    assertThat(TiberoDialect.indexKeyBytes("TIMESTAMP", null)).isEqualTo(11);
  }

  @Test
  void notFoundConstraint_acceptsDropAndRenameCodes() {
    // DROP CONSTRAINT 는 2443, RENAME CONSTRAINT 는 23292 로 "없음" 을 알린다 — 둘 다 무시 대상이어야 한다.
    assertThat(
            TiberoDialect.NotFound.CONSTRAINT.matches(
                new java.sql.SQLException("x", "42000", 23292)))
        .isTrue();
    assertThat(
            TiberoDialect.NotFound.CONSTRAINT.matches(
                new RuntimeException(new java.sql.SQLException("ORA-02443: cannot drop"))))
        .isTrue();
    // 다른 종류의 코드는 맞지 않는다.
    assertThat(
            TiberoDialect.NotFound.CONSTRAINT.matches(new java.sql.SQLException("x", "42000", 942)))
        .isFalse();
    // 메시지 표기는 다섯 자리 0 채움(ORA-00942)으로 본다 — 코드가 비어 있어도 인식한다.
    assertThat(
            TiberoDialect.NotFound.TABLE.matches(
                new java.sql.SQLException("ORA-00942: table or view does not exist")))
        .isTrue();
    assertThat(TiberoErrors.hasCode(new java.sql.SQLException("ORA-942 short"), 942)).isFalse();
  }

  @Test
  void bind_integerAsIs() throws Exception {
    TiberoValues.bind(ps, 1, 5);
    verify(ps).setObject(1, 5);
  }

  @Test
  void readRow_turkishLocale_keyIsId() throws Exception {
    Locale original = Locale.getDefault();
    try {
      Locale.setDefault(new Locale("tr"));
      ResultSet rs = rs(Types.VARCHAR, "VARCHAR2", 0, 0);
      ResultSetMetaData md = rs.getMetaData();
      when(md.getColumnCount()).thenReturn(1);
      when(md.getColumnLabel(1)).thenReturn("ID");
      when(rs.getString(1)).thenReturn("v");
      assertThat(TiberoValues.readRow(rs)).containsOnlyKeys("id");
    } finally {
      Locale.setDefault(original);
    }
  }

  @Test
  void columnDdl_aliasesAndUnknown() {
    assertThat(TiberoDialect.columnDdl("BIGINT", null)).isEqualTo("NUMBER(19)");
    assertThat(TiberoDialect.columnDdl("NUMERIC", null)).isEqualTo("NUMBER(18,6)");
    assertThatThrownBy(() -> TiberoDialect.columnDdl(null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TiberoDialect.columnDdl("JSONB", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void columnDdl_geometry_unsupported422() {
    assertThatThrownBy(() -> TiberoDialect.columnDdl("GEOMETRY", null))
        .isInstanceOfSatisfying(
            DataStoreFeatureUnsupportedException.class,
            e -> {
              assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
              assertThat(e.code()).isEqualTo("DATA_STORE_FEATURE_UNSUPPORTED");
              assertThat(e.getMessage()).endsWith(": Tibero 저장소에서 아직 지원하지 않습니다");
            });
  }

  @Test
  void columnDdl_varcharOver4000_400() {
    assertThatThrownBy(() -> TiberoDialect.columnDdl("VARCHAR", 4001))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
