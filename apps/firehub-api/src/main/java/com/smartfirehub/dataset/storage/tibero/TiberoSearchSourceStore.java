package com.smartfirehub.dataset.storage.tibero;

import com.smartfirehub.dataset.rowsearch.CompiledFilter;
import com.smartfirehub.dataset.rowsearch.RowQueryFilter;
import com.smartfirehub.dataset.rowsearch.SearchSourceReader.SourceRow;
import com.smartfirehub.dataset.rowsearch.SearchSourceStore;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.external.ExternalStoreConnections;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.service.TiberoIncrementalCursor;
import com.smartfirehub.pipeline.service.TiberoTimestamps;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Tibero 데이터 저장소의 행 검색 원본 읽기({@link SearchSourceStore}, WD-723). PG 구현({@code
 * PgSearchSourceStore})과 같은 계약을 Tibero SQL·시계로 채운다.
 *
 * <p><b>연결.</b> {@link ExternalStoreConnections#withDml} 로 읽는다 — 호출자 트랜잭션이 있으면 묶인 커넥션, 없으면(행 검색
 * 동기화는 트랜잭션을 걸지 않는다) 호출마다 자동 커밋 커넥션. 모든 문장에 실행 시간 제한({@link TiberoDialect#QUERY_TIMEOUT_SECONDS})을
 * 건다.
 *
 * <p><b>PG 와 다른 점.</b>
 *
 * <ul>
 *   <li>LIMIT 이 없어 {@code ORDER BY} 인라인 뷰 + {@code ROWNUM} 으로 자른다.
 *   <li>배열 바인딩이 없어 id 목록은 {@code IN (?, …)} 이고, IN 목록 상한(1000) 때문에 {@link #IN_CHUNK} 개씩 나눠 묻는다.
 *   <li>테이블 실체 식별자는 {@code ALL_OBJECTS.OBJECT_ID}. 재생성(보관 이름 바꾸기 → 같은 이름으로 새로 만들기)은 OBJECT_ID 가 바뀌고
 *       새 시퀀스로 ID 를 1 부터 다시 매긴다 — PG swap 과 같은 「세대 재구축」 경로다. 교체 적재(REPLACE)는 같은 테이블에 {@code DELETE
 *       FROM} + 시퀀스 {@code NEXTVAL} INSERT 라 OBJECT_ID 가 그대로이고 ID 가 이어진다(재사용 없음) — 새 ID 가 변경분으로 다시
 *       색인되고 옛 ID 는 원본 대조 정리가 지운다.
 *   <li>{@code _UPDATED_AT} 은 서버 벽시계 TIMESTAMP 다. UTC 책갈피는 {@link
 *       TiberoTimestamps#serverWallClockLiteral} 로 서버 시간대로 옮겨 비교한다(파이프라인 증분과 같은 식). 책갈피 후보도 Tibero
 *       시계({@link TiberoIncrementalCursor})로 잡는다.
 * </ul>
 */
@Component
public class TiberoSearchSourceStore implements SearchSourceStore {

  /** IN 목록 한 번에 넣는 id 수 — Oracle 계열 IN 목록 상한(1000) 안쪽. */
  static final int IN_CHUNK = 1000;

  private final ExternalStoreConnections connections;
  private final TiberoIncrementalCursor cursor;

  public TiberoSearchSourceStore(
      ExternalStoreConnections connections, TiberoIncrementalCursor cursor) {
    this.connections = connections;
    this.cursor = cursor;
  }

  @Override
  public DataStoreBackend backend() {
    return DataStoreBackend.TIBERO;
  }

  /** 현재 테넌트 id 와 그 저장소 스키마. */
  private record Ctx(long tenantId, String schema) {}

  private Ctx ctx() {
    long tenantId = TenantContext.require("Tibero 행 검색 원본 읽기");
    return new Ctx(tenantId, connections.schemaOf(tenantId));
  }

  @Override
  public long currentOid(String table) {
    Ctx c = ctx();
    String physical = TiberoNames.physical(table);
    return connections.withDml(
        c.tenantId(),
        conn -> {
          Long id = TiberoCatalog.objectId(conn, c.schema(), physical, "TABLE");
          if (id == null) {
            // PG 의 regclass 캐스팅 실패와 같은 자리 — 원본 테이블이 없으면 동기화를 실패시킨다(백오프).
            throw new IllegalStateException("행 검색 원본 테이블을 찾을 수 없습니다: " + table);
          }
          return id;
        });
  }

  @Override
  public List<SourceRow> fetchChanged(
      String table, List<String> columns, OffsetDateTime cursorValue, long afterId, int limit) {
    Ctx c = ctx();
    StringBuilder inner =
        new StringBuilder("SELECT \"ID\"")
            .append(columns.isEmpty() ? "" : ", " + TiberoDmlSql.cols(columns))
            .append(" FROM ")
            .append(TiberoDmlSql.tbl(c.schema(), table))
            .append(" WHERE \"ID\" > ?");
    if (cursorValue != null) {
      inner
          .append(" AND \"_UPDATED_AT\" >= ")
          .append(TiberoTimestamps.serverWallClockLiteral(cursorValue));
    }
    inner.append(" ORDER BY \"ID\"");
    String sql = "SELECT * FROM (" + inner + ") WHERE ROWNUM <= ?";
    return connections.withDml(
        c.tenantId(),
        conn -> {
          try (PreparedStatement ps = prepare(conn, sql)) {
            ps.setLong(1, afterId);
            ps.setInt(2, limit);
            List<SourceRow> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) rows.add(new SourceRow(rs.getLong(1), values(rs, columns)));
            }
            return rows;
          }
        });
  }

  @Override
  public Map<Long, Map<String, Object>> fetchRows(
      String table, List<String> columns, Collection<Long> ids) {
    Map<Long, Map<String, Object>> result = new HashMap<>();
    if (ids.isEmpty()) return result;
    Ctx c = ctx();
    String head =
        "SELECT \"ID\""
            + (columns.isEmpty() ? "" : ", " + TiberoDmlSql.cols(columns))
            + " FROM "
            + TiberoDmlSql.tbl(c.schema(), table)
            + " WHERE \"ID\" IN (";
    connections.withDml(
        c.tenantId(),
        conn -> {
          for (List<Long> part : chunks(ids)) {
            try (PreparedStatement ps =
                prepare(conn, head + TiberoDmlSql.placeholders(part.size()) + ")")) {
              bindIds(ps, 1, part);
              try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.put(rs.getLong(1), values(rs, columns));
              }
            }
          }
          return null;
        });
    return result;
  }

  @Override
  public List<Long> matchingIds(String table, RowQueryFilter filter, int limit) {
    Ctx c = ctx();
    CompiledFilter compiled = filter.toTibero();
    String sql =
        "SELECT \"ID\" FROM (SELECT t.\"ID\" FROM "
            + TiberoDmlSql.tbl(c.schema(), table)
            + " t WHERE "
            + compiled.sql()
            + " ORDER BY t.\"ID\") WHERE ROWNUM <= ?";
    return connections.withDml(
        c.tenantId(),
        conn -> {
          try (PreparedStatement ps = prepare(conn, sql)) {
            int i = bindAll(ps, 1, compiled.params());
            ps.setInt(i, limit);
            return readIds(ps, new ArrayList<>());
          }
        });
  }

  @Override
  public Set<Long> filterIds(String table, Collection<Long> ids, RowQueryFilter filter) {
    if (ids.isEmpty()) return Set.of();
    Ctx c = ctx();
    CompiledFilter compiled = filter.toTibero();
    String head =
        "SELECT t.\"ID\" FROM " + TiberoDmlSql.tbl(c.schema(), table) + " t WHERE t.\"ID\" IN (";
    return connections.withDml(
        c.tenantId(),
        conn -> {
          Set<Long> out = new HashSet<>();
          for (List<Long> part : chunks(ids)) {
            String sql = head + TiberoDmlSql.placeholders(part.size()) + ") AND " + compiled.sql();
            try (PreparedStatement ps = prepare(conn, sql)) {
              int i = bindIds(ps, 1, part);
              bindAll(ps, i, compiled.params());
              readIds(ps, out);
            }
          }
          return out;
        });
  }

  @Override
  public Set<Long> existingIds(String table, Collection<Long> ids) {
    if (ids.isEmpty()) return Set.of();
    Ctx c = ctx();
    String head =
        "SELECT \"ID\" FROM " + TiberoDmlSql.tbl(c.schema(), table) + " WHERE \"ID\" IN (";
    return connections.withDml(
        c.tenantId(),
        conn -> {
          Set<Long> out = new HashSet<>();
          for (List<Long> part : chunks(ids)) {
            try (PreparedStatement ps =
                prepare(conn, head + TiberoDmlSql.placeholders(part.size()) + ")")) {
              bindIds(ps, 1, part);
              readIds(ps, out);
            }
          }
          return out;
        });
  }

  /** Tibero 서버 시계로 책갈피 후보를 잡는다 — {@code _UPDATED_AT} 이 그 시계로 채워지기 때문이다(PG 시계와 섞지 않는다). */
  @Override
  public OffsetDateTime captureCursorCandidate() {
    return cursor.captureCandidate();
  }

  // ---------- 헬퍼 ----------

  /** 실행 시간 제한을 건 문장 준비(응답 없는 질의가 동기화 워커·요청 스레드를 붙잡지 않게). */
  private static PreparedStatement prepare(Connection conn, String sql) throws SQLException {
    PreparedStatement ps = conn.prepareStatement(sql);
    try {
      ps.setQueryTimeout(TiberoDialect.QUERY_TIMEOUT_SECONDS);
    } catch (SQLException | RuntimeException e) {
      ps.close();
      throw e;
    }
    return ps;
  }

  /** 요청 컬럼 값을 요청 순서대로 읽는다(1번 열은 ID). 키는 메타데이터 이름(소문자) 그대로다. */
  private static Map<String, Object> values(ResultSet rs, List<String> columns)
      throws SQLException {
    Map<String, Object> values = new LinkedHashMap<>();
    for (int i = 0; i < columns.size(); i++) {
      values.put(columns.get(i), TiberoValues.read(rs, i + 2));
    }
    return values;
  }

  private static int bindIds(PreparedStatement ps, int index, List<Long> ids) throws SQLException {
    int i = index;
    for (Long id : ids) ps.setLong(i++, id);
    return i;
  }

  /** 필터 값 바인딩 — {@link TiberoValues#bind} 가 BOOLEAN 을 1/0, 날짜를 JDBC 날짜로 바꾼다(저장 때와 같은 규칙). */
  private static int bindAll(PreparedStatement ps, int index, List<Object> params)
      throws SQLException {
    int i = index;
    for (Object p : params) TiberoValues.bind(ps, i++, p);
    return i;
  }

  private static <C extends Collection<Long>> C readIds(PreparedStatement ps, C out)
      throws SQLException {
    try (ResultSet rs = ps.executeQuery()) {
      while (rs.next()) out.add(rs.getLong(1));
    }
    return out;
  }

  /** id 목록을 {@link #IN_CHUNK} 개씩 나눈다. */
  private static List<List<Long>> chunks(Collection<Long> ids) {
    List<Long> all = new ArrayList<>(ids);
    List<List<Long>> out = new ArrayList<>();
    for (int from = 0; from < all.size(); from += IN_CHUNK) {
      out.add(all.subList(from, Math.min(from + IN_CHUNK, all.size())));
    }
    return out;
  }
}
