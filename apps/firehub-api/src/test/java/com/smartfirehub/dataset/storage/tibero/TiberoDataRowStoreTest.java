package com.smartfirehub.dataset.storage.tibero;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.SpatialFilter;
import com.smartfirehub.dataset.exception.RowNotFoundException;
import com.smartfirehub.dataset.service.DataTableRowService.UpsertResult;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreFeatureUnsupportedException;
import com.smartfirehub.dataset.storage.external.ExternalStoreConnections;
import com.smartfirehub.dataset.storage.external.ExternalStoreConnections.SqlWork;
import com.smartfirehub.global.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.UncategorizedSQLException;

/** JDBC 객체를 가짜로 만들어 실행 SQL·바인드 값·커넥션 종류·건수 계산을 검증한다. 실서버는 쓰지 않는다. */
class TiberoDataRowStoreTest {

  private static final String S = "GENIA_T5";

  /** 실행된 PreparedStatement 한 건의 기록. */
  private static final class Stmt {
    final String sql;
    final Map<Integer, Object> binds = new TreeMap<>();
    final List<Map<Integer, Object>> batches = new ArrayList<>();
    int executeBatchCalls;

    /** setQueryTimeout 으로 건 실행 시간 제한(초). 걸지 않았으면 null. */
    Integer queryTimeout;

    Stmt(String sql) {
      this.sql = sql;
    }
  }

  /** 가짜 커넥션 하나와 그 위에서 실행된 문장 기록. */
  private static final class Fake {
    final Connection conn = mock(Connection.class);
    final List<Stmt> stmts = new ArrayList<>();
    final List<String> executed = new ArrayList<>();
    final List<Predicate<String>> queryMatchers = new ArrayList<>();
    final List<Function<String, ResultSet>> queryResults = new ArrayList<>();
    Function<String, Integer> updateCount = sql -> 1;
    Predicate<String> failWhen = sql -> false;
    Function<String, SQLException> failure;

    Fake() throws SQLException {
      when(conn.prepareStatement(anyString()))
          .thenAnswer(
              inv -> {
                String sql = inv.getArgument(0);
                if (failWhen.test(sql)) {
                  throw failure.apply(sql);
                }
                return newPs(sql);
              });
      Statement st = mock(Statement.class);
      when(conn.createStatement()).thenReturn(st);
      when(st.execute(anyString()))
          .thenAnswer(
              inv -> {
                String sql = inv.getArgument(0);
                executed.add(sql);
                if (failWhen.test(sql)) {
                  throw failure.apply(sql);
                }
                return false;
              });
    }

    void onQueryFn(String contains, Function<String, ResultSet> fn) {
      queryMatchers.add(sql -> sql.contains(contains));
      queryResults.add(fn);
    }

    void onQuery(String contains, ResultSet rs) {
      queryMatchers.add(sql -> sql.contains(contains));
      queryResults.add(sql -> rs);
    }

    Stmt last(String contains) {
      Stmt found = null;
      for (Stmt s : stmts) {
        if (s.sql.contains(contains)) {
          found = s;
        }
      }
      assertThat(found).as("실행된 문장 중 '%s' 포함", contains).isNotNull();
      return found;
    }

    List<Stmt> all(String contains) {
      return stmts.stream().filter(s -> s.sql.contains(contains)).toList();
    }

    private PreparedStatement newPs(String sql) throws SQLException {
      Stmt st = new Stmt(sql);
      stmts.add(st);
      PreparedStatement ps = mock(PreparedStatement.class);
      org.mockito.stubbing.Answer<Object> capture =
          inv -> {
            st.binds.put(inv.getArgument(0), inv.getArgument(1));
            return null;
          };
      org.mockito.Mockito.doAnswer(
              inv -> {
                st.queryTimeout = inv.getArgument(0);
                return null;
              })
          .when(ps)
          .setQueryTimeout(anyInt());
      org.mockito.Mockito.doAnswer(capture).when(ps).setObject(anyInt(), any());
      org.mockito.Mockito.doAnswer(capture).when(ps).setInt(anyInt(), anyInt());
      org.mockito.Mockito.doAnswer(capture).when(ps).setLong(anyInt(), anyLong());
      org.mockito.Mockito.doAnswer(capture).when(ps).setString(anyInt(), any());
      org.mockito.Mockito.doAnswer(capture).when(ps).setBigDecimal(anyInt(), any());
      org.mockito.Mockito.doAnswer(capture).when(ps).setDate(anyInt(), any(java.sql.Date.class));
      org.mockito.Mockito.doAnswer(capture)
          .when(ps)
          .setTimestamp(anyInt(), any(java.sql.Timestamp.class));
      org.mockito.Mockito.doAnswer(
              inv -> {
                st.binds.put(inv.getArgument(0), null);
                return null;
              })
          .when(ps)
          .setNull(anyInt(), anyInt());
      org.mockito.Mockito.doAnswer(
              inv -> {
                st.batches.add(new TreeMap<>(st.binds));
                st.binds.clear();
                return null;
              })
          .when(ps)
          .addBatch();
      when(ps.executeBatch())
          .thenAnswer(
              inv -> {
                st.executeBatchCalls++;
                return new int[st.batches.size()];
              });
      when(ps.executeUpdate()).thenAnswer(inv -> updateCount.apply(sql));
      when(ps.executeQuery())
          .thenAnswer(
              inv -> {
                for (int i = 0; i < queryMatchers.size(); i++) {
                  if (queryMatchers.get(i).test(sql)) {
                    return queryResults.get(i).apply(sql);
                  }
                }
                return rs(new String[] {"X"}, new String[] {"LONG"}, new Object[][] {});
              });
      return ps;
    }
  }

