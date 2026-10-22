package com.smartfirehub.dbexecutor.sql;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 결과 행 값·컬럼명 변환(스펙 §4.2). 규칙은 api 와 공유하는 고정값 파일({@code tibero-executor-vectors.json} 의 {@code
 * columnNames}·{@code valueConversions})로 고정한다.
 *
 * <ul>
 *   <li>컬럼명: 전부 대문자면 소문자로(따옴표 없는 Tibero 식별자는 대문자로 돌아오므로 PG 와 같은 이름이 된다), 섞여 있으면 사용자가 따옴표로 지정한 별칭이라
 *       그대로 둔다 — WD-75 Python {@code _result_column_name} 과 같다.
 *   <li>NUMBER: scale&gt;0 이면 그 자릿수로 고정(PG numeric(p,s) 처럼 1.5 → 1.500000), scale≤0(정수 컬럼·맨
 *       NUMBER)은 정수값이면 정수(long 범위 밖이면 BigDecimal), 소수면 뒤 0 을 뗀 값. 값을 자르지 않는다 — 메타가 scale 0 이어도 소수가
 *       오면 그대로 둔다.
 *   <li>DATE/TIMESTAMP: 시간대 없는 ISO-8601 문자열 {@code yyyy-MM-dd'T'HH:mm:ss[.SSSSSS]}.
 *   <li>CLOB: 문자열.
 * </ul>
 */
public final class ValueConverter {

  private static final DateTimeFormatter SECONDS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);

  private static final BigDecimal LONG_MIN = BigDecimal.valueOf(Long.MIN_VALUE);
  private static final BigDecimal LONG_MAX = BigDecimal.valueOf(Long.MAX_VALUE);

  private ValueConverter() {}

  /** 결과 컬럼명 정규화: 전부 대문자일 때만 소문자로. */
  public static String columnName(String raw) {
    if (raw == null) {
      return null;
    }
    return raw.equals(raw.toUpperCase(Locale.ROOT)) ? raw.toLowerCase(Locale.ROOT) : raw;
  }

  /**
   * NUMBER 값을 컬럼 메타(precision·scale)로 변환한다. 판정은 값이 아니라 메타로 한다 — 값으로 판정하면 우연히 정수인 NUMBER(10,2) 가 정수로
   * 바뀐다.
   */
  public static Object number(BigDecimal value, int precision, int scale) {
    if (value == null) {
      return null;
    }
    if (scale > 0) {
      // Python quantize 와 같은 반올림(HALF_EVEN). NUMBER(p,s) 값은 이미 s 자리 이하라 실제로는 0 만 채운다.
      return value.setScale(scale, RoundingMode.HALF_EVEN);
    }
    // scale 0(정수 컬럼) 또는 음수(맨 NUMBER 는 -127): 정수값이면 정수, 소수면 뒤 0 만 뗀다.
    // 실서버 측정: Tibero 는 리터럴 1.5 를 NUMBER(38,0), COUNT(*) 를 (0,0) 으로 알려 준다 — scale 0 이라고 자르면 값이 바뀐다.
    BigDecimal stripped = value.stripTrailingZeros();
    if (stripped.scale() <= 0) {
      BigDecimal integral = stripped.setScale(0);
      if (integral.compareTo(LONG_MIN) >= 0 && integral.compareTo(LONG_MAX) <= 0) {
        return integral.longValueExact();
      }
      return integral;
    }
    return stripped;
  }

  /** 시간대 없는 ISO-8601 문자열. 소수 초가 있으면 마이크로초 6자리(그 아래는 버림), 없으면 초까지. */
  public static String timestamp(LocalDateTime t) {
    if (t == null) {
      return null;
    }
    int micros = t.getNano() / 1_000;
    String base = t.format(SECONDS);
    return micros == 0 ? base : base + "." + String.format(Locale.ROOT, "%06d", micros);
  }

  /**
   * 변환에 쓰는 컬럼 메타. 결과 집합마다 컬럼별로 한 번 만들어 모든 행에 재사용한다. 정밀도·자릿수는 NUMBER 판정에만 쓰므로 NUMERIC·DECIMAL 컬럼에서만
   * 읽는다(다른 타입은 0).
   */
  public record ColumnMeta(int type, int precision, int scale) {

    /** {@code column} 번째 컬럼의 메타를 읽는다. */
    public static ColumnMeta of(ResultSetMetaData md, int column) throws SQLException {
      int type = md.getColumnType(column);
      if (type == Types.NUMERIC || type == Types.DECIMAL) {
        return new ColumnMeta(type, md.getPrecision(column), md.getScale(column));
      }
      return new ColumnMeta(type, 0, 0);
    }
  }

  /** 결과 집합의 한 칸을 JDBC 타입에 맞는 getter 로 읽어 변환한다(메타를 그 자리에서 읽는다). */
  public static Object read(ResultSet rs, int column, ResultSetMetaData md) throws SQLException {
    return read(rs, column, ColumnMeta.of(md, column));
  }

  /** 결과 집합의 한 칸을 미리 읽어 둔 컬럼 메타로 변환한다. */
  public static Object read(ResultSet rs, int column, ColumnMeta meta) throws SQLException {
    switch (meta.type()) {
      case Types.NUMERIC, Types.DECIMAL -> {
        return number(rs.getBigDecimal(column), meta.precision(), meta.scale());
      }
      case Types.DATE, Types.TIMESTAMP, Types.TIME -> {
        // Tibero(Oracle) DATE 는 시각을 가진다 — getDate 로 읽으면 시각이 잘리므로 getTimestamp 를 쓴다.
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : timestamp(ts.toLocalDateTime());
      }
      case Types.CLOB, Types.NCLOB -> {
        Clob clob = rs.getClob(column);
        if (clob == null) {
          return null;
        }
        try {
          return clob.getSubString(1, (int) clob.length());
        } finally {
          try {
            clob.free();
          } catch (SQLException | RuntimeException ignored) {
            // 해제 실패는 값 읽기 결과에 영향 주지 않는다.
          }
        }
      }
      case Types.CHAR,
          Types.VARCHAR,
          Types.LONGVARCHAR,
          Types.NCHAR,
          Types.NVARCHAR,
          Types.LONGNVARCHAR -> {
        return rs.getString(column);
      }
      default -> {
        Object v = rs.getObject(column);
        // JSON 으로 그대로 실을 수 있는 값만 두고(byte[] 는 Jackson 이 Base64 로 쓴다), 드라이버 고유 객체는 문자열로.
        if (v == null
            || v instanceof String
            || v instanceof Number
            || v instanceof Boolean
            || v instanceof byte[]) {
          return v;
        }
        return v.toString();
      }
    }
  }
}
