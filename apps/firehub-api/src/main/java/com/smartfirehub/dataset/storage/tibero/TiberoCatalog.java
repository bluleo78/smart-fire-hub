package com.smartfirehub.dataset.storage.tibero;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

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

  /**
   * 객체 OBJECT_ID 조회(WD-256). 바인드: 소유자(대문자), 객체 이름(대문자), 객체 유형({@code TABLE}·{@code SEQUENCE}·{@code
   * TRIGGER}).
   */
  static String objectIdQuery() {
    return "SELECT OBJECT_ID FROM ALL_OBJECTS WHERE OWNER = ? AND OBJECT_NAME = ?"
        + " AND OBJECT_TYPE = ?";
  }

  /** 테이블 주석 조회(WD-256). 바인드: 소유자(대문자), 테이블(대문자). */
  static String tableCommentQuery() {
    return "SELECT COMMENTS FROM ALL_TAB_COMMENTS WHERE OWNER = ? AND TABLE_NAME = ?";
  }

  /** 테이블에 달린 색인의 {이름, 컬럼} 조회(WD-256). 바인드: 소유자(대문자), 테이블(대문자). */
  static String tableIndexColumnsQuery() {
    return "SELECT INDEX_NAME, COLUMN_NAME FROM ALL_IND_COLUMNS WHERE TABLE_OWNER = ?"
        + " AND TABLE_NAME = ? ORDER BY INDEX_NAME, COLUMN_POSITION";
  }

  /** 테이블의 우리 CHECK 제약({@code CK_%}) {이름, 컬럼} 조회(WD-256). 바인드: 소유자(대문자), 테이블(대문자). */
  static String checkConstraintColumnsQuery() {
    return "SELECT c.CONSTRAINT_NAME, cc.COLUMN_NAME FROM ALL_CONSTRAINTS c JOIN ALL_CONS_COLUMNS cc"
        + " ON cc.OWNER = c.OWNER AND cc.CONSTRAINT_NAME = c.CONSTRAINT_NAME"
        + " WHERE c.OWNER = ? AND c.TABLE_NAME = ? AND c.CONSTRAINT_TYPE = 'C'"
        + " AND c.CONSTRAINT_NAME LIKE 'CK\\_%' ESCAPE '\\'";
  }

  /** 이름이 붙은 컬럼 하나 — 색인·제약 조회 결과 한 행. */
  record NamedColumn(String name, String column) {}

  /** 객체의 OBJECT_ID. 없으면 null. {@code type} 은 {@code TABLE}·{@code SEQUENCE}·{@code TRIGGER}. */
  static Long objectId(Connection conn, String schema, String name, String type)
      throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(objectIdQuery())) {
      ps.setString(1, TiberoNames.physical(schema));
      ps.setString(2, name);
      ps.setString(3, type);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return null;
        }
        long id = rs.getLong(1);
        return rs.wasNull() ? null : id;
      }
    }
  }

  /** 테이블 주석. 주석이 없거나 빈 문자열이면(Tibero 는 {@code IS ''} 를 NULL 로 돌려준다) null. */
  static String tableComment(Connection conn, String schema, String table) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(tableCommentQuery())) {
      ps.setString(1, TiberoNames.physical(schema));
      ps.setString(2, table);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return null;
        }
        String comment = rs.getString(1);
        return comment == null || comment.isBlank() ? null : comment;
      }
    }
  }

  /** 테이블에 달린 색인 컬럼 행들(색인 이름 순·키 순서). */
  static List<NamedColumn> tableIndexColumns(Connection conn, String schema, String table)
      throws SQLException {
    return namedColumns(conn, tableIndexColumnsQuery(), schema, table);
  }

  /** 테이블의 우리 CHECK 제약 컬럼 행들. */
  static List<NamedColumn> checkConstraintColumns(Connection conn, String schema, String table)
      throws SQLException {
    return namedColumns(conn, checkConstraintColumnsQuery(), schema, table);
  }

  private static List<NamedColumn> namedColumns(
      Connection conn, String sql, String schema, String table) throws SQLException {
    List<NamedColumn> rows = new ArrayList<>();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, TiberoNames.physical(schema));
      ps.setString(2, table);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          rows.add(new NamedColumn(rs.getString(1), rs.getString(2)));
        }
      }
    }
    return rows;
  }

  /** OWNER 세션의 트리거 생성 권한 수 조회({@code SESSION_PRIVS}). 바인드 없음. */
  static String createTriggerPrivilegeQuery() {
    return "SELECT COUNT(*) FROM SESSION_PRIVS WHERE PRIVILEGE IN"
        + " ('CREATE TRIGGER', 'CREATE ANY TRIGGER')";
  }

  /** OWNER 세션 소유 트리거의 상태 조회({@code USER_TRIGGERS}). 바인드: 트리거 이름 {@code n} 개(대문자). */
  static String triggerStatusQuery(int n) {
    return "SELECT TRIGGER_NAME, STATUS, TRIGGERING_EVENT FROM USER_TRIGGERS WHERE TRIGGER_NAME IN ("
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
   * 행 트리거의 모양(WD-233) — 켜진 트리거의 {@code TRIGGERING_EVENT} 로 가린다.
   *
   * <ul>
   *   <li>{@code CURRENT}: UPDATE 전용(현행) — 더 할 일이 없다.
   *   <li>{@code LEGACY}: 옛 INSERT+UPDATE 트리거(WD-233 이전) — ID 기본값이 선 뒤 UPDATE 전용으로 바꾼다.
   *   <li>{@code MISSING}: 없음·꺼짐, 또는 알 수 없는 표기(INSERT 전용 등) — 새로 만든다.
   * </ul>
   */
  enum TriggerShape {
    CURRENT,
    LEGACY,
    MISSING
  }

  /**
   * 켜진 트리거의 {@code TRIGGERING_EVENT}({@code null} = 없음·꺼짐)를 {@link TriggerShape} 로 한 번에 분류한다. 표기
   * 차이(앞뒤 공백·대소문자·{@code UPDATE OF ...} 같은 꼬리)에 흔들리지 않게 「UPDATE·INSERT 를 포함하는가」로 본다 — 정확히 일치만 보면
   * 표기가 조금만 달라도 쓰기마다 트리거 DDL 이 반복된다(리뷰 I-2). UPDATE 를 포함하지 않는 표기는 모두 {@code MISSING} 이다.
   */
  static TriggerShape classify(String event) {
    if (event == null) {
      return TriggerShape.MISSING;
    }
    String e = event.trim().toUpperCase(Locale.ROOT);
    if (!e.contains("UPDATE")) {
      return TriggerShape.MISSING;
    }
    return e.contains("INSERT") ? TriggerShape.LEGACY : TriggerShape.CURRENT;
  }

  /**
   * 켜진 트리거의 이름 → {@code TRIGGERING_EVENT}. 이름이 없으면 질의하지 않는다. 저장소의 보장 경로는 이것 하나로 새 트리거와 옛 트리거를 함께
   * 가린다(질의 한 번).
   */
  static Map<String, String> enabledTriggerEvents(Connection conn, Collection<String> triggerNames)
      throws SQLException {
    Map<String, String> events = new TreeMap<>();
    if (triggerNames.isEmpty()) {
      return events;
    }
    try (PreparedStatement ps = conn.prepareStatement(triggerStatusQuery(triggerNames.size()))) {
      int i = 1;
      for (String name : triggerNames) {
        ps.setString(i++, name);
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          if ("ENABLED".equalsIgnoreCase(rs.getString(2))) {
            events.put(rs.getString(1), rs.getString(3));
          }
        }
      }
    }
    return events;
  }

  /** ID 기본값이 이 테이블의 현재 시퀀스를 가리키는지 확인한다. 이름 바꾸기 뒤에는 옛 시퀀스 참조가 남을 수 있다. */
  static boolean idDefaultUsesSequence(Connection conn, String schema, String table)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "SELECT DATA_DEFAULT FROM USER_TAB_COLUMNS WHERE TABLE_NAME = ? AND COLUMN_NAME = 'ID'")) {
      ps.setString(1, TiberoNames.physical(table));
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return false;
        }
        String expression = rs.getString(1);
        return expression != null
            && expression.toUpperCase(Locale.ROOT).contains(TiberoNames.sequenceName(schema, table))
            && expression.toUpperCase(Locale.ROOT).contains("NEXTVAL");
      }
    }
  }

  /**
   * ID 기본값을 이 테이블의 현재 시퀀스에 묶는다(멱등) — 이미 묶여 있으면 아무것도 하지 않고, 아니면 시퀀스가 있을 때만 {@code MODIFY ID DEFAULT}
   * 를 실행한다. 기본값이 현재 시퀀스를 가리키게 됐으면(이미 그렇거나 방금 설정) true, 시퀀스가 없어 묶지 못했으면 false.
   *
   * <p>재생성 보관·복원·맞춤과 저장소의 트리거 보장이 흩어져 반복하던 「시퀀스가 있고 DEFAULT 가 그걸 안 가리키면 재바인딩」 판정을 한 곳에 둔다. 시퀀스가 없으면
   * 묶지 않는 이유: 없는 시퀀스로의 MODIFY 는 ALTER 시점에 JDBC-8034 로 거부된다(리뷰 M-1, 실서버 탐침). 순서는 기본값 확인이 먼저다 — 흔한
   * 경로(이미 묶임)에서 시퀀스 조회를 하지 않는다. DDL 실패는 그대로 던진다(삼킬지는 호출자가 정한다). 반드시 OWNER 세션에서 부른다({@code
   * USER_TAB_COLUMNS}).
   *
   * <p>보관·복원처럼 시퀀스 이름 바꾸기를 <b>계획한</b> 뒤의 무조건 재바인딩은 이 도우미를 쓰지 않는다 — 계획 시점엔 새 이름의 시퀀스가 아직 없어 이 판정이
   * 건너뛰어 버린다.
   */
  static boolean rebindIdDefaultIfNeeded(Connection conn, String schema, String table)
      throws SQLException {
    if (idDefaultUsesSequence(conn, schema, table)) {
      return true;
    }
    if (objectId(conn, schema, TiberoNames.sequenceName(schema, table), "SEQUENCE") == null) {
      return false;
    }
    try (Statement st = conn.createStatement()) {
      st.execute(TiberoDdlSql.setIdDefault(schema, table));
    }
    return true;
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