  /** 라벨·종류(LONG=NUMBER(19), DEC=NUMBER(18,6), STR=VARCHAR2)·행으로 가짜 ResultSet 을 만든다. */
  private static ResultSet rs(String[] labels, String[] kinds, Object[][] rows) {
    try {
      int[] cur = {-1};
      ResultSet rs = mock(ResultSet.class);
      ResultSetMetaData md = mock(ResultSetMetaData.class);
      when(rs.getMetaData()).thenReturn(md);
      when(md.getColumnCount()).thenReturn(labels.length);
      for (int i = 0; i < labels.length; i++) {
        int col = i + 1;
        when(md.getColumnLabel(col)).thenReturn(labels[i]);
        switch (kinds[i]) {
          case "LONG" -> {
            when(md.getColumnType(col)).thenReturn(Types.NUMERIC);
            when(md.getColumnTypeName(col)).thenReturn("NUMBER");
            when(md.getPrecision(col)).thenReturn(19);
            when(md.getScale(col)).thenReturn(0);
          }
          case "DEC" -> {
            when(md.getColumnType(col)).thenReturn(Types.NUMERIC);
            when(md.getColumnTypeName(col)).thenReturn("NUMBER");
            when(md.getPrecision(col)).thenReturn(18);
            when(md.getScale(col)).thenReturn(6);
          }
          default -> {
            when(md.getColumnType(col)).thenReturn(Types.VARCHAR);
            when(md.getColumnTypeName(col)).thenReturn("VARCHAR2");
          }
        }
      }
      when(rs.next()).thenAnswer(inv -> ++cur[0] < rows.length);
      boolean[] lastNull = {false};
      when(rs.wasNull()).thenAnswer(inv -> lastNull[0]);
      when(rs.getLong(anyInt()))
          .thenAnswer(
              inv -> {
                Object v = rows[cur[0]][(int) inv.getArgument(0) - 1];
                lastNull[0] = v == null;
                return v == null ? 0L : ((Number) v).longValue();
              });
      when(rs.getInt(anyInt()))
          .thenAnswer(
              inv -> {
                Object v = rows[cur[0]][(int) inv.getArgument(0) - 1];
                lastNull[0] = v == null;
                return v == null ? 0 : ((Number) v).intValue();
              });
      when(rs.getBigDecimal(anyInt()))
          .thenAnswer(
              inv -> {
                Object v = rows[cur[0]][(int) inv.getArgument(0) - 1];
                lastNull[0] = v == null;
                return (BigDecimal) v;
              });
      when(rs.getString(anyInt()))
          .thenAnswer(
              inv -> {
                Object v = rows[cur[0]][(int) inv.getArgument(0) - 1];
                lastNull[0] = v == null;
                return (String) v;
              });
      return rs;
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 한 컬럼(LONG) 짜리 결과 — COUNT·NEXTVAL 용. */
  private static ResultSet single(long value) {
    return rs(new String[] {"V"}, new String[] {"LONG"}, new Object[][] {{value}});
  }

  private ExternalStoreConnections connections;
  private Fake dml;
  private Fake ddl;
  private int dmlCalls;
  private int ddlCalls;

  private TiberoColumnTypeCache columnTypeCache;
  private TiberoDataRowStore store;

  @BeforeEach
  void setUp() throws Exception {
    TenantContext.set(5L);
    dml = new Fake();
    ddl = new Fake();
    connections = mock(ExternalStoreConnections.class);
    when(connections.schemaOf(5L)).thenReturn(S);
    when(connections.withDml(anyLong(), org.mockito.ArgumentMatchers.<SqlWork<Object>>any()))
        .thenAnswer(
            inv -> {
              dmlCalls++;
              return run(inv.getArgument(1), dml.conn);
            });
    when(connections.withDdl(anyLong(), org.mockito.ArgumentMatchers.<SqlWork<Object>>any()))
        .thenAnswer(
            inv -> {
              ddlCalls++;
              return run(inv.getArgument(1), ddl.conn);
            });
    // 고아 스윕은 풀을 만들지 않는 스키마 조회를 쓴다.
    when(connections.schemaOfTransient(5L)).thenReturn(S);
    columnTypeCache = new TiberoColumnTypeCache();
    store = new TiberoDataRowStore(connections, columnTypeCache);
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  private static Object run(SqlWork<Object> work, Connection conn) {
    try {
      return work.apply(conn);
    } catch (SQLException e) {
      throw new UncategorizedSQLException("Tibero", null, e);
    }
  }

  private static Map<String, Object> row(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  @Test
  void backend_isTibero() {
    assertThat(store.backend()).isEqualTo(DataStoreBackend.TIBERO);
  }

  // ---------- 조회 ----------

  @Test
  void queryData_bindsSearchAndPaging_readsRowsWithLowercaseKeys() {
    dml.onQuery(
        "ROW_NUMBER",
        rs(
            new String[] {"ID", "NAME", "QTY"},
            new String[] {"LONG", "STR", "DEC"},
            new Object[][] {{7L, "apple", new BigDecimal("1.5")}}));

    List<Map<String, Object>> rows =
        store.queryData("orders", List.of("name", "qty"), "50%", 2, 10, "qty", "desc", null);

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0))
        .containsOnlyKeys("id", "name", "qty")
        .containsEntry("id", 7L)
        .containsEntry("name", "apple")
        .containsEntry("qty", new BigDecimal("1.5"));
    Stmt st = dml.last("ROW_NUMBER");
    assertThat(st.sql).contains("ORDER BY \"QTY\" DESC NULLS LAST, \"ID\"");
    assertThat(st.binds)
        .containsEntry(1, "%50\\%%")
        .containsEntry(2, "%50\\%%")
        .containsEntry(3, 20L)
        .containsEntry(4, 30L);
    assertThat(dmlCalls).isEqualTo(1);
    assertThat(ddlCalls).isZero();
  }

  @Test
  void queryData_blankSearch_hasNoWhere() {
    store.queryData("orders", List.of("name"), "  ", 0, 5, null, null, null);
    Stmt st = dml.last("ROW_NUMBER");
    assertThat(st.sql).doesNotContain("WHERE (");
    assertThat(st.binds).containsEntry(1, 0L).containsEntry(2, 5L);
  }

  @Test
  void queryData_noColumns_dropsRowNumberAlias() {
    dml.onQuery(
        "ROW_NUMBER",
        rs(
            new String[] {"ID", "NAME", "RN__"},
            new String[] {"LONG", "STR", "LONG"},
            new Object[][] {{1L, "a", 1L}}));
    List<Map<String, Object>> rows =
        store.queryData("orders", List.of(), null, 0, 5, null, null, null);
    assertThat(rows.get(0)).containsOnlyKeys("id", "name");
  }

  @Test
  void queryData_columnResponseOverload_usesColumnNames() {
    DatasetColumnResponse c =
        new DatasetColumnResponse(1L, "name", "이름", "TEXT", null, true, false, null, 0, false);
    store.queryData("orders", List.of(c), null, "x", null, null, 0, 5, null);
    assertThat(dml.last("ROW_NUMBER").sql).contains("TO_CHAR(\"NAME\")");
  }

  @Test
  void countRows_bindsEscapedSearch() {
    dml.onQuery("COUNT(*)", single(42));
    long n = store.countRows("orders", List.of("name", "memo"), "a_b", null);
    assertThat(n).isEqualTo(42);
    assertThat(dml.last("COUNT(*)").binds).containsEntry(1, "%a\\_b%").containsEntry(2, "%a\\_b%");
  }

  @Test
  void spatialFilter_unsupported422() {
    DatasetColumnResponse c =
        new DatasetColumnResponse(1L, "name", "이름", "TEXT", null, true, false, null, 0, false);
    SpatialFilter bbox = new SpatialFilter.Bbox(null, 0, 0, 1, 1);
    assertThatThrownBy(
            () -> store.queryData("orders", List.of(c), Map.of(), null, null, null, 0, 5, bbox))
        .isInstanceOf(DataStoreFeatureUnsupportedException.class)
        .hasMessageContaining("공간 데이터");
    assertThatThrownBy(() -> store.countRows("orders", List.of(c), Map.of(), null, bbox))
        .isInstanceOf(DataStoreFeatureUnsupportedException.class);
    assertThat(dmlCalls).isZero();
  }

  @Test
  void geometryColumnType_unsupported422() {
    Map<String, String> types = Map.of("geom", "GEOMETRY");
    assertThatThrownBy(
            () -> store.queryData("orders", List.of("geom"), null, 0, 5, null, null, types))
        .isInstanceOf(DataStoreFeatureUnsupportedException.class);
    assertThatThrownBy(() -> store.insertRow("orders", List.of("geom"), row("geom", "x"), types))
        .isInstanceOf(DataStoreFeatureUnsupportedException.class);
  }

  // ---------- 삽입 ----------

  @Test
  void insertBatch_chunksOf500_progressCallback() {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int i = 0; i < 1200; i++) {
      rows.add(row("name", "n" + i, "qty", (long) i));
    }
    List<int[]> progress = new ArrayList<>();
    store.insertBatchWithProgress(
        "orders",
        List.of("name", "qty"),
        rows,
        (done, total) -> progress.add(new int[] {done, total}),
        null);

    assertThat(progress).hasSize(3);
    assertThat(progress.get(0)).containsExactly(500, 1200);
    assertThat(progress.get(1)).containsExactly(1000, 1200);
    assertThat(progress.get(2)).containsExactly(1200, 1200);
    Stmt st = dml.last("INSERT INTO");
    assertThat(st.executeBatchCalls).isEqualTo(3);
    assertThat(st.batches).hasSize(1200);
    assertThat(st.batches.get(0)).containsEntry(1, "n0").containsEntry(2, 0L);
    assertThat(st.sql).doesNotContain("IMPORT_ID").contains("SYSTIMESTAMP").contains(".NEXTVAL");
    assertThat(dmlCalls).isEqualTo(1);
  }

  @Test
  void insertBatch_withImportId_bindsImportIdFirst() {
    store.insertBatch("orders", List.of("name"), List.of(row("name", "a")), 99L, null);
    Stmt st = dml.last("INSERT INTO");
    assertThat(st.sql).contains("\"IMPORT_ID\"");
    assertThat(st.batches.get(0)).containsEntry(1, 99L).containsEntry(2, "a");
  }

  @Test
  void insertBatch_bindsJavaValueTypes() {
    store.insertBatch(
        "orders",
        List.of("flag", "d", "n"),
        List.of(row("flag", true, "d", java.time.LocalDate.of(2024, 1, 2), "n", null)),
        null);
    Map<Integer, Object> b = dml.last("INSERT INTO").batches.get(0);
    assertThat(b.get(1)).isEqualTo(1);
    assertThat(b.get(2)).isEqualTo(java.sql.Date.valueOf("2024-01-02"));
    assertThat(b).containsKey(3);
    assertThat(b.get(3)).isNull();
  }

  @Test
  void insertBatch_emptyRows_noConnection() {
    store.insertBatch("orders", List.of("name"), List.of(), null);
    assertThat(dmlCalls).isZero();
  }

  // ---------- upsert ----------

  /** 첫 컬럼이 코드(문자열)인 기존 키 결과. */
  private static ResultSet keys(String... codes) {
    Object[][] rows = new Object[codes.length][];
    for (int i = 0; i < codes.length; i++) {
      rows[i] = new Object[] {codes[i]};
    }
    return rs(new String[] {"CODE"}, new String[] {"STR"}, rows);
  }

  @Test
  void upsertBatch_countsInsertedAndUpdated() {
    dml.onQuery("SELECT \"CODE\" FROM", keys("A"));
    UpsertResult r =
        store.upsertBatch(
            "orders",
            List.of("code", "qty"),
            List.of("code"),
            List.of(row("code", "A", "qty", 1L), row("code", "B", "qty", 2L)),
            7L,
            null);

    assertThat(r).isEqualTo(new UpsertResult(1, 1));
    Stmt merge = dml.last("MERGE INTO");
    assertThat(merge.batches).hasSize(2);
    assertThat(merge.batches.get(0))
        .containsEntry(1, 7L)
        .containsEntry(2, "A")
        .containsEntry(3, 1L);
    assertThat(dmlCalls).isEqualTo(1);
  }

  @Test
  void upsertBatch_duplicatePkInBatch_lastWins() {
    UpsertResult r =
        store.upsertBatch(
            "orders",
            List.of("code", "qty"),
            List.of("code"),
            List.of(
                row("code", "A", "qty", 1L),
                row("code", "A", "qty", 2L),
                row("code", "B", "qty", 3L)),
            null,
            null);

    assertThat(r).isEqualTo(new UpsertResult(2, 0));
    Stmt merge = dml.last("MERGE INTO");
    assertThat(merge.sql).doesNotContain("IMPORT_ID");
    assertThat(merge.batches).hasSize(2);
    assertThat(merge.batches.get(0)).containsEntry(1, "A").containsEntry(2, 2L);
    // 중복 제거된 키만 조회한다
    assertThat(dml.last("SELECT \"CODE\" FROM").binds).containsEntry(1, "A").containsEntry(2, "B");
  }

  @Test
  void upsertBatch_numericKeys_compareByValue() {
    // 기존 키는 BigDecimal(1.50) 로 오고 배치는 Long/Integer/BigDecimal(1.5) 로 들어와도 같은 키로 본다
    dml.onQuery(
        "SELECT \"K\" FROM",
        rs(
            new String[] {"K"},
            new String[] {"DEC"},
            new Object[][] {{new BigDecimal("1.500000")}, {new BigDecimal("2.000000")}}));
    UpsertResult r =
        store.upsertBatch(
            "orders",
            List.of("k", "qty"),
            List.of("k"),
            List.of(
                row("k", new BigDecimal("1.5"), "qty", 1L),
                row("k", 2, "qty", 2L),
                row("k", 3L, "qty", 3L)),
            null,
            null);
    assertThat(r).isEqualTo(new UpsertResult(1, 2));
  }

  @Test
  void upsertBatch_keyRepeatedAcrossChunks_secondChunkSeesItAsExisting() {
    // 상태가 있는 가짜: 이미 MERGE 배치에 담긴 키를 "기존 키" 로 돌려준다
    dml.onQueryFn(
        "SELECT \"CODE\" FROM",
        sql -> {
          List<String> merged = new ArrayList<>();
          for (Stmt s : dml.all("MERGE INTO")) {
            for (Map<Integer, Object> b : s.batches) {
              merged.add((String) b.get(1));
            }
          }
          return keys(merged.toArray(new String[0]));
        });
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int i = 0; i < 500; i++) {
      rows.add(row("code", "C" + i, "qty", (long) i));
    }
    rows.add(row("code", "C0", "qty", 999L)); // 두 번째 청크의 첫 행이 첫 청크 키와 같다
    UpsertResult r =
        store.upsertBatch("orders", List.of("code", "qty"), List.of("code"), rows, null, null);
    assertThat(r).isEqualTo(new UpsertResult(500, 1));
  }

