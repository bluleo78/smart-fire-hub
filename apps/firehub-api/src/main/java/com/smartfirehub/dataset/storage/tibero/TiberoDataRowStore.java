package com.smartfirehub.dataset.storage.tibero;

import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.SpatialFilter;
import com.smartfirehub.dataset.exception.InvalidTableNameException;
import com.smartfirehub.dataset.exception.RowNotFoundException;
import com.smartfirehub.dataset.service.DataTableRowService.UpsertResult;
import com.smartfirehub.dataset.storage.DataRowStore;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreBatching;
import com.smartfirehub.dataset.storage.DataStoreFeatureUnsupportedException;
import com.smartfirehub.dataset.storage.DataTableNames;
import com.smartfirehub.dataset.storage.external.ExternalStoreConnections;
import com.smartfirehub.global.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Tibero 용 {@link DataRowStore}(WD-74): 행 조회·검색·정렬·페이징·삽입·upsert(MERGE)·단건 편집·유일성·가져오기 스테이징.
 *
 * <p><b>연결.</b> 모든 메서드는 {@link ExternalStoreConnections#withDml} 로 실행한다 — 호출자 트랜잭션이 있으면 테넌트당 묶인 커넥션
 * 하나를 모든 DML·조회가 공유하므로 {@code addRow} 의 insert→get 이 같은 트랜잭션에서 서로 보이고(read-your-writes),
 * 교체(REPLACE) 도중 실패하면 비우기까지 함께 롤백된다. 예외는 스테이징 테이블 생성·삭제뿐이며 DDL 이라 항상 별도 자동 커밋 커넥션({@code withDdl})을
 * 쓴다 — 묶인 커넥션에서 DDL 을 실행하면 암묵 커밋으로 진행 중인 DML 이 확정되기 때문이다. {@code TRUNCATE} 도 DDL 이라 쓰지 않고 {@code
 * DELETE FROM} 으로 비운다.
 *
 * <p><b>값.</b> 바인딩·읽기는 {@link TiberoValues}(Java 값 타입 기준)를 쓴다. 빈 문자열은 Tibero 가 NULL 로 저장한다(막지 않는다).
 * 결과 맵 키는 메타데이터 이름(소문자)이며 {@code queryData} 는 {@code id} + 요청 컬럼, {@code getRow} 는 거기에 {@code
 * created_at} 을 더한다(PG 와 동일).
 *
 * <p><b>미지원(R4).</b> 공간 필터와 GEOMETRY 컬럼 타입은 {@link DataStoreFeatureUnsupportedException}(422). 거부는
 * 메서드 모양별 진입 헬퍼({@link #enter}·{@link #checkWrite}·{@link #rejectSpatialFilter}) 에서만 한다.
 *
 * <p><b>검색 타입 캐시.</b> 검색 문자열 식을 정하는 물리 컬럼 타입은 {@link TiberoColumnTypeCache} 로 30초 캐시한다. 컬럼 구조를 바꾸는
 * DDL 저장소가 무효화한다.
 */
@Slf4j
@Component
public class TiberoDataRowStore implements DataRowStore {

  /** Oracle 계열 IN 목록 상한. */
  private static final int IN_LIMIT = 1000;

  private static final String SPATIAL_FEATURE = "공간 데이터";

  private final ExternalStoreConnections connections;
  private final TiberoColumnTypeCache columnTypeCache;

  public TiberoDataRowStore(
      ExternalStoreConnections connections, TiberoColumnTypeCache columnTypeCache) {
    this.connections = connections;
    this.columnTypeCache = columnTypeCache;
  }

  @Override
  public DataStoreBackend backend() {
    return DataStoreBackend.TIBERO;
  }

  // ---------- 공통 헬퍼 ----------

  /** 현재 테넌트와 그 저장소 스키마. */
  private record Ctx(long tenantId, String schema) {}

  private Ctx ctx(String operation) {
    long tenantId = TenantContext.require("Tibero " + operation);
    return new Ctx(tenantId, connections.schemaOf(tenantId));
  }

  /**
   * 단건·조회 메서드의 진입 헬퍼: GEOMETRY 타입 힌트 거부 → 테이블 이름 검증 → 현재 테넌트·스키마. 미지원 거부를 메서드마다 흩어 두지 않으려고 한곳에 모았다.
   */
  private Ctx enter(String operation, String tableName, Map<String, String> columnTypes) {
    checkWrite(tableName, columnTypes);
    return ctx(operation);
  }

  /**
   * 일괄 쓰기 메서드의 진입 헬퍼: GEOMETRY 타입 힌트 거부 → 테이블 이름 검증만 한다. 빈 입력이면 테넌트·스키마 확인(풀 생성) 없이 바로 끝내야 해서
   * {@link #ctx} 는 호출자가 빈 입력 검사 뒤에 부른다.
   */
  private static void checkWrite(String tableName, Map<String, String> columnTypes) {
    rejectGeometryHints(columnTypes);
    DataTableNames.validate(tableName);
  }

  /** 공간 필터를 받는 조회 메서드의 진입 헬퍼. 필터가 있으면 422 — 타입 힌트는 위임받는 메서드의 {@link #enter} 가 본다. */
  private static void rejectSpatialFilter(SpatialFilter filter) {
    if (filter != null) {
      throw unsupportedSpatial();
    }
  }

  /** GEOMETRY 타입 힌트가 있으면 422. 힌트에는 PG DDL 문자열이 섞여 오므로 "GEOMETRY" 포함 여부로 본다. */
  private static void rejectGeometryHints(Map<String, String> columnTypes) {
    if (columnTypes != null) {
      for (String type : columnTypes.values()) {
        if (type != null && type.toUpperCase(Locale.ROOT).contains("GEOMETRY")) {
          throw unsupportedSpatial();
        }
      }
    }
  }

  private static DataStoreFeatureUnsupportedException unsupportedSpatial() {
    return new DataStoreFeatureUnsupportedException(DataStoreBackend.TIBERO, SPATIAL_FEATURE);
  }

  /** 검색어가 있으면 넘겨받은 검색 대상 컬럼 전체가 검색 대상, 없으면 비어 있다(WHERE 없음). */
  private static List<String> searchColumns(List<String> columns, String search) {
    return search == null || search.isBlank() ? List.of() : columns;
  }

  /**
   * 검색할 때만 컬럼별 물리 타입({@code ALL_TAB_COLUMNS})을 돌려준다. 캐시에 없으면 같은 커넥션에서 한 번 읽는다({@link
   * TiberoColumnTypeCache}). 호출자가 넘기는 {@code columnTypes} 는 null·PG DDL 문자열이 섞여 믿을 수 없어 쓰지 않는다. 검색이
   * 없으면 빈 맵(질의 없음).
   */
  private Map<String, TiberoColumnType> searchTypes(
      Connection conn, Ctx c, String tableName, List<String> searchCols) throws SQLException {
    if (searchCols.isEmpty()) {
      return Map.of();
    }
    return columnTypeCache.get(conn, c.tenantId(), c.schema(), tableName);
  }

  /** 조회 SQL 에 들어갈 컬럼·정렬 이름을 커넥션을 열기 전에 검증한다(SQL 은 람다 안에서 한 번만 만든다). */
  private static void validateNames(List<String> columns, String sortBy) {
    columns.forEach(DataTableNames::validate);
    if (sortBy != null) {
      DataTableNames.validate(sortBy);
    }
  }

  private static int bindSearch(PreparedStatement ps, int index, List<String> cols, String search)
      throws SQLException {
    int i = index;
    if (!cols.isEmpty()) {
      String pattern = TiberoDmlSql.searchPattern(search);
      for (int c = 0; c < cols.size(); c++) {
        ps.setString(i++, pattern);
      }
    }
    return i;
  }

  /** {@code importId} 가 있으면 1번에 먼저 바인딩하고 이어서 행 값을 바인딩한다(삽입·MERGE 공통 바인드 순서). */
  private static void bindRow(
      PreparedStatement ps, List<String> columns, Map<String, Object> row, Long importId)
      throws SQLException {
    int i = 1;
    if (importId != null) {
      TiberoValues.bind(ps, i++, importId);
    }
    bindRow(ps, i, columns, row);
  }

  /** 행 값을 순서대로 바인딩한다. 값이 없는 컬럼은 null. */
  private static int bindRow(
      PreparedStatement ps, int index, List<String> columns, Map<String, Object> row)
      throws SQLException {
    int i = index;
    for (String c : columns) {
      TiberoValues.bind(ps, i++, row.get(c));
    }
    return i;
  }

  private static List<Map<String, Object>> readAll(ResultSet rs) throws SQLException {
    List<Map<String, Object>> rows = new ArrayList<>();
    while (rs.next()) {
      Map<String, Object> row = TiberoValues.readRow(rs);
      row.remove("rn__");
      rows.add(row);
    }
    return rows;
  }

  /**
   * 조회·검색·페이징·건수·단건/청크 DML 용 문장을 실행 시간 제한({@link TiberoDialect#QUERY_TIMEOUT_SECONDS})과 함께 준비한다. 응답
   * 없는 질의가 요청 스레드와 묶인 커넥션을 무한정 붙잡지 않게 하기 위해서다. 데이터 크기에 비례하는 일괄 문장(전체 삭제·스테이징 승격)은 제한 없이 {@code
   * prepareStatement} 를 직접 쓴다 — 큰 가져오기를 60초에서 끊지 않도록.
   */
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

  private static long readCount(PreparedStatement ps) throws SQLException {
    try (ResultSet rs = ps.executeQuery()) {
      return rs.next() ? rs.getLong(1) : 0L;
    }
  }

  // ---------- 조회 ----------

  @Override
  public List<Map<String, Object>> queryData(
      String tableName,
      List<String> columns,
      List<String> searchColumns,
      String search,
      int page,
      int size,
      String sortBy,
      String sortDir,
      Map<String, String> columnTypes) {
    Ctx c = enter("queryData", tableName, columnTypes);
    // 잘못된 이름이면 연결하지 않는다. 출력 컬럼과 검색 대상 컬럼을 따로 받으므로(WD-158) 둘 다 검증한다.
    validateNames(columns, sortBy);
    validateNames(searchColumns, null);
    // 검색어는 출력 컬럼이 아니라 검색 대상 컬럼에 건다 — 일부 컬럼만 내보내도 화면과 같은 행이 나오게(WD-158).
    List<String> searchCols = searchColumns(searchColumns, search);
    long offset = (long) page * size;
    return connections.withDml(
        c.tenantId(),
        conn -> {
          String sql =
              TiberoDmlSql.select(
                  c.schema(),
                  tableName,
                  columns,
                  searchCols,
                  sortBy,
                  sortDir,
                  searchTypes(conn, c, tableName, searchCols));
          try (PreparedStatement ps = prepare(conn, sql)) {
            int i = bindSearch(ps, 1, searchCols, search);
            ps.setLong(i++, offset);
            ps.setLong(i, offset + size);
            try (ResultSet rs = ps.executeQuery()) {
              return readAll(rs);
            }
          }
        });
  }

  @Override
  public List<Map<String, Object>> queryData(
      String tableName,
      List<DatasetColumnResponse> columns,
      Map<String, String> columnTypes,
      String search,
      String sortBy,
      String sortDir,
      int page,
      int size,
      SpatialFilter spatialFilter) {
    rejectSpatialFilter(spatialFilter);
    List<String> names = columns.stream().map(DatasetColumnResponse::columnName).toList();
    return queryData(tableName, names, search, page, size, sortBy, sortDir, columnTypes);
  }

  @Override
  public long countRows(
      String tableName,
      List<DatasetColumnResponse> columns,
      Map<String, String> columnTypes,
      String search,
      SpatialFilter spatialFilter) {
    rejectSpatialFilter(spatialFilter);
    List<String> names = columns.stream().map(DatasetColumnResponse::columnName).toList();
    return countRows(tableName, names, search, columnTypes);
  }

  @Override
  public long countRows(
      String tableName, List<String> columns, String search, Map<String, String> columnTypes) {
    Ctx c = enter("countRows", tableName, columnTypes);
    List<String> searchCols = searchColumns(columns, search);
    // 검색 컬럼만 SQL 에 들어간다 — 잘못된 이름이면 연결하지 않는다.
    validateNames(searchCols, null);
    return connections.withDml(
        c.tenantId(),
        conn -> {
          String sql =
              TiberoDmlSql.count(
                  c.schema(), tableName, searchCols, searchTypes(conn, c, tableName, searchCols));
          try (PreparedStatement ps = prepare(conn, sql)) {
            bindSearch(ps, 1, searchCols, search);
            return readCount(ps);
          }
        });
  }

  // ---------- 행 수 통계(WD-80) ----------

  /**
   * 테이블별 정확한 행 수({@code COUNT(*)}). 사전 뷰({@code ALL_TABLES.NUM_ROWS})는 통계가 늦거나 비어 있어 쓰지 않는다.
   *
   * <p>커넥션 하나에서 테이블마다 센다. 한 테이블이 실패하면(없음·권한) 경고만 남기고 결과에서 빼며, 연결 자체가 실패해도 예외를 던지지 않는다 — 폴러가 실패를 "값
   * 없음"으로 받아 그 회차를 건너뛰게 하려는 계약이다. 형식이 틀린 이름은 연결 전에 뺀다.
   *
   * <p>시간 초과·연결 끊김({@link TiberoErrors#isUnresponsive})이면 그 회차를 바로 멈추고 이미 센 테이블까지만 돌려준다. 응답 없는 서버에
   * 남은 테이블마다 시간 제한(60초)만큼 기다리면 모든 테넌트를 차례로 도는 폴러가 그만큼 묶여 다른 테넌트의 트리거가 늦어지기 때문이다.
   */
  @Override
  public Map<String, Long> rowCounts(Collection<String> tableNames) {
    Map<String, Long> result = new LinkedHashMap<>();
    List<String> names = validStatNames(tableNames, "행 수");
    if (names.isEmpty()) {
      return result;
    }
    try {
      Ctx c = ctx("rowCounts");
      connections.withDml(
          c.tenantId(),
          conn -> {
            for (String table : names) {
              try (PreparedStatement ps =
                  prepare(conn, TiberoDmlSql.count(c.schema(), table, List.of(), Map.of()))) {
                result.put(table, readCount(ps));
              } catch (SQLException e) {
                if (TiberoErrors.isUnresponsive(e)) {
                  // 서버 무응답·연결 끊김 — 남은 테이블도 같은 이유로 실패하므로 이번 회차를 멈춘다.
                  warnStatAborted("행 수", c.tenantId(), e);
                  break;
                }
                warnStatFailure("행 수", c.tenantId(), table, e);
              }
            }
            return null;
          });
    } catch (RuntimeException e) {
      // 연결 실패 등 — 이미 센 테이블까지만 돌려준다. 드라이버 메시지에 접속 정보가 섞일 수 있어 예외 클래스 이름만 남긴다.
      log.warn("Tibero 행 수 통계 실패 — 이번 회차는 세지 않는다: {}", e.getClass().getName());
    }
    return result;
  }

  /** 빈 테이블 판정을 한 문장으로 묶는 테이블 수 — 문장 길이를 적당히 두면서 데이터셋 수만큼 왕복하지 않게 한다. */
  static final int EMPTY_CHECK_CHUNK = 50;

  /**
   * 행이 하나도 없는 테이블. {@value #EMPTY_CHECK_CHUNK}개씩 {@code UNION ALL} 한 문장으로 판정한다({@link
   * TiberoDmlSql#emptyTables}).
   *
   * <p>묶음 안 테이블 하나가 없으면 문장 전체가 실패하므로, 실패한 묶음만 테이블별로 다시 판정한다 — 없는 테이블만 빠지고 나머지는 제대로 판정된다. 판정하지 못한
   * 테이블은 빼고(비어 있다고 세지 않는다) 예외를 던지지 않는다.
   *
   * <p>시간 초과·연결 끊김({@link TiberoErrors#isUnresponsive})이면 테이블별 재판정도 남은 묶음도 하지 않고 이미 판정한 테이블까지만 돌려준다
   * — 홈 대시보드 요청 스레드가 응답 없는 서버에 테이블 수만큼 시간 제한을 기다리지 않게 하려는 것이다.
   */
  @Override
  public Set<String> emptyTables(Collection<String> tableNames) {
    Set<String> result = new LinkedHashSet<>();
    List<String> names = validStatNames(tableNames, "빈 테이블 판정");
    if (names.isEmpty()) {
      return result;
    }
    try {
      Ctx c = ctx("emptyTables");
      connections.withDml(
          c.tenantId(),
          conn -> {
            chunks:
            for (int from = 0; from < names.size(); from += EMPTY_CHECK_CHUNK) {
              List<String> chunk =
                  names.subList(from, Math.min(from + EMPTY_CHECK_CHUNK, names.size()));
              try {
                readEmptyNames(conn, c.schema(), chunk, result);
              } catch (SQLException chunkFailure) {
                if (TiberoErrors.isUnresponsive(chunkFailure)) {
                  // 서버 무응답·연결 끊김 — 테이블별로 다시 물어도 같은 이유로 실패하므로 여기서 멈춘다.
                  warnStatAborted("빈 테이블 판정", c.tenantId(), chunkFailure);
                  break;
                }
                // 묶음 단위 실패(대개 없는 테이블 하나) — 이 묶음만 테이블별로 다시 판정한다.
                for (String table : chunk) {
                  try {
                    readEmptyNames(conn, c.schema(), List.of(table), result);
                  } catch (SQLException e) {
                    if (TiberoErrors.isUnresponsive(e)) {
                      warnStatAborted("빈 테이블 판정", c.tenantId(), e);
                      break chunks;
                    }
                    warnStatFailure("빈 테이블 판정", c.tenantId(), table, e);
                  }
                }
              }
            }
            return null;
          });
    } catch (RuntimeException e) {
      // 드라이버 메시지에 접속 정보가 섞일 수 있어 예외 클래스 이름만 남긴다.
      log.warn("Tibero 빈 테이블 판정 실패 — 판정한 테이블까지만 센다: {}", e.getClass().getName());
    }
    return result;
  }

  /**
   * 빈 테이블 판정 문장 하나를 실행해 비어 있는 테이블 이름을 모은다. 길이가 다른 문자 리터럴의 {@code UNION ALL} 은 CHAR 로 맞춰져 뒤에 공백이 붙을
   * 수 있어 잘라 낸다(우리 이름에는 공백이 없다).
   */
  private static void readEmptyNames(
      Connection conn, String schema, List<String> tables, Set<String> into) throws SQLException {
    try (PreparedStatement ps = prepare(conn, TiberoDmlSql.emptyTables(schema, tables));
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        String name = rs.getString(1);
        if (name != null) {
          into.add(name.trim());
        }
      }
    }
  }

  /** 통계용 이름 목록: 중복을 없애고 형식이 틀린 이름은 경고와 함께 뺀다(통계는 예외를 던지지 않는다). */
  private static List<String> validStatNames(Collection<String> tableNames, String operation) {
    List<String> valid = new ArrayList<>();
    for (String name : new LinkedHashSet<>(tableNames)) {
      try {
        DataTableNames.validate(name);
        valid.add(name);
      } catch (InvalidTableNameException e) {
        log.warn("Tibero {}: 형식이 틀린 테이블 이름이라 건너뛴다: {}", operation, name);
      }
    }
    return valid;
  }

  /** 테이블 하나의 통계 실패 경고. 드라이버 메시지 대신 예외 클래스와 오류 코드만 남긴다(접속 정보 노출 방지). */
  /** 서버 무응답·연결 끊김으로 통계 회차를 멈췄다는 경고(회차당 한 번). 드라이버 메시지에 접속 정보가 섞일 수 있어 예외 클래스와 오류 코드만 남긴다. */
  private static void warnStatAborted(String operation, long tenantId, SQLException e) {
    log.warn(
        "Tibero {} 중단 — 테넌트 {} 저장소가 응답하지 않아 이번 회차는 여기까지만 센다: {} (code {})",
        operation,
        tenantId,
        e.getClass().getName(),
        e.getErrorCode());
  }

  private static void warnStatFailure(
      String operation, long tenantId, String table, SQLException e) {
    log.warn(
        "Tibero {} 실패 — 테넌트 {} 테이블 {} 은 이번에 빠진다: {} (code {})",
        operation,
        tenantId,
        table,
        e.getClass().getName(),
        e.getErrorCode());
  }

  @Override
  public boolean checkDataUniqueness(String tableName, List<String> pkColumnNames) {
    if (pkColumnNames.isEmpty()) {
      return true;
    }
    Ctx c = ctx("checkDataUniqueness");
    // 전체 행 수와 고유 PK 조합 수를 한 번의 스캔으로 센다(NULL 의미는 빌더 Javadoc).
    String sql = TiberoDmlSql.uniqueness(c.schema(), tableName, pkColumnNames);
    return connections.withDml(
        c.tenantId(),
        conn -> {
          try (PreparedStatement ps = prepare(conn, sql);
              ResultSet rs = ps.executeQuery()) {
            return !rs.next() || rs.getLong(1) == rs.getLong(2);
          }
        });
  }

  /** 컬럼에 NULL 이 한 건이라도 있는지 확인한다. 한 행만 읽으므로 전체 스캔을 피한다. */
  @Override
  public boolean hasNulls(String tableName, String columnName) {
    Ctx c = ctx("hasNulls");
    String sql = TiberoDmlSql.hasNulls(c.schema(), tableName, columnName);
    return connections.withDml(
        c.tenantId(),
        conn -> {
          try (PreparedStatement ps = prepare(conn, sql);
              ResultSet rs = ps.executeQuery()) {
            return rs.next();
          }
        });
  }

  @Override
  public List<Map<String, Object>> findDuplicateRows(
      String tableName, List<String> pkColumnNames, int limit) {
    Ctx c = ctx("findDuplicateRows");
    String sql = TiberoDmlSql.duplicates(c.schema(), tableName, pkColumnNames);
    return connections.withDml(
        c.tenantId(),
        conn -> {
          try (PreparedStatement ps = prepare(conn, sql)) {
            ps.setLong(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
              List<Map<String, Object>> rows = readAll(rs);
              // PG 와 같이 건수는 Long — NUMBER 는 BigDecimal 로 읽혀 오므로 명시 변환한다.
              for (Map<String, Object> r : rows) {
                if (r.get("duplicate_count") instanceof Number n) {
                  r.put("duplicate_count", n.longValue());
                }
              }
              return rows;
            }
          }
        });
  }

  // ---------- 삽입 ----------

  @Override
  public void insertBatch(
      String tableName,
      List<String> columns,
      List<Map<String, Object>> rows,
      Map<String, String> columnTypes) {
    insertChunks(tableName, columns, rows, null, null, columnTypes);
  }

  @Override
  public void insertBatchWithProgress(
      String tableName,
      List<String> columns,
      List<Map<String, Object>> rows,
      BiConsumer<Integer, Integer> progressCallback,
      Map<String, String> columnTypes) {
    insertChunks(tableName, columns, rows, null, progressCallback, columnTypes);
  }

  @Override
  public void insertBatch(
      String tableName,
      List<String> columns,
      List<Map<String, Object>> rows,
      Long importId,
      Map<String, String> columnTypes) {
    insertChunks(tableName, columns, rows, importId, null, columnTypes);
  }

  @Override
  public void insertBatchWithProgress(
      String tableName,
      List<String> columns,
      List<Map<String, Object>> rows,
      Long importId,
      BiConsumer<Integer, Integer> progressCallback,
      Map<String, String> columnTypes) {
    insertChunks(tableName, columns, rows, importId, progressCallback, columnTypes);
  }

  /** 일반 테이블 삽입. {@code importId} 가 null 이면 IMPORT_ID 를 건드리지 않는다. */
  private void insertChunks(
      String tableName,
      List<String> columns,
      List<Map<String, Object>> rows,
      Long importId,
      BiConsumer<Integer, Integer> progress,
      Map<String, String> columnTypes) {
    checkWrite(tableName, columnTypes);
    if (rows.isEmpty()) {
      return;
    }
    Ctx c = ctx("insertBatch");
    String sql = TiberoDmlSql.insert(c.schema(), tableName, columns, importId != null);
    runInsertChunks(c.tenantId(), sql, columns, rows, importId, progress);
  }

  /**
   * 같은 INSERT 를 500행 단위 {@code addBatch}/{@code executeBatch} 로 실행하고 청크마다 진행 콜백 {@code (누적, 전체)} 을
   * 부른다. 스테이징 삽입도 이 경로를 쓴다.
   */
  private void runInsertChunks(
      long tenantId,
      String sql,
      List<String> columns,
      List<Map<String, Object>> rows,
      Long importId,
      BiConsumer<Integer, Integer> progress) {
    int total = rows.size();
    connections.withDml(
        tenantId,
        conn -> {
          try (PreparedStatement ps = prepare(conn, sql)) {
            int processed = 0;
            for (int start = 0; start < total; start += DataStoreBatching.CHUNK) {
              int end = Math.min(start + DataStoreBatching.CHUNK, total);
              for (Map<String, Object> row : rows.subList(start, end)) {
                bindRow(ps, columns, row, importId);
                ps.addBatch();
              }
              ps.executeBatch();
              processed += end - start;
              if (progress != null) {
                progress.accept(processed, total);
              }
            }
          }
          return null;
        });
  }

  // ---------- upsert ----------

  @Override
  public UpsertResult upsertBatch(
      String tableName,
      List<String> columns,
      List<String> pkColumns,
      List<Map<String, Object>> rows,
      Long importId,
      Map<String, String> columnTypes) {
    return upsert(tableName, columns, pkColumns, rows, importId, null, columnTypes);
  }

  @Override
  public UpsertResult upsertBatchWithProgress(
      String tableName,
      List<String> columns,
      List<String> pkColumns,
      List<Map<String, Object>> rows,
      Long importId,
      BiConsumer<Integer, Integer> progressCallback,
      Map<String, String> columnTypes) {
    return upsert(tableName, columns, pkColumns, rows, importId, progressCallback, columnTypes);
  }

  /**
   * PK 기준 upsert. MERGE 는 inserted/updated 를 알려주지 않으므로 청크마다 ① 청크 안 PK 중복을 마지막 행 우선으로 정리하고 ② 기존 키를
   * 먼저 조회한 뒤 ③ MERGE 를 배치로 실행하고 ④ inserted = 정리된 행 수 − 기존 키 수, updated = 기존 키 수로 센다. 전부 같은 커넥션이라 앞
   * 청크가 넣은 행은 뒤 청크에서 "기존" 으로 보인다(PG 의 xmax 판정과 같은 결과).
   */
  private UpsertResult upsert(
      String tableName,
      List<String> columns,
      List<String> pkColumns,
      List<Map<String, Object>> rows,
      Long importId,
      BiConsumer<Integer, Integer> progress,
      Map<String, String> columnTypes) {
    checkWrite(tableName, columnTypes);
    if (rows.isEmpty()) {
      return new UpsertResult(0, 0);
    }
    if (pkColumns.isEmpty()) {
      throw new IllegalStateException("UPSERT mode requires at least one primary key column");
    }
    if (!columns.containsAll(pkColumns)) {
      throw new IllegalArgumentException("PK 컬럼은 적재 컬럼에 포함돼야 합니다");
    }
    Ctx c = ctx("upsertBatch");
    String mergeSql =
        TiberoDmlSql.merge(c.schema(), tableName, columns, pkColumns, importId != null);
    int total = rows.size();
    return connections.withDml(
        c.tenantId(),
        conn -> {
          int inserted = 0;
          int updated = 0;
          int processed = 0;
          try (PreparedStatement ps = prepare(conn, mergeSql)) {
            for (int start = 0; start < total; start += DataStoreBatching.CHUNK) {
              int end = Math.min(start + DataStoreBatching.CHUNK, total);
              List<Map<String, Object>> chunk = dedupeLastWins(rows.subList(start, end), pkColumns);
              int existing = countExisting(conn, c.schema(), tableName, pkColumns, chunk);
              for (Map<String, Object> row : chunk) {
                bindRow(ps, columns, row, importId);
                ps.addBatch();
              }
              ps.executeBatch();
              inserted += chunk.size() - existing;
              updated += existing;
              processed += end - start;
              if (progress != null) {
                progress.accept(processed, total);
              }
            }
          }
          return new UpsertResult(inserted, updated);
        });
  }

  /**
   * 같은 PK 의 행을 마지막 것만 남긴다(입력 순서 유지). PK 에 null 이 있으면 MERGE 의 ON 이 맞지 않아 매번 새 행이 되므로 서로 다른 행으로 둔다.
   */
  private static List<Map<String, Object>> dedupeLastWins(
      List<Map<String, Object>> chunk, List<String> pkColumns) {
    Map<Object, Map<String, Object>> byKey = new LinkedHashMap<>();
    for (Map<String, Object> row : chunk) {
      List<Object> key = keyOf(row, pkColumns);
      byKey.put(key.contains(null) ? new Object() : key, row);
    }
    return new ArrayList<>(byKey.values());
  }

  /** 행의 PK 값 목록(비교용으로 정규화). */
  private static List<Object> keyOf(Map<String, Object> row, List<String> pkColumns) {
    List<Object> key = new ArrayList<>();
    for (String pk : pkColumns) {
      key.add(normalizeKey(row.get(pk)));
    }
    return key;
  }

  /**
   * 키 비교용 정규화. NUMBER 는 Long 또는 BigDecimal 로 읽혀 오고 배치 값은 Integer·Long·BigDecimal 등 제각각이라 {@code
   * BigDecimal} 로 통일하고 {@code stripTrailingZeros} 로 스케일 차이를 없앤다. Boolean 은 NUMBER(1) 의 1/0 과 같게 본다.
   */
  static Object normalizeKey(Object v) {
    if (v == null) {
      return null;
    }
    if (v instanceof Boolean b) {
      return b ? BigDecimal.ONE : BigDecimal.ZERO;
    }
    if (v instanceof Number n) {
      try {
        BigDecimal bd = n instanceof BigDecimal b ? b : new BigDecimal(n.toString());
        return bd.signum() == 0 ? BigDecimal.ZERO : bd.stripTrailingZeros();
      } catch (NumberFormatException e) {
        return v;
      }
    }
    return v;
  }

  /** 정리된 청크 중 대상 테이블에 이미 있는 키의 수. IN 목록은 1000 개 이하로 나눠 조회한다. */
  private static int countExisting(
      Connection conn,
      String schema,
      String tableName,
      List<String> pkColumns,
      List<Map<String, Object>> chunk)
      throws SQLException {
    List<Map<String, Object>> probe = new ArrayList<>();
    Set<List<Object>> wanted = new HashSet<>();
    for (Map<String, Object> row : chunk) {
      List<Object> key = keyOf(row, pkColumns);
      if (!key.contains(null) && wanted.add(key)) {
        probe.add(row);
      }
    }
    Set<List<Object>> found = new HashSet<>();
    for (int start = 0; start < probe.size(); start += IN_LIMIT) {
      List<Map<String, Object>> part =
          probe.subList(start, Math.min(start + IN_LIMIT, probe.size()));
      String sql = TiberoDmlSql.existingKeys(schema, tableName, pkColumns, part.size());
      try (PreparedStatement ps = prepare(conn, sql)) {
        int i = 1;
        for (Map<String, Object> row : part) {
          i = bindRow(ps, i, pkColumns, row);
        }
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            List<Object> key = new ArrayList<>();
            for (int col = 1; col <= pkColumns.size(); col++) {
              key.add(normalizeKey(TiberoValues.read(rs, col)));
            }
            if (wanted.contains(key)) {
              found.add(key);
            }
          }
        }
      }
    }
    return found.size();
  }

  // ---------- 단건·삭제 ----------

  @Override
  public int deleteRows(String tableName, List<Long> rowIds) {
    DataTableNames.validate(tableName);
    if (rowIds == null || rowIds.isEmpty()) {
      return 0;
    }
    Ctx c = ctx("deleteRows");
    return connections.withDml(
        c.tenantId(),
        conn -> {
          int deleted = 0;
          for (int start = 0; start < rowIds.size(); start += IN_LIMIT) {
            List<Long> part = rowIds.subList(start, Math.min(start + IN_LIMIT, rowIds.size()));
            try (PreparedStatement ps =
                prepare(conn, TiberoDmlSql.deleteByIds(c.schema(), tableName, part.size()))) {
              for (int i = 0; i < part.size(); i++) {
                ps.setLong(i + 1, part.get(i));
              }
              deleted += ps.executeUpdate();
            }
          }
          return deleted;
        });
  }

  @Override
  public void truncateTable(String tableName) {
    DataTableNames.validate(tableName);
    Ctx c = ctx("truncateTable");
    String sql = TiberoDmlSql.deleteAll(c.schema(), tableName);
    // TRUNCATE 는 DDL(암묵 커밋)이라 쓰지 않는다 — 묶인 커넥션에서 DELETE 하면 롤백도 된다.
    connections.withDml(
        c.tenantId(),
        conn -> {
          try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
          }
          return null;
        });
  }

  @Override
  public Long insertRow(
      String tableName,
      List<String> columns,
      Map<String, Object> row,
      Map<String, String> columnTypes) {
    Ctx c = enter("insertRow", tableName, columnTypes);
    String nextId = TiberoDmlSql.nextId(c.schema(), tableName);
    String insert = TiberoDmlSql.insertWithId(c.schema(), tableName, columns);
    return connections.withDml(
        c.tenantId(),
        conn -> {
          // RETURNING INTO 대신 ID 를 먼저 받아 INSERT 에 쓴다(Oracle 계열 공통 구문만 사용).
          long id;
          try (PreparedStatement ps = prepare(conn, nextId);
              ResultSet rs = ps.executeQuery()) {
            rs.next();
            id = rs.getLong(1);
          }
          try (PreparedStatement ps = prepare(conn, insert)) {
            ps.setLong(1, id);
            bindRow(ps, 2, columns, row);
            ps.executeUpdate();
          }
          return id;
        });
  }

  @Override
  public void updateRow(
      String tableName,
      long rowId,
      List<String> columns,
      Map<String, Object> row,
      Map<String, String> columnTypes) {
    Ctx c = enter("updateRow", tableName, columnTypes);
    String sql = TiberoDmlSql.update(c.schema(), tableName, columns);
    int affected =
        connections.withDml(
            c.tenantId(),
            conn -> {
              try (PreparedStatement ps = prepare(conn, sql)) {
                int i = bindRow(ps, 1, columns, row);
                ps.setLong(i, rowId);
                return ps.executeUpdate();
              }
            });
    if (affected == 0) {
      throw new RowNotFoundException();
    }
  }

  @Override
  public Map<String, Object> getRow(
      String tableName, List<String> columns, long rowId, Map<String, String> columnTypes) {
    Ctx c = enter("getRow", tableName, columnTypes);
    String sql = TiberoDmlSql.getRow(c.schema(), tableName, columns);
    Map<String, Object> row =
        connections.withDml(
            c.tenantId(),
            conn -> {
              try (PreparedStatement ps = prepare(conn, sql)) {
                ps.setLong(1, rowId);
                try (ResultSet rs = ps.executeQuery()) {
                  return rs.next() ? TiberoValues.readRow(rs) : null;
                }
              }
            });
    if (row == null) {
      throw new RowNotFoundException();
    }
    return row;
  }

  // ---------- 스테이징 ----------

  /**
   * 대상 테이블의 컬럼 타입을 {@code ALL_TAB_COLUMNS} 에서 읽어 같은 타입의 스테이징 테이블(PK 없음, {@code _SEQ} 포함)과 전용 시퀀스를
   * 만든다. DDL 이라 별도 자동 커밋 커넥션으로 실행한다. 반환 이름은 파사드의 이름 검증({@code [a-z][a-z0-9_]*})을 통과하도록 소문자이며, 이후
   * 호출에서 대문자 물리 이름으로 되돌린다.
   */
  @Override
  public String createStagingTable(String targetTableName, List<String> columns) {
    DataTableNames.validate(targetTableName);
    for (String col : columns) {
      DataTableNames.validate(col);
    }
    Ctx c = ctx("createStagingTable");
    String physical = TiberoNames.stagingTableName();
    String logical = physical.toLowerCase(Locale.ROOT);
    connections.withDdl(
        c.tenantId(),
        conn -> {
          Map<String, TiberoColumnType> types =
              TiberoCatalog.columnTypes(conn, c.schema(), targetTableName);
          List<String> ddls = new ArrayList<>();
          for (String col : columns) {
            TiberoColumnType type = types.get(col);
            if (type == null) {
              throw new IllegalStateException(
                  "Column '" + col + "' not found on table '" + targetTableName + "'");
            }
            ddls.add(type.ddl());
          }
          List<String> statements = TiberoDmlSql.createStaging(c.schema(), physical, columns, ddls);
          try (Statement st = conn.createStatement()) {
            st.execute(statements.get(0));
            try {
              st.execute(statements.get(1));
            } catch (SQLException e) {
              // 시퀀스 생성이 실패하면 테이블만 남지 않도록 지우고 원래 오류를 다시 던진다.
              try {
                st.execute(TiberoDmlSql.dropStaging(c.schema(), physical).get(0));
              } catch (SQLException cleanup) {
                e.addSuppressed(cleanup);
              }
              throw e;
            }
          }
          return null;
        });
    return logical;
  }

  @Override
  public void insertStagingBatchWithProgress(
      String stagingTable,
      List<String> columns,
      List<Map<String, Object>> rows,
      BiConsumer<Integer, Integer> progressCallback) {
    DataTableNames.validate(stagingTable);
    if (rows.isEmpty()) {
      return;
    }
    Ctx c = ctx("insertStagingBatch");
    String sql =
        TiberoDmlSql.insertStaging(c.schema(), TiberoNames.physical(stagingTable), columns);
    runInsertChunks(c.tenantId(), sql, columns, rows, null, progressCallback);
  }

  /**
   * MERGE 보다 먼저 같은 커넥션에서 집계 질의 하나로 "정리 소스 행 수(total)" 와 "그 중 이미 대상에 있는 키 수(existing)" 를 센다.
   * inserted = total − existing, updated = existing 이며 MERGE 가 돌려주는 영향 행 수는 드라이버마다 다를 수 있어 쓰지 않는다.
   */
  @Override
  public UpsertResult promoteStagingToUpsert(
      String stagingTable,
      String targetTableName,
      List<String> columns,
      List<String> pkColumns,
      Long importId) {
    DataTableNames.validate(stagingTable);
    DataTableNames.validate(targetTableName);
    if (pkColumns.isEmpty()) {
      throw new IllegalStateException("UPSERT mode requires at least one primary key column");
    }
    Ctx c = ctx("promoteStagingToUpsert");
    String stg = TiberoNames.physical(stagingTable);
    String countSql =
        TiberoDmlSql.promoteUpsertCount(c.schema(), targetTableName, stg, columns, pkColumns);
    String mergeSql =
        TiberoDmlSql.promoteUpsertMerge(
            c.schema(), targetTableName, stg, columns, pkColumns, importId != null);
    return connections.withDml(
        c.tenantId(),
        conn -> {
          long total;
          long existing;
          try (PreparedStatement ps = conn.prepareStatement(countSql);
              ResultSet rs = ps.executeQuery()) {
            rs.next();
            total = rs.getLong(1);
            existing = rs.getLong(2);
          }
          try (PreparedStatement ps = conn.prepareStatement(mergeSql)) {
            if (importId != null) {
              TiberoValues.bind(ps, 1, importId);
            }
            ps.executeUpdate(); // 반환값은 쓰지 않는다
          }
          return new UpsertResult((int) (total - existing), (int) existing);
        });
  }

  /**
   * 대상을 비우고 스테이징을 복사한다. 한 {@code withDml} 호출(= 같은 묶인 커넥션) 안에서 {@code DELETE} → {@code INSERT …
   * SELECT} 순으로 실행하므로, 호출자 트랜잭션이 롤백되면 비우기도 함께 되돌아간다. PK 가 없으면 중복 정리 없이 모든 행을 복사한다.
   */
  @Override
  public void promoteStagingToReplace(
      String stagingTable, String targetTableName, List<String> columns, List<String> pkColumns) {
    DataTableNames.validate(stagingTable);
    DataTableNames.validate(targetTableName);
    Ctx c = ctx("promoteStagingToReplace");
    String deleteSql = TiberoDmlSql.deleteAll(c.schema(), targetTableName);
    String insertSql =
        TiberoDmlSql.promoteReplace(
            c.schema(), targetTableName, TiberoNames.physical(stagingTable), columns, pkColumns);
    connections.withDml(
        c.tenantId(),
        conn -> {
          try (PreparedStatement ps = conn.prepareStatement(deleteSql)) {
            ps.executeUpdate();
          }
          try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
            ps.executeUpdate();
          }
          return null;
        });
  }

  /** 스테이징 테이블·시퀀스를 지운다(DDL — 별도 커넥션). 이미 없으면 무시하고 그 밖의 오류는 던진다. */
  @Override
  public void dropStagingTable(String stagingTable) {
    DataTableNames.validate(stagingTable);
    Ctx c = ctx("dropStagingTable");
    String stg = TiberoNames.physical(stagingTable);
    connections.withDdl(c.tenantId(), conn -> dropStaging(conn, c.schema(), stg));
  }

  /**
   * 만든 지 {@code minAge} 가 지난 고아 스테이징 테이블(과 전용 시퀀스)을 지운다(WD-74 A8). 정상 경로에서는 가져오기의 finally 가 {@link
   * #dropStagingTable} 을 부르지만, 워커가 강제 종료되면 남는다 — {@code StagingTableCleanupService} 가 READY 외부 저장소
   * 테넌트마다 주기적으로 부른다.
   *
   * <p>대상은 {@code ALL_OBJECTS} 에서 이름이 정확히 {@code STG_<24hex>} 인 테이블뿐이다(사용자 테이블 보호, {@link
   * TiberoCatalog#staleStagingTables}). 테넌트 컨텍스트가 필요 없도록 {@code tenantId} 를 직접 받는다. 한 테이블의 삭제 실패는
   * 경고만 남기고 나머지를 계속한다(하나가 막혀 나머지가 영원히 남지 않도록).
   *
   * <p>스윕은 {@link ExternalStoreConnections#withDdl} 의 단발 직결 커넥션을 쓴다 — 풀이 없는 테넌트에 풀을 새로 만들거나, 요청을 처리
   * 중인 다른 테넌트의 풀을 LRU 에서 밀어내지 않는다.
   *
   * @return 실제로 지운 스테이징 테이블 수(이미 없던 것은 세지 않는다)
   */
  @Override
  public int dropStaleStagingTables(long tenantId, Duration minAge) {
    String schema = connections.schemaOfTransient(tenantId);
    return connections.withDdl(
        tenantId,
        conn -> {
          int dropped = 0;
          for (String stg : TiberoCatalog.staleStagingTables(conn, schema, minAge)) {
            try {
              if (dropStaging(conn, schema, stg)) {
                dropped++;
              }
            } catch (SQLException e) {
              // 드라이버 메시지에 접속 정보가 섞일 수 있어 예외 클래스 이름만 남긴다.
              log.warn(
                  "테넌트 {} 고아 스테이징 테이블 삭제 실패(다음 주기에 재시도): {} ({})",
                  tenantId,
                  stg,
                  e.getClass().getName());
            }
          }
          return dropped;
        });
  }

  /**
   * 한 DDL 커넥션에서 스테이징 테이블 → 전용 시퀀스 순으로 지운다. 이미 없는 객체는 무시하고 그 밖의 오류는 던진다. {@link #dropStagingTable} 과
   * 고아 스윕이 같은 규칙을 쓰도록 한곳에 둔다.
   *
   * @return 테이블을 실제로 지웠으면 true(이미 없었으면 false)
   */
  private static boolean dropStaging(Connection conn, String schema, String stagingPhysical)
      throws SQLException {
    List<String> statements = TiberoDmlSql.dropStaging(schema, stagingPhysical);
    boolean dropped =
        TiberoErrors.runIgnoring(conn, statements.get(0), TiberoDialect.NotFound.TABLE);
    TiberoErrors.runIgnoring(conn, statements.get(1), TiberoDialect.NotFound.SEQUENCE);
    return dropped;
  }
}
