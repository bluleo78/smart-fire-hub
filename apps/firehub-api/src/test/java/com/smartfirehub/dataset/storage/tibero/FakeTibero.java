package com.smartfirehub.dataset.storage.tibero;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 메모리 속 Tibero 흉내 — 이름 바꾸기·DROP·COMMENT 문장을 해석하고 카탈로그 질의에 답한다. 도우미 단위 테스트({@code
 * TiberoRebuildBackupTest})와 저장소 단위 테스트({@code TiberoDataTableStoreTempTest.Rebuild}, WD-256)가 함께
 * 쓴다 — 저장소 테스트는 {@code createTable} 문장(CREATE SEQUENCE·TABLE·INDEX)과 트리거 상태 질의도 지나가므로 그것도 해석한다.
 */
final class FakeTibero {

  /** 객체가 속한 스키마 — 딸린 객체 이름 파생에 쓴다. */
  private final String schema;

  FakeTibero() {
    this("GENIA_T5");
  }

  FakeTibero(String schema) {
    this.schema = schema;
  }

  final Map<String, Long> tables = new LinkedHashMap<>();
  final Map<String, String> comments = new LinkedHashMap<>();
  final Set<String> sequences = new LinkedHashSet<>();
  final Map<String, String> idDefaults = new LinkedHashMap<>(); // 테이블 → DEFAULT 가 참조하는 시퀀스
  final Map<String, String> triggers = new LinkedHashMap<>(); // 트리거 → 테이블
  final Map<String, String[]> indexes = new LinkedHashMap<>(); // 색인 → {테이블, 컬럼}
  final Map<String, String[]> checks = new LinkedHashMap<>(); // 제약 → {테이블, 컬럼}
  final List<String> log = new ArrayList<>();
  Predicate<String> failOn = sql -> false;

  /** CREATE TABLE 이 새 테이블에 줄 다음 OBJECT_ID. */
  long nextOid = 1000L;

  /** 테이블 하나와 우리 규칙의 딸린 객체를 심는다(PK 색인·컬럼 색인·_UPDATED_AT 색인·BOOLEAN CHECK·시퀀스·트리거). */
  void seed(String table, long oid) {
    String p = TiberoNames.physical(table);
    tables.put(p, oid);
    sequences.add(TiberoNames.sequenceName(schema, table));
    idDefaults.put(p, TiberoNames.sequenceName(schema, table));
    triggers.put(TiberoNames.triggerName(schema, table), p);
    indexes.put(TiberoNames.indexName(schema, table, "NAME"), new String[] {p, "NAME"});
    indexes.put(
        TiberoNames.indexName(schema, table, "_UPDATED_AT"), new String[] {p, "_UPDATED_AT"});
    indexes.put(TiberoNames.primaryKeyIndexName(schema, table), new String[] {p, "NAME"});
    indexes.put("SYS_C_" + p, new String[] {p, "ID"});
    checks.put(TiberoNames.checkConstraintName(schema, table, "FLAG"), new String[] {p, "FLAG"});
  }

