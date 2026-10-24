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

/**
 * Tibero 카탈로그 뷰({@code ALL_TAB_COLUMNS}·{@code ALL_CONSTRAINTS}·{@code ALL_IND_COLUMNS}·{@code
 * ALL_OBJECTS}) 읽기 헬퍼(WD-74). 두 저장소가 같은 쿼리·바인딩 규칙(스키마·객체 이름은 대문자 물리 이름)을 쓰도록 한 곳에 둔다. 커넥션 종류는 호출자가
 * 정한다.
 */
public final class TiberoCatalog {

  private TiberoCatalog() {}

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

  /** OWNER 세션의 트리거 생성 권한 수 조회({@code SESSION_PRIVS}). 바인드 없음. */
  static String createTriggerPrivilegeQuery() {
    return "SELECT COUNT(*) FROM SESSION_PRIVS WHERE PRIVILEGE IN"
        + " ('CREATE TRIGGER', 'CREATE ANY TRIGGER')";
  }

  /** OWNER 세션 소유 트리거의 상태 조회({@code USER_TRIGGERS}). 바인드: 트리거 이름 {@code n} 개(대문자). */
  static String triggerStatusQuery(int n) {
    return "SELECT TRIGGER_NAME, STATUS FROM USER_TRIGGERS WHERE TRIGGER_NAME IN ("
        + String.join(", ", java.util.Collections.nCopies(n, "?"))
        + ")";
  }

  /**
   * OWNER 세션에 CREATE TRIGGER(또는 CREATE ANY TRIGGER) 권한이 있는가. 반드시 OWNER 세션에서 불러야 한다 — DBA 세션이면 DBA 의
   * 권한이 나온다.
   */
  static boolean hasCreateTriggerPrivilege(Connection conn) throws SQLException {
    try (var st = conn.createStatement();
        ResultSet rs = st.executeQuery(createTriggerPrivilegeQuery())) {
      return rs.next() && rs.getInt(1) > 0;
    }
  }

