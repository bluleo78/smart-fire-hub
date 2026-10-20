package com.smartfirehub.dataset.storage.tibero;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tibero 카탈로그 뷰({@code ALL_TAB_COLUMNS}·{@code ALL_CONSTRAINTS}·{@code ALL_IND_COLUMNS}·{@code
 * ALL_OBJECTS}) 읽기 헬퍼(WD-74). 두 저장소가 같은 쿼리·바인딩 규칙(스키마·객체 이름은 대문자 물리 이름)을 쓰도록 한 곳에 둔다. 커넥션 종류는 호출자가
 * 정한다.
 */
final class TiberoCatalog {

  private TiberoCatalog() {}

  /**
   * 스테이징 테이블 물리 이름 형식({@link TiberoNames#stagingTableName()}). LIKE 만으로는 {@code stg_report} 같은 사용자
   * 테이블도 걸린다.
   */
  private static final Pattern STAGING_NAME = Pattern.compile("^STG_[0-9A-F]{24}$");

  /** 컬럼 타입 조회. 바인드: 스키마(대문자), 테이블(대문자). */
  static String columnTypesQuery() {
    return "SELECT COLUMN_NAME, DATA_TYPE, DATA_LENGTH, DATA_PRECISION, DATA_SCALE, CHAR_USED,"
        + " CHAR_LENGTH FROM ALL_TAB_COLUMNS WHERE OWNER = ? AND TABLE_NAME = ?";
  }

  /** 제약 존재 확인. 바인드: 스키마(대문자), 제약 이름(대문자). */
  static String constraintExistsQuery() {
    return "SELECT 1 FROM ALL_CONSTRAINTS WHERE OWNER = ? AND CONSTRAINT_NAME = ?";
  }

  /** 인덱스 컬럼 조회(키 순서대로). 바인드: 인덱스 소유자(대문자), 인덱스 이름(대문자). */
  static String indexColumnsQuery() {
    return "SELECT COLUMN_NAME FROM ALL_IND_COLUMNS WHERE INDEX_OWNER = ? AND INDEX_NAME = ?"
        + " ORDER BY COLUMN_POSITION";
  }

  /** 오래된 스테이징 후보 조회. 바인드: 스키마(대문자), 경과 일수(소수). */
  static String staleStagingQuery() {
    return "SELECT OBJECT_NAME FROM ALL_OBJECTS WHERE OWNER = ? AND OBJECT_TYPE = 'TABLE'"
        + " AND OBJECT_NAME LIKE 'STG\\_%' ESCAPE '\\' AND CREATED < SYSDATE - ?";
  }

  /** 테이블의 {컬럼명(소문자) → 물리 타입}. 테이블이 없으면 빈 맵. */
  static Map<String, TiberoColumnType> columnTypes(Connection conn, String schema, String table)
      throws SQLException {
    Map<String, TiberoColumnType> result = new LinkedHashMap<>();
    try (PreparedStatement ps = conn.prepareStatement(columnTypesQuery())) {
      ps.setString(1, TiberoNames.physical(schema));
      ps.setString(2, TiberoNames.physical(table));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String name = rs.getString(1).toLowerCase(Locale.ROOT);
          String type = rs.getString(2);
          Integer length = intOrNull(rs, 3);
          Integer precision = intOrNull(rs, 4);
          Integer scale = intOrNull(rs, 5);
          String charUsed = rs.getString(6);
          Integer charLength = intOrNull(rs, 7);
          result.put(
              name, new TiberoColumnType(type, length, precision, scale, charUsed, charLength));
        }
      }
    }
    return result;
  }

  /** 스키마에 그 이름의 제약이 있는지. */
  static boolean constraintExists(Connection conn, String schema, String constraintName)
      throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(constraintExistsQuery())) {
      ps.setString(1, TiberoNames.physical(schema));
      ps.setString(2, constraintName);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  /**
   * 인덱스의 컬럼 물리 이름(대문자)을 키 순서대로 돌려준다. 인덱스가 없으면 빈 목록. PK 인덱스를 다시 만들기 전에 옛 정의를 기억해 두었다가, 새 인덱스 생성이
   * 실패하면 되살리는 데 쓴다.
   */
  static List<String> indexColumns(Connection conn, String schema, String indexName)
      throws SQLException {
    List<String> columns = new ArrayList<>();
    try (PreparedStatement ps = conn.prepareStatement(indexColumnsQuery())) {
      ps.setString(1, TiberoNames.physical(schema));
      ps.setString(2, indexName);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          columns.add(rs.getString(1));
        }
      }
    }
    return columns;
  }

  /**
   * 만든 지 {@code olderThan} 이 지난 스테이징 테이블 물리 이름. LIKE 로 좁힌 뒤 Java 에서 정확한 형식({@code STG_<24hex>})만
   * 남긴다 — 사용자 데이터셋 테이블 {@code stg_report}(물리 {@code STG_REPORT}) 를 지우지 않기 위해서다.
   */
  static List<String> staleStagingTables(Connection conn, String schema, Duration olderThan)
      throws SQLException {
    List<String> names = new ArrayList<>();
    BigDecimal days =
        BigDecimal.valueOf(olderThan.toSeconds())
            .divide(BigDecimal.valueOf(86_400), 6, RoundingMode.DOWN);
    try (PreparedStatement ps = conn.prepareStatement(staleStagingQuery())) {
      ps.setString(1, TiberoNames.physical(schema));
      ps.setBigDecimal(2, days);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String name = rs.getString(1);
          if (name != null && STAGING_NAME.matcher(name).matches()) {
            names.add(name);
          }
        }
      }
    }
    return names;
  }

  private static Integer intOrNull(ResultSet rs, int column) throws SQLException {
    int v = rs.getInt(column);
    return rs.wasNull() ? null : v;
  }
}