  @Test
  void upsertBatchWithProgress_reportsPerChunk() {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int i = 0; i < 600; i++) {
      rows.add(row("code", "C" + i, "qty", (long) i));
    }
    List<int[]> progress = new ArrayList<>();
    UpsertResult r =
        store.upsertBatchWithProgress(
            "orders",
            List.of("code", "qty"),
            List.of("code"),
            rows,
            null,
            (d, t) -> progress.add(new int[] {d, t}),
            null);
    assertThat(r).isEqualTo(new UpsertResult(600, 0));
    assertThat(progress).hasSize(2);
    assertThat(progress.get(1)).containsExactly(600, 600);
  }

  @Test
  void upsertBatch_emptyRows_zero_andNoPkThrows() {
    assertThat(store.upsertBatch("orders", List.of("code"), List.of("code"), List.of(), null, null))
        .isEqualTo(new UpsertResult(0, 0));
    assertThatThrownBy(
            () ->
                store.upsertBatch(
                    "orders", List.of("code"), List.of(), List.of(row("code", "A")), null, null))
        .isInstanceOf(IllegalStateException.class);
  }

  // ---------- 단건·삭제 ----------

  @Test
  void insertRow_returnsSequenceId() {
    dml.onQuery("NEXTVAL FROM DUAL", single(42));
    Long id = store.insertRow("orders", List.of("name"), row("name", "a"), null);
    assertThat(id).isEqualTo(42L);
    Stmt ins = dml.last("INSERT INTO");
    assertThat(ins.binds).containsEntry(1, 42L).containsEntry(2, "a");
    assertThat(ins.sql).doesNotContain("NEXTVAL");
    assertThat(dmlCalls).isEqualTo(1);
  }

  @Test
  void updateRow_zeroRows_RowNotFound() {
    dml.updateCount = sql -> 0;
    assertThatThrownBy(() -> store.updateRow("orders", 9L, List.of("name"), row("name", "a"), null))
        .isInstanceOf(RowNotFoundException.class);
  }

  @Test
  void updateRow_bindsColumnsThenId() {
    store.updateRow("orders", 9L, List.of("name"), row("name", "a"), null);
    assertThat(dml.last("UPDATE").binds).containsEntry(1, "a").containsEntry(2, 9L);
  }

  @Test
  void getRow_missing_RowNotFound() {
    dml.onQuery("CREATED_AT", rs(new String[] {"ID"}, new String[] {"LONG"}, new Object[][] {}));
    assertThatThrownBy(() -> store.getRow("orders", List.of("name"), 1L, null))
        .isInstanceOf(RowNotFoundException.class);
  }

  @Test
  void getRow_found_lowercaseKeys() {
    dml.onQuery(
        "CREATED_AT",
        rs(new String[] {"ID", "NAME"}, new String[] {"LONG", "STR"}, new Object[][] {{1L, "a"}}));
    Map<String, Object> r = store.getRow("orders", List.of("name"), 1L, null);
    assertThat(r).containsEntry("id", 1L).containsEntry("name", "a");
    assertThat(dml.last("CREATED_AT").binds).containsEntry(1, 1L);
  }

  @Test
  void deleteRows_empty_zero() {
    assertThat(store.deleteRows("orders", List.of())).isZero();
    assertThat(dmlCalls).isZero();
  }

  @Test
  void deleteRows_chunksOf1000_sumsCounts() {
    List<Long> ids = new ArrayList<>();
    for (long i = 0; i < 2500; i++) {
      ids.add(i);
    }
    dml.updateCount = sql -> sql.chars().filter(c -> c == '?').toArray().length;
    int n = store.deleteRows("orders", ids);
    assertThat(n).isEqualTo(2500);
    assertThat(dml.all("DELETE FROM")).hasSize(3);
    assertThat(dmlCalls).isEqualTo(1);
  }

  @Test
  void truncate_usesDelete() {
    store.truncateTable("orders");
    assertThat(dml.last("DELETE FROM").sql).isEqualTo("DELETE FROM \"GENIA_T5\".\"ORDERS\"");
    assertThat(dml.stmts).noneMatch(s -> s.sql.contains("TRUNCATE"));
    assertThat(ddl.executed).isEmpty();
  }

  // ---------- 유일성·중복 ----------

  /** (전체, 고유) 두 컬럼짜리 집계 결과. */
  private static ResultSet totals(long total, long distinct) {
    return rs(
        new String[] {"T", "D"}, new String[] {"LONG", "LONG"}, new Object[][] {{total, distinct}});
  }

  @Test
  void checkDataUniqueness_singleQueryComparesTotalAndDistinct() {
    dml.onQuery("COUNT(DISTINCT", totals(3, 3));
    assertThat(store.checkDataUniqueness("orders", List.of("code"))).isTrue();
    // 한 번의 질의(한 번의 스캔)로 판정한다(B2).
    assertThat(dml.stmts).hasSize(1);

    dml.queryMatchers.clear();
    dml.queryResults.clear();
    dml.stmts.clear();
    dml.onQuery("COUNT(DISTINCT", totals(3, 2));
    assertThat(store.checkDataUniqueness("orders", List.of("code"))).isFalse();
    assertThat(dml.stmts).hasSize(1);

    // 복합 PK 도 한 질의.
    dml.queryMatchers.clear();
    dml.queryResults.clear();
    dml.stmts.clear();
    dml.onQuery("GROUP BY", totals(4, 4));
    assertThat(store.checkDataUniqueness("orders", List.of("a", "b"))).isTrue();
    assertThat(dml.stmts).hasSize(1);
  }

  @Test
  void hasNulls_selectsOneRowWithRownumAndReportsExistence() {
    // 행이 하나라도 나오면 NULL 이 있는 것 — 정확한 SQL(따옴표 친 대문자 이름, ROWNUM = 1)도 함께 확인한다.
    dml.onQuery(
        "IS NULL AND ROWNUM = 1",
        rs(new String[] {"1"}, new String[] {"LONG"}, new Object[][] {{1L}}));
    assertThat(store.hasNulls("orders", "code")).isTrue();
    assertThat(dml.stmts).hasSize(1);
    assertThat(dml.stmts.get(0).sql).endsWith("WHERE \"CODE\" IS NULL AND ROWNUM = 1");

    // 결과가 비어 있으면 NULL 없음.
    dml.queryMatchers.clear();
    dml.queryResults.clear();
    dml.stmts.clear();
    dml.onQuery(
        "IS NULL AND ROWNUM = 1", rs(new String[] {"1"}, new String[] {"LONG"}, new Object[][] {}));
    assertThat(store.hasNulls("orders", "code")).isFalse();
  }

  // ---------- 검색 문자열 식(A3) ----------

  @Test
  void search_readsPhysicalTypesOnSameConnection_booleanMatchesTrueText() {
    dml.onQuery(
        "ALL_TAB_COLUMNS",
        rs(
            new String[] {
              "COLUMN_NAME",
              "DATA_TYPE",
              "DATA_LENGTH",
              "DATA_PRECISION",
              "DATA_SCALE",
              "CHAR_USED",
              "CHAR_LENGTH"
            },
            new String[] {"STR", "STR", "LONG", "LONG", "LONG", "STR", "LONG"},
            new Object[][] {
              {"FLAG", "NUMBER", 22L, 1L, 0L, null, null},
              {"BORN", "DATE", 7L, null, null, null, null}
            }));

    store.queryData("orders", List.of("flag", "born"), "true", 0, 5, null, null, null);

    Stmt q = dml.last("ROW_NUMBER");
    assertThat(q.sql)
        .contains("UPPER(CASE \"FLAG\" WHEN 1 THEN 'true' WHEN 0 THEN 'false' END) LIKE UPPER(?)")
        .contains("UPPER(TO_CHAR(\"BORN\", 'YYYY-MM-DD')) LIKE UPPER(?)");
    // 대소문자 무시는 SQL 의 UPPER(?) 가 맡으므로 바인드는 사용자 입력 그대로(이스케이프만).
    assertThat(q.binds).containsEntry(1, "%true%").containsEntry(2, "%true%");
    Stmt cat = dml.last("ALL_TAB_COLUMNS");
    assertThat(cat.binds).containsEntry(1, S).containsEntry(2, "ORDERS");
    // 카탈로그 조회도 같은 withDml(같은 커넥션) 안에서 한다.
    assertThat(dmlCalls).isEqualTo(1);
  }

  /** 검색 타입 카탈로그 결과(매번 새 ResultSet) — 캐시 동작을 보려면 같은 질의가 두 번 나올 수 있어야 한다. */
  private static ResultSet flagColumnCatalog() {
    return rs(
        new String[] {
          "COLUMN_NAME",
          "DATA_TYPE",
          "DATA_LENGTH",
          "DATA_PRECISION",
          "DATA_SCALE",
          "CHAR_USED",
          "CHAR_LENGTH"
        },
        new String[] {"STR", "STR", "LONG", "LONG", "LONG", "STR", "LONG"},
        new Object[][] {{"FLAG", "NUMBER", 22L, 1L, 0L, null, null}});
  }

  @Test
  void search_columnTypesCached_untilInvalidated() {
    dml.onQueryFn("ALL_TAB_COLUMNS", sql -> flagColumnCatalog());

    // 두 번 검색(조회 + 건수)해도 카탈로그는 한 번만 읽는다.
    store.queryData("orders", List.of("flag"), "true", 0, 5, null, null, null);
    store.countRows("orders", List.of("flag"), "true", null);
    assertThat(dml.all("ALL_TAB_COLUMNS")).hasSize(1);
    // 캐시된 타입으로도 같은 문자열 식을 만든다.
    assertThat(dml.last("COUNT(*)").sql).contains("CASE \"FLAG\" WHEN 1 THEN 'true'");

    // DDL 저장소가 무효화하면 다음 검색은 다시 읽는다.
    columnTypeCache.invalidate(5L, "orders");
    store.queryData("orders", List.of("flag"), "true", 0, 5, null, null, null);
    assertThat(dml.all("ALL_TAB_COLUMNS")).hasSize(2);
  }

  @Test
  void search_cacheIsPerTenantAndTable() {
    dml.onQueryFn("ALL_TAB_COLUMNS", sql -> flagColumnCatalog());
    store.queryData("orders", List.of("flag"), "x", 0, 5, null, null, null);
    // 다른 테이블은 따로 읽는다.
    store.queryData("other", List.of("flag"), "x", 0, 5, null, null, null);
    // 다른 테이블을 무효화해도 이 테이블 항목은 남는다.
    columnTypeCache.invalidate(5L, "other");
    store.queryData("orders", List.of("flag"), "x", 0, 5, null, null, null);
    assertThat(dml.all("ALL_TAB_COLUMNS")).hasSize(2);
  }

  @Test
  void search_invalidColumnOrSort_rejectedBeforeConnecting() {
    // SQL 은 커넥션 안에서 한 번만 만들지만, 이름 검증은 연결 전에 끝낸다.
    assertThatThrownBy(() -> store.queryData("orders", List.of("Bad"), "x", 0, 5, null, null, null))
        .isInstanceOf(com.smartfirehub.dataset.exception.InvalidTableNameException.class);
    assertThatThrownBy(
            () -> store.queryData("orders", List.of("name"), null, 0, 5, "a-b", "asc", null))
        .isInstanceOf(com.smartfirehub.dataset.exception.InvalidTableNameException.class);
    assertThatThrownBy(() -> store.countRows("orders", List.of("Bad"), "x", null))
        .isInstanceOf(com.smartfirehub.dataset.exception.InvalidTableNameException.class);
    assertThat(dmlCalls).isZero();
  }

  @Test
  void noSearch_skipsCatalogQuery() {
    store.queryData("orders", List.of("name"), null, 0, 5, null, null, null);
    store.countRows("orders", List.of("name"), "", null);
    assertThat(dml.all("ALL_TAB_COLUMNS")).isEmpty();
  }

  @Test
  void findDuplicateRows_bindsLimit_readsLowercaseKeys() {
    dml.onQuery(
        "DUPLICATE_COUNT",
        rs(
            new String[] {"CODE", "DUPLICATE_COUNT"},
            new String[] {"STR", "DEC"},
            new Object[][] {{"A", new BigDecimal("2")}}));
    List<Map<String, Object>> rows = store.findDuplicateRows("orders", List.of("code"), 10);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)).containsEntry("code", "A").containsEntry("duplicate_count", 2L);
    assertThat(dml.last("DUPLICATE_COUNT").binds).containsEntry(1, 10L);
  }

  // ---------- 스테이징 ----------

  private static ResultSet targetColumns() {
    return rs(
        new String[] {
          "COLUMN_NAME",
          "DATA_TYPE",
          "DATA_LENGTH",
          "DATA_PRECISION",
          "DATA_SCALE",
          "CHAR_USED",
          "CHAR_LENGTH"
        },
        new String[] {"STR", "STR", "LONG", "LONG", "LONG", "STR", "LONG"},
        new Object[][] {
          {"ID", "NUMBER", 22L, 19L, 0L, null, null},
          // VARCHAR(20) 은 VARCHAR2(20 CHAR) 로 만들어지므로 DATA_LENGTH 는 바이트(80), CHAR_LENGTH 는 20.
          {"CODE", "VARCHAR2", 80L, null, null, "C", 20L},
          {"QTY", "NUMBER", 22L, 18L, 6L, null, null}
        });
  }

  @Test
  void createStagingTable_usesDdlConnection_returnsLowercaseName() throws Exception {
    ResultSet cols = targetColumns();
    ddl.onQuery("ALL_TAB_COLUMNS", cols);

    String stg = store.createStagingTable("orders", List.of("code", "qty"));

    assertThat(stg).matches("stg_[0-9a-f]{24}");
    assertThat(ddlCalls).isEqualTo(1);
    assertThat(dmlCalls).isZero();
    Stmt q = ddl.last("ALL_TAB_COLUMNS");
    assertThat(q.binds).containsEntry(1, S).containsEntry(2, "ORDERS");
    assertThat(ddl.executed).hasSize(2);
    assertThat(ddl.executed.get(0))
        .startsWith("CREATE TABLE \"GENIA_T5\".\"" + stg.toUpperCase() + "\"")
        .contains("\"_SEQ\" NUMBER(19)")
        .contains("\"CODE\" VARCHAR2(20 CHAR)")
        .contains("\"QTY\" NUMBER(18,6)");
    assertThat(ddl.executed.get(1))
        .startsWith("CREATE SEQUENCE \"GENIA_T5\".\"" + TiberoNames.stagingSequenceName(stg));
  }

  @Test
  void createStagingTable_unknownColumn_throws() {
    ddl.onQuery("ALL_TAB_COLUMNS", targetColumns());
    assertThatThrownBy(() -> store.createStagingTable("orders", List.of("nope")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(ddl.executed).isEmpty();
  }

  @Test
  void insertStagingBatchWithProgress_usesStagingSequence() {
    List<int[]> progress = new ArrayList<>();
    store.insertStagingBatchWithProgress(
        "stg_0123456789abcdef01234567",
        List.of("code"),
        List.of(row("code", "A"), row("code", "B")),
        (d, t) -> progress.add(new int[] {d, t}));
    Stmt st = dml.last("INSERT INTO");
    assertThat(st.sql)
        .contains("\"STG_0123456789ABCDEF01234567\"")
        .contains("\"_SEQ\"")
        .contains(".NEXTVAL");
    assertThat(st.batches).hasSize(2);
    assertThat(progress).hasSize(1);
    assertThat(progress.get(0)).containsExactly(2, 2);
  }

  @Test
  void promoteStagingToUpsert_counts() {
    // 집계 질의: (정리된 소스 5행, 그 중 기존 키 3개). MERGE 의 반환값은 엉뚱해도 건수에 영향이 없어야 한다.
    dml.onQuery(
        "THEN 1 ELSE 0 END",
        rs(new String[] {"A", "B"}, new String[] {"LONG", "LONG"}, new Object[][] {{5L, 3L}}));
    dml.updateCount = sql -> sql.startsWith("MERGE") ? 999 : 1;
    UpsertResult r =
        store.promoteStagingToUpsert(
            "stg_0123456789abcdef01234567", "orders", List.of("code", "qty"), List.of("code"), 11L);
    assertThat(r).isEqualTo(new UpsertResult(2, 3));
    assertThat(dml.last("MERGE INTO").binds).containsEntry(1, 11L);
    assertThat(dmlCalls).isEqualTo(1);
    // 존재 키 수를 MERGE 보다 먼저 센다
    assertThat(dml.stmts.get(0).sql).contains("THEN 1 ELSE 0 END");
    assertThat(dml.stmts.get(1).sql).startsWith("MERGE INTO");
  }

  @Test
  void promoteStagingToUpsert_noImportId_noBind() {
    dml.onQuery(
        "THEN 1 ELSE 0 END",
        rs(new String[] {"A", "B"}, new String[] {"LONG", "LONG"}, new Object[][] {{4L, 0L}}));
    dml.updateCount = sql -> sql.startsWith("MERGE") ? 999 : 1;
    UpsertResult r =
        store.promoteStagingToUpsert(
            "stg_0123456789abcdef01234567", "orders", List.of("code"), List.of("code"), null);
    assertThat(r).isEqualTo(new UpsertResult(4, 0));
    assertThat(dml.last("MERGE INTO").binds).isEmpty();
  }

  @Test
  void queryCountAndSingleRowDml_haveQueryTimeout_bulkPromoteHasNone() {
    dml.onQuery("ROW_NUMBER", rs(new String[] {"ID"}, new String[] {"LONG"}, new Object[][] {}));
    dml.onQuery("COUNT(*)", single(1));
    dml.onQuery("NEXTVAL", single(3));
    store.queryData("orders", List.of("name"), null, 0, 10, null, null, null);
    store.countRows("orders", List.of("name"), null, null);
    store.insertRow("orders", List.of("name"), row("name", "a"), null);
    store.updateRow("orders", 3L, List.of("name"), row("name", "b"), null);
    store.insertBatch("orders", List.of("name"), List.of(row("name", "c")), null);
    int before = dml.stmts.size();
    store.promoteStagingToReplace(
        "stg_0123456789abcdef01234567", "orders", List.of("code"), List.of("code"));

    List<Stmt> timed = dml.stmts.subList(0, before);
    assertThat(timed).isNotEmpty();
    assertThat(timed)
        .allSatisfy(
            st ->
                assertThat(st.queryTimeout)
                    .as("제한 시간: %s", st.sql)
                    .isEqualTo(TiberoDialect.QUERY_TIMEOUT_SECONDS));
    // 데이터 크기에 비례하는 일괄 문장(전체 삭제 + 스테이징 복사)에는 걸지 않는다.
    assertThat(dml.stmts.subList(before, dml.stmts.size()))
        .hasSize(2)
        .allSatisfy(st -> assertThat(st.queryTimeout).isNull());
  }

  @Test
  void promoteStagingToReplace_deleteThenInsertSameConnection() {
    store.promoteStagingToReplace(
        "stg_0123456789abcdef01234567", "orders", List.of("code"), List.of("code"));
    assertThat(dmlCalls).isEqualTo(1);
    assertThat(ddlCalls).isZero();
    assertThat(dml.stmts).hasSize(2);
    assertThat(dml.stmts.get(0).sql).isEqualTo("DELETE FROM \"GENIA_T5\".\"ORDERS\"");
    assertThat(dml.stmts.get(1).sql).startsWith("INSERT INTO \"GENIA_T5\".\"ORDERS\"");
  }

  @Test
  void dropStagingTable_dropsTableAndSequence_ignoresMissing() {
    ddl.failWhen = sql -> true;
    ddl.failure =
        sql ->
            sql.startsWith("DROP TABLE")
                ? new SQLException("ORA-00942: table or view does not exist", "42000", 942)
                : new SQLException("ORA-02289: sequence does not exist", "42000", 2289);
    store.dropStagingTable("stg_0123456789abcdef01234567");
    assertThat(ddl.executed).hasSize(2);
    assertThat(ddl.executed.get(0)).startsWith("DROP TABLE");
    assertThat(ddl.executed.get(1)).startsWith("DROP SEQUENCE");
    assertThat(dmlCalls).isZero();
  }

  @Test
  void dropStagingTable_otherError_propagates() {
    ddl.failWhen = sql -> true;
    ddl.failure = sql -> new SQLException("ORA-01031: insufficient privileges", "42000", 1031);
    assertThatThrownBy(() -> store.dropStagingTable("stg_0123456789abcdef01234567"))
        .isInstanceOf(RuntimeException.class);
  }

  // ---------- 고아 스테이징 정리(A8) ----------

  @Test
  void dropStaleStagingTables_dropsOnlyStagingShapedNames_countsDropped() {
    String a = "STG_0123456789ABCDEF01234567";
    String b = "STG_89ABCDEF0123456789ABCDEF";
    // LIKE 'STG\_%' 는 사용자 테이블 stg_report(물리 STG_REPORT) 도 돌려준다 — Java 에서 걸러야 한다.
    ddl.onQuery(
        "ALL_OBJECTS",
        rs(
            new String[] {"OBJECT_NAME"},
            new String[] {"STR"},
            new Object[][] {{a}, {"STG_REPORT"}, {b}}));
    // b 는 그새 지워졌다(942) — 세지 않고 계속한다.
    ddl.failWhen = sql -> sql.equals("DROP TABLE \"GENIA_T5\".\"" + b + "\"");
    ddl.failure = sql -> new SQLException("ORA-00942: table or view does not exist", "42000", 942);

    int dropped = store.dropStaleStagingTables(5L, java.time.Duration.ofHours(1));

    assertThat(dropped).isEqualTo(1);
    assertThat(ddl.executed)
        .containsExactly(
            "DROP TABLE \"GENIA_T5\".\"" + a + "\"",
            "DROP SEQUENCE \"GENIA_T5\".\"" + TiberoNames.stagingSequenceName(a) + "\"",
            "DROP TABLE \"GENIA_T5\".\"" + b + "\"",
            "DROP SEQUENCE \"GENIA_T5\".\"" + TiberoNames.stagingSequenceName(b) + "\"");
    Stmt q = ddl.last("ALL_OBJECTS");
    assertThat(q.sql)
        .isEqualTo(
            "SELECT OBJECT_NAME FROM ALL_OBJECTS WHERE OWNER = ? AND OBJECT_TYPE = 'TABLE'"
                + " AND OBJECT_NAME LIKE 'STG\\_%' ESCAPE '\\' AND CREATED < SYSDATE - ?");
    // 1시간 = 1/24 일(소수 6자리 내림)
    assertThat(q.binds).containsEntry(1, S).containsEntry(2, new BigDecimal("0.041666"));
    assertThat(dmlCalls).isZero();
    // 풀을 만드는 schemaOf 는 쓰지 않고, 풀을 건드리지 않는 단발 직결 DDL(withDdl) 한 번뿐이다.
    assertThat(ddlCalls).isEqualTo(1);
    verify(connections, times(0)).schemaOf(anyLong());
  }

  @Test
  void dropStaleStagingTables_oneTableFails_othersStillDropped() {
    String a = "STG_0123456789ABCDEF01234567";
    String b = "STG_89ABCDEF0123456789ABCDEF";
    ddl.onQuery(
        "ALL_OBJECTS",
        rs(new String[] {"OBJECT_NAME"}, new String[] {"STR"}, new Object[][] {{a}, {b}}));
    ddl.failWhen = sql -> sql.equals("DROP TABLE \"GENIA_T5\".\"" + a + "\"");
    ddl.failure = sql -> new SQLException("ORA-00054: resource busy", "61000", 54);

    assertThat(store.dropStaleStagingTables(5L, java.time.Duration.ofHours(1))).isEqualTo(1);
    assertThat(ddl.executed).contains("DROP TABLE \"GENIA_T5\".\"" + b + "\"");
  }

  @Test
  void invalidTableName_rejectedBeforeConnecting() {
    assertThatThrownBy(() -> store.truncateTable("Bad-Name"))
        .isInstanceOf(com.smartfirehub.dataset.exception.InvalidTableNameException.class);
    verify(connections, times(0)).withDml(anyLong(), any());
  }
}