  /**
   * 주어진 트리거 중 OWNER 세션에 있고 켜진({@code STATUS} 가 ENABLED, 대소문자 무시) 것의 이름들. DISABLED 등은 없는 것으로 본다. 이름이
   * 없으면 질의하지 않는다. 한 문장의 읽기·쓰기 대상 수만큼이라 IN 목록 한도(1000)에 닿지 않는다.
   */
  static java.util.Set<String> enabledTriggers(
      Connection conn, java.util.Collection<String> triggerNames) throws SQLException {
    java.util.Set<String> enabled = new java.util.TreeSet<>();
    if (triggerNames.isEmpty()) {
      return enabled;
    }
    try (PreparedStatement ps = conn.prepareStatement(triggerStatusQuery(triggerNames.size()))) {
      int i = 1;
      for (String name : triggerNames) {
        ps.setString(i++, name);
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          if ("ENABLED".equalsIgnoreCase(rs.getString(2))) {
            enabled.add(rs.getString(1));
          }
        }
      }
    }
    return enabled;
  }

  /** OWNER 세션이 소유한 테이블 이름 조회({@code USER_TABLES}). 바인드: (테이블 한정 시) 테이블 물리 이름. */
  static String ownTablesQuery(boolean singleTable) {
    return "SELECT TABLE_NAME FROM USER_TABLES" + (singleTable ? " WHERE TABLE_NAME = ?" : "");
  }

  /**
   * OWNER 가 슬롯 계정에 건 SELECT 권한 조회({@code USER_TAB_PRIVS_MADE}). 바인드: 슬롯 계정 LIKE 패턴({@code \}로
   * 이스케이프), (테이블 한정 시) 테이블 물리 이름. 뷰·시퀀스 등 테이블이 아닌 객체의 GRANT 도 함께 나온다.
   */
  static String slotSelectGrantsQuery(boolean singleTable) {
    return "SELECT TABLE_NAME, GRANTEE FROM USER_TAB_PRIVS_MADE WHERE GRANTEE LIKE ? ESCAPE '\\'"
        + " AND PRIVILEGE = 'SELECT'"
        + (singleTable ? " AND TABLE_NAME = ?" : "");
  }

  /**
   * OWNER 가 지정한 계정들에 건 SELECT 권한 조회({@code USER_TAB_PRIVS_MADE}, WD-78). 바인드: 계정 이름 {@code
   * grantees} 개, (테이블 한정 시) 테이블 물리 이름. LIKE 판({@link #slotSelectGrantsQuery})과 달리 계정을 정확히 묶어 다시 거를
   * 필요가 없다.
   */
  static String slotSelectGrantsByGranteeQuery(int grantees, boolean singleTable) {
    return "SELECT TABLE_NAME, GRANTEE FROM USER_TAB_PRIVS_MADE WHERE GRANTEE IN ("
        + String.join(", ", java.util.Collections.nCopies(grantees, "?"))
        + ") AND PRIVILEGE = 'SELECT'"
        + (singleTable ? " AND TABLE_NAME = ?" : "");
  }

  /** OWNER 세션 소유 테이블 물리 이름들(대문자). {@code onlyTable} 이 null 이면 전부, 아니면 그 하나(있으면). */
  public static java.util.Set<String> ownTables(Connection conn, String onlyTable)
      throws SQLException {
    java.util.Set<String> names = new java.util.TreeSet<>();
    try (PreparedStatement ps = conn.prepareStatement(ownTablesQuery(onlyTable != null))) {
      if (onlyTable != null) {
        ps.setString(1, onlyTable);
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          names.add(rs.getString(1));
        }
      }
    }
    return names;
  }

  /**
   * 테넌트 슬롯 계정({@code GENIA_T{id}_S%})에 걸린 SELECT 권한 {테이블 물리 이름 → 계정 이름 집합}. LIKE 는 {@code
   * GENIA_T1_S%} 가 {@code GENIA_T1_SXYZ} 같은 이름도 잡을 수 있으므로 호출자가 슬롯 계정 이름 집합으로 다시 거른다.
   */
  public static Map<String, java.util.Set<String>> slotSelectGrants(
      Connection conn, long tenantId, String onlyTable) throws SQLException {
    Map<String, java.util.Set<String>> grants = new LinkedHashMap<>();
    try (PreparedStatement ps = conn.prepareStatement(slotSelectGrantsQuery(onlyTable != null))) {
      ps.setString(1, "GENIA\\_T" + tenantId + "\\_S%");
      if (onlyTable != null) {
        ps.setString(2, onlyTable);
      }
      readGrants(ps, grants);
    }
    return grants;
  }

  /**
   * 지정한 계정들({@code grantees}, 정확한 이름)에 걸린 SELECT 권한 {테이블 물리 이름 → 계정 이름 집합}(WD-78). 실행 준비처럼 다룰 슬롯
   * 계정을 이미 아는 경우 LIKE 로 테넌트 슬롯 전부를 읽은 뒤 거르지 않고 그 계정만 읽는다. 계정이 없으면 질의하지 않는다.
   */
  public static Map<String, java.util.Set<String>> slotSelectGrants(
      Connection conn, java.util.Collection<String> grantees, String onlyTable)
      throws SQLException {
    Map<String, java.util.Set<String>> grants = new LinkedHashMap<>();
    if (grantees.isEmpty()) {
      return grants;
    }
    try (PreparedStatement ps =
        conn.prepareStatement(slotSelectGrantsByGranteeQuery(grantees.size(), onlyTable != null))) {
      int i = 1;
      for (String grantee : grantees) {
        ps.setString(i++, grantee);
      }
      if (onlyTable != null) {
        ps.setString(i, onlyTable);
      }
      readGrants(ps, grants);
    }
    return grants;
  }

  /** (TABLE_NAME, GRANTEE) 행을 {테이블 → 계정 집합} 에 모은다. */
  private static void readGrants(PreparedStatement ps, Map<String, java.util.Set<String>> grants)
      throws SQLException {
    try (ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        grants
            .computeIfAbsent(rs.getString(1), k -> new java.util.TreeSet<>())
            .add(rs.getString(2));
      }
    }
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
          // LIKE 만으로는 stg_report 같은 사용자 테이블도 걸린다 — 정확한 물리 이름(대문자) 형식만 남긴다.
          if (TiberoNames.isStagingPhysicalName(name)) {
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