  Connection connection() {
    return (Connection)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, m, args) ->
                switch (m.getName()) {
                  case "createStatement" -> statement();
                  case "prepareStatement" -> prepared((String) args[0]);
                  default -> null;
                });
  }

  private Statement statement() {
    return (Statement)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {Statement.class},
            (proxy, m, args) -> {
              if (m.getName().equals("execute")) {
                run((String) args[0]);
              }
              return m.getName().equals("execute") ? Boolean.FALSE : null;
            });
  }

  private PreparedStatement prepared(String sql) {
    List<Object> binds = new ArrayList<>();
    return (PreparedStatement)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {PreparedStatement.class},
            (proxy, m, args) -> {
              switch (m.getName()) {
                case "setString" -> {
                  binds.add((Integer) args[0] - 1, args[1]);
                  return null;
                }
                case "executeQuery" -> {
                  return result(query(sql, binds));
                }
                default -> {
                  return null;
                }
              }
            });
  }

  private List<Object[]> query(String sql, List<Object> b) {
    List<Object[]> rows = new ArrayList<>();
    if (sql.equals(TiberoCatalog.objectIdQuery())) {
      String name = (String) b.get(1);
      switch ((String) b.get(2)) {
        case "TABLE" -> {
          if (tables.containsKey(name)) rows.add(new Object[] {tables.get(name)});
        }
        case "SEQUENCE" -> {
          if (sequences.contains(name)) rows.add(new Object[] {1L});
        }
        case "TRIGGER" -> {
          if (triggers.containsKey(name)) rows.add(new Object[] {2L});
        }
        default -> throw new IllegalStateException("unexpected type");
      }
    } else if (sql.equals(TiberoCatalog.tableCommentQuery())) {
      String table = (String) b.get(1);
      if (tables.containsKey(table)) rows.add(new Object[] {comments.get(table)});
    } else if (sql.equals(TiberoCatalog.tableIndexColumnsQuery())) {
      indexes.forEach(
          (n, tc) -> {
            if (tc[0].equals(b.get(1))) rows.add(new Object[] {n, tc[1]});
          });
    } else if (sql.contains("DATA_DEFAULT FROM USER_TAB_COLUMNS")) {
      String sequence = idDefaults.get(b.get(0));
      if (tables.containsKey(b.get(0))) {
        rows.add(new Object[] {sequence == null ? null : "\"" + schema + "\".\"" + sequence + "\".NEXTVAL"});
      }
    } else if (sql.startsWith("SELECT TRIGGER_NAME, STATUS, TRIGGERING_EVENT FROM USER_TRIGGERS")) {
      for (Object name : b) {
        if (triggers.containsKey((String) name)) rows.add(new Object[] {name, "ENABLED", "UPDATE"});
      }
    } else if (sql.equals(TiberoCatalog.checkConstraintColumnsQuery())) {
      checks.forEach(
          (n, tc) -> {
            if (tc[0].equals(b.get(1))) rows.add(new Object[] {n, tc[1]});
          });
    } else {
      throw new IllegalStateException("모르는 질의: " + sql);
    }
    return rows;
  }

  private static ResultSet result(List<Object[]> rows) {
    int[] at = {-1};
    Object[] last = {null};
    return (ResultSet)
        Proxy.newProxyInstance(
            FakeTibero.class.getClassLoader(),
            new Class<?>[] {ResultSet.class},
            (proxy, m, args) -> {
              switch (m.getName()) {
                case "next" -> {
                  return ++at[0] < rows.size();
                }
                case "getLong" -> {
                  last[0] = rows.get(at[0])[(Integer) args[0] - 1];
                  return last[0] == null ? 0L : ((Number) last[0]).longValue();
                }
                case "getString" -> {
                  last[0] = rows.get(at[0])[(Integer) args[0] - 1];
                  return last[0] == null ? null : last[0].toString();
                }
                case "wasNull" -> {
                  return last[0] == null;
                }
                default -> {
                  return null;
                }
              }
            });
  }

  private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

  private void run(String sql) throws SQLException {
    log.add(sql);
    if (failOn.test(sql)) {
      throw new SQLException("주입된 실패", "42000", -7071);
    }
    List<String> q = new ArrayList<>();
    Matcher m = QUOTED.matcher(sql);
    while (m.find()) q.add(m.group(1));
    if (sql.startsWith("RENAME ")) {
      rename(sequences, q.get(0), q.get(1));
    } else if (sql.startsWith("ALTER INDEX")) {
      String[] tc = indexes.remove(q.get(1));
      require(tc != null && !indexes.containsKey(q.get(2)));
      indexes.put(q.get(2), tc);
    } else if (sql.contains("RENAME CONSTRAINT")) {
      String[] tc = checks.remove(q.get(2));
      require(tc != null && !checks.containsKey(q.get(3)));
      checks.put(q.get(3), tc);
    } else if (sql.startsWith("ALTER TABLE") && sql.contains(" RENAME TO ")) {
      String from = q.get(1);
      String to = q.get(2);
      require(tables.containsKey(from) && !tables.containsKey(to));
      tables.put(to, tables.remove(from));
      if (idDefaults.containsKey(from)) idDefaults.put(to, idDefaults.remove(from));
      if (comments.containsKey(from)) comments.put(to, comments.remove(from));
      indexes.values().forEach(tc -> tc[0] = tc[0].equals(from) ? to : tc[0]);
      checks.values().forEach(tc -> tc[0] = tc[0].equals(from) ? to : tc[0]);
      triggers.replaceAll((k, v) -> v.equals(from) ? to : v);
    } else if (sql.startsWith("COMMENT ON TABLE")) {
      String text = sql.substring(sql.indexOf(" IS '") + 5, sql.length() - 1);
      require(tables.containsKey(q.get(1)));
      if (text.isEmpty()) comments.remove(q.get(1));
      else comments.put(q.get(1), text);
    } else if (sql.startsWith("CREATE OR REPLACE TRIGGER")) {
      require(tables.containsKey(q.get(3)));
      triggers.put(q.get(1), q.get(3));
    } else if (sql.startsWith("DROP TRIGGER")) {
      require(triggers.remove(q.get(1)) != null);
    } else if (sql.startsWith("ALTER TABLE") && sql.contains(" MODIFY (\"ID\" DEFAULT ")) {
      // 시퀀스 rename 은 기존 DEFAULT 를 자동 갱신하지 않는다. 명시적 MODIFY 만 참조를 바꾼다.
      require(tables.containsKey(q.get(1)) && sequences.contains(q.get(4)));
      idDefaults.put(q.get(1), q.get(4));
    } else if (sql.startsWith("DROP TABLE")) {
      String name = q.get(1);
      require(tables.remove(name) != null);
      comments.remove(name);
      idDefaults.remove(name);
      indexes.values().removeIf(tc -> tc[0].equals(name));
      checks.values().removeIf(tc -> tc[0].equals(name));
      triggers.values().removeIf(tbl -> tbl.equals(name));
    } else if (sql.startsWith("GRANT ")) {
      // 권한은 상태로 흉내 내지 않는다(이름 바꾸기가 GRANT 를 유지하는지는 실서버 테스트가 본다).
      require(tables.containsKey(q.get(1)));
    } else if (sql.startsWith("CREATE SEQUENCE ")) {
      require(sequences.add(q.get(1)));
    } else if (sql.startsWith("CREATE TABLE ")) {
      require(!tables.containsKey(q.get(1)));
      tables.put(q.get(1), nextOid++);
      // 최근 DDL 의 ID 기본값은 테이블 전용 시퀀스를 참조한다.
      idDefaults.put(q.get(1), TiberoNames.sequenceName(schema, q.get(1)));
    } else if (sql.startsWith("CREATE INDEX ") || sql.startsWith("CREATE UNIQUE INDEX ")) {
      require(!indexes.containsKey(q.get(1)) && tables.containsKey(q.get(3)));
      indexes.put(q.get(1), new String[] {q.get(3), q.get(4)});
    } else if (sql.startsWith("DROP SEQUENCE")) {
      require(sequences.remove(q.get(1)));
    } else {
      throw new IllegalStateException("모르는 문장: " + sql);
    }
  }

  private void rename(Set<String> set, String from, String to) throws SQLException {
    require(set.contains(from) && !set.contains(to));
    set.remove(from);
    set.add(to);
  }

  private static void require(boolean ok) throws SQLException {
    if (!ok) throw new SQLException("이름 충돌 또는 없음", "42000", -7102);
  }

  /** 객체 이름 모음 — 상태 비교용. */
  Set<String> everything() {
    Set<String> all = new LinkedHashSet<>();
    tables.forEach((n, oid) -> all.add("T:" + n + "#" + oid));
    sequences.forEach(n -> all.add("Q:" + n));
    idDefaults.forEach((table, sequence) -> all.add("D:" + table + "@" + sequence));
    triggers.forEach((n, t) -> all.add("G:" + n + "@" + t));
    indexes.forEach((n, tc) -> all.add("I:" + n + "@" + tc[0]));
    checks.forEach((n, tc) -> all.add("C:" + n + "@" + tc[0]));
    return all;
  }
}
