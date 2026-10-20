package com.smartfirehub.dataset.storage.tibero;

import com.smartfirehub.dataset.dto.ColumnStatsResponse;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreFeatureUnsupportedException;
import com.smartfirehub.dataset.storage.DataTableStore;
import com.smartfirehub.dataset.storage.external.ExternalStoreConnections;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Tibero 용 {@link DataTableStore}(WD-74): DDL·보상·컬럼 통계.
 *
 * <p><b>연결.</b> 모든 DDL 은 {@link ExternalStoreConnections#withDdl} 의 별도 자동 커밋 커넥션으로 실행한다(Oracle 계열
 * DDL 은 암묵 커밋이라 DML 이 묶인 커넥션에서 실행하면 진행 중인 DML 이 확정된다). 읽기 전용인 통계만 {@code withDml} 을 쓴다.
 *
 * <p><b>보상(R2).</b> DDL 은 PostgreSQL 트랜잭션에 참여하지 못하므로, 호출자 트랜잭션이 롤백되면 {@code createTable}/{@code
 * cloneTable} 은 테이블·시퀀스를, {@code addColumn} 은 컬럼을 지운다. {@code dropTable} 은 되돌릴 수 없으므로 커밋 이후({@code
 * afterCommit})로 미루고, {@code dropColumn} 도 같은 이유로 미룬다. 그 밖의 컬럼 변경(이름·타입·NULL·인덱스)은 보상하지 않는다 — 롤백
 * 원인이 드물고(메타 저장 실패), 결과가 "물리 컬럼만 바뀐 상태"라 재시도할 수 있기 때문이다. 보상은 DDL 을 실행하기 전에 {@link
 * ExternalStoreConnections#rollbackBound} 로 같은 트랜잭션의 묶인 DML 커넥션을 먼저 롤백·반납한다 — 그 커넥션이 방금 만든 테이블·컬럼의
 * 행 잠금을 쥔 채 남아 있으면 다른 세션의 DROP 이 ORA-00054 로 실패한다.
 *
 * <p><b>이미 있는 테이블(A4).</b> {@code createTable}/{@code cloneTable} 은 같은 이름의 테이블·시퀀스가 이미 있으면 지우지 않고
 * 409 {@code DATA_STORE_TABLE_EXISTS} 로 거부한다(인터페이스 계약의 "고아 테이블 먼저 삭제" 와 다르다 — PG 는 DDL 이 트랜잭션이라
 * 안전하지만 여기서 미리 DROP 하면 동시에 같은 이름을 만드는 다른 요청의 테이블을 지울 수 있다). 생성 도중 실패하면 <b>이번 호출이 만든 객체만</b> 지운다.
 * 고아는 커밋 뒤 삭제({@code dropTable})가 실패했을 때만 생기며 ERROR 로그(테이블 이름)로 남으므로 운영자가 수동으로 지운다 — 조용히 지우지 않는다.
 *
 * <p><b>인덱스 키 길이(A6).</b> 인덱스 컬럼들의 선언 바이트 폭 합이 {@link TiberoDialect#MAX_INDEX_KEY_BYTES} 를 넘으면 DDL
 * 전에 400 으로 거부한다(원문 ORA-01450 대신).
 *
 * <p><b>미지원(R4).</b> 임시 테이블 교체 계열과 온라인 PK 인덱스 생성은 {@link DataStoreFeatureUnsupportedException}(422,
 * {@link #unsupported}). {@code finishReplace} 는 인터페이스 기본 구현을 그대로 두며 {@code swapTable}/{@code
 * dropTempTable} 을 거쳐 같은 예외가 난다.
 *
 * <p><b>검색 타입 캐시.</b> 컬럼 구조를 바꾸는 DDL(생성·복제·삭제·컬럼 추가/삭제/이름·타입 변경) 뒤에는 {@link TiberoColumnTypeCache}
 * 의 해당 테이블 항목을 지워 다음 검색이 카탈로그를 다시 읽게 한다.
 */
@Slf4j
@Component
public class TiberoDataTableStore implements DataTableStore {

  private static final String TEMP_FEATURE = "임시 테이블 교체(파이프라인 출력)";
  private static final String ONLINE_PK_FEATURE = "온라인 PK 인덱스 생성";

  /** 통계 평균을 계산하는 숫자 논리 타입(PG 구현과 동일). */
  private static final Set<String> NUMERIC_TYPES = Set.of("INTEGER", "DECIMAL");

  private final ExternalStoreConnections connections;

  /** 행 저장소의 검색 타입 캐시 — 컬럼 구조를 바꾼 뒤 무효화한다. */
  private final TiberoColumnTypeCache columnTypeCache;

  public TiberoDataTableStore(
      ExternalStoreConnections connections, TiberoColumnTypeCache columnTypeCache) {
    this.connections = connections;
    this.columnTypeCache = columnTypeCache;
  }

  @Override
  public DataStoreBackend backend() {
    return DataStoreBackend.TIBERO;
  }

  @Override
  public void createTable(String tableName, List<DatasetColumnRequest> columns) {
    long tenantId = TenantContext.require("Tibero createTable");
    String schema = connections.schemaOf(tenantId);
    // 빌더가 이름·인덱스 키 길이 검증을 하므로 DDL 을 실행하기 전에 먼저 만든다(잘못되면 아무것도 만들지 않는다).
    // 같은 이름의 기존 테이블은 지우지 않는다 — 이미 있으면 runCreating 이 409 로 거부한다(클래스 Javadoc A4).
    List<String> statements = TiberoDdlSql.createTable(schema, tableName, columns);
    runCreating(tenantId, schema, tableName, statements);
    // 같은 이름을 지웠다 다시 만든 경우 옛 구조가 캐시에 남지 않게 한다.
    columnTypeCache.invalidate(tenantId, tableName);
    registerRollbackDrop(tenantId, schema, tableName);
  }

  @Override
  public void addColumn(String tableName, DatasetColumnRequest column) {
    TiberoDdlSql.rejectSystemColumn(column.columnName());
    long tenantId = TenantContext.require("Tibero addColumn");
    String schema = connections.schemaOf(tenantId);
    List<String> statements = TiberoDdlSql.addColumn(schema, tableName, column);
    // 컬럼 추가 문장이 성공한 뒤에만 보상을 등록하기 위해 첫 문장 직후 등록한다.
    execute(tenantId, statements.get(0));
    columnTypeCache.invalidate(tenantId, tableName);
    String dropSql = TiberoDdlSql.dropColumn(schema, tableName, column.columnName());
    String objects = columnObjects(tableName, column.columnName());
    register(
        new Compensation(
            connections,
            tenantId,
            Compensation.Phase.ROLLBACK,
            List.of(TiberoDdlSql.DdlStep.of(dropSql)),
            objects,
            "롤백 보상(컬럼 삭제) 실패 — 물리 스키마가 어긋났을 수 있음: " + objects,
            null));
    for (int i = 1; i < statements.size(); i++) {
      execute(tenantId, statements.get(i));
    }
  }

  @Override
  public void setColumnIndex(
      String tableName, String columnName, boolean indexed, String columnType) {
    if ("GEOMETRY".equalsIgnoreCase(columnType)) {
      throw unsupported("GEOMETRY 컬럼");
    }
    long tenantId = TenantContext.require("Tibero setColumnIndex");
    String schema = connections.schemaOf(tenantId);
    if (indexed) {
      // 단일 컬럼 인덱스는 가장 넓은 TEXT 도 4000 바이트라 현재 한도(6000)를 넘을 수 없다. 타입을 알면 한도 변경에 대비해 검사만 하고,
      // 모르면(null) 카탈로그를 읽지 않는다 — 읽어도 결과가 바뀌지 않는 질의라서다.
      if (columnType != null) {
        TiberoDdlSql.checkIndexKeyLength(
            List.of(columnName), List.of(TiberoDialect.indexKeyBytes(columnType, null)));
      }
      execute(tenantId, TiberoDdlSql.createIndex(schema, tableName, columnName));
    } else {
      executeIgnoring(
          tenantId,
          TiberoDdlSql.dropIndex(schema, tableName, columnName),
          TiberoDialect.NotFound.INDEX);
    }
  }

  /**
   * PK 유니크 인덱스를 다시 만든다. 검사·옛 정의 읽기·삭제·생성·복구를 <b>한 DDL 커넥션</b>에서 한다.
   *
   * <p>DDL 은 자동 커밋이라 "옛 인덱스 삭제 → 새 인덱스 생성" 사이에 실패하면 PK 유일성 강제가 조용히 사라진다(중복 키가 들어와도 막지 못한다). 그래서 지우기
   * 전에 {@code ALL_IND_COLUMNS} 에서 옛 인덱스 컬럼을 읽어 두고, 새 인덱스 생성이 실패하면 옛 인덱스를 되살린 뒤(최선 노력, 실패 시 ERROR
   * 로그) 원래 예외를 다시 던진다. {@code deleteColumn} 이 지운 PK 컬럼은 물리 삭제가 커밋 뒤로 미뤄져 아직 있으므로 옛 정의 그대로 되살릴 수 있다.
   */
  @Override
  public void recreatePrimaryKeyIndex(String tableName, List<String> pkColumnNames) {
    long tenantId = TenantContext.require("Tibero recreatePrimaryKeyIndex");
    String schema = connections.schemaOf(tenantId);
    String indexName = TiberoNames.primaryKeyIndexName(schema, tableName);
    String dropSql = TiberoDdlSql.dropPrimaryKeyIndex(schema, tableName);
    String createSql =
        pkColumnNames.isEmpty()
            ? null
            : TiberoDdlSql.createPrimaryKeyIndex(schema, tableName, pkColumnNames);
    connections.withDdl(
        tenantId,
        conn -> {
          if (createSql != null) {
            // 호출자는 컬럼 이름만 준다 — 실제 물리 타입으로 키 길이를 검사하고, 기존 PK 인덱스를 지우기 전에 거부한다.
            checkPrimaryKeyLength(conn, schema, tableName, pkColumnNames);
          }
          List<String> oldColumns = TiberoCatalog.indexColumns(conn, schema, indexName);
          TiberoErrors.runIgnoring(conn, dropSql, TiberoDialect.NotFound.INDEX);
          if (createSql == null) {
            return null;
          }
          try {
            TiberoErrors.runIgnoring(conn, createSql);
          } catch (SQLException e) {
            restorePrimaryKeyIndex(conn, schema, tableName, oldColumns, e);
            throw e;
          }
          return null;
        });
  }

  /**
   * 새 PK 인덱스 생성이 실패했을 때 옛 인덱스를 되살린다(최선 노력). 옛 인덱스가 없었으면 아무것도 하지 않는다. 되살리기도 실패하면 PK 유일성 강제가 꺼진 상태이므로
   * 테이블 이름과 함께 ERROR 로 남기고, 그 실패는 원래 예외에 덧붙인다.
   */
  private static void restorePrimaryKeyIndex(
      Connection conn,
      String schema,
      String tableName,
      List<String> oldColumns,
      SQLException cause) {
    if (oldColumns.isEmpty()) {
      return;
    }
    try {
      TiberoErrors.runIgnoring(
          conn, TiberoDdlSql.createPrimaryKeyIndex(schema, tableName, oldColumns));
    } catch (SQLException restore) {
      log.error(
          "Tibero PK 인덱스 재생성 실패 후 옛 인덱스 복구도 실패 — PK 유일성 강제가 꺼짐(수동 복구 필요): {}", tableName, restore);
      cause.addSuppressed(restore);
    }
  }

  /**
   * {@code ALL_TAB_COLUMNS} 의 물리 타입으로 PK 유니크 인덱스 키 길이를 검사한다. 카탈로그에 없는 컬럼은 0 으로 본다(이어지는 DDL 이 어차피
   * 실패하므로 키 길이 오류로 오인시키지 않는다).
   */
  private static void checkPrimaryKeyLength(
      Connection conn, String schema, String tableName, List<String> pkColumnNames)
      throws SQLException {
    Map<String, TiberoColumnType> types = TiberoCatalog.columnTypes(conn, schema, tableName);
    List<Integer> bytes = new ArrayList<>();
    for (String col : pkColumnNames) {
      TiberoColumnType type = types.get(col.toLowerCase(Locale.ROOT));
      bytes.add(type == null ? 0 : type.indexKeyBytes());
    }
    TiberoDdlSql.checkIndexKeyLength(pkColumnNames, bytes);
  }

  @Override
  public void createPrimaryKeyIndexConcurrently(String tableName, List<String> pkColumnNames) {
    throw unsupported(ONLINE_PK_FEATURE);
  }

  @Override
  public void dropTable(String tableName) {
    long tenantId = TenantContext.require("Tibero dropTable");
    String schema = connections.schemaOf(tenantId);
    List<TiberoDdlSql.DdlStep> steps = dropSteps(schema, tableName, true, true);
    columnTypeCache.invalidate(tenantId, tableName);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      // 되돌릴 수 없는 삭제는 PG 커밋이 확정된 뒤에 실행한다. 실패해도 이미 커밋됐으므로 로그만 남긴다.
      // 이 고아는 자동으로 지우지 않는다(createTable 도 지우지 않고 409) — 운영자가 이 로그로 찾아 수동 정리한다.
      TransactionSynchronizationManager.registerSynchronization(
          new Compensation(
              connections,
              tenantId,
              Compensation.Phase.AFTER_COMMIT,
              steps,
              tableObjects(tableName),
              "커밋 후 Tibero 테이블 삭제 실패 — 고아 테이블이 남음(수동 정리 필요): " + tableName,
              null));
    } else {
      runSteps(tenantId, steps);
    }
  }

  @Override
  public void createTempTable(String tableName) {
    throw unsupported(TEMP_FEATURE);
  }

  @Override
  public void swapTable(String tableName) {
    throw unsupported(TEMP_FEATURE);
  }

  @Override
  public void dropTempTable(String tableName) {
    throw unsupported(TEMP_FEATURE);
  }

  @Override
  public void renameColumn(String tableName, String oldName, String newName) {
    long tenantId = TenantContext.require("Tibero renameColumn");
    String schema = connections.schemaOf(tenantId);
    // 새 이름 검증이 들어 있는 빌더 호출을 DDL 실행보다 먼저 모두 끝낸다.
    String renameSql = TiberoDdlSql.renameColumn(schema, tableName, oldName, newName);
    String checkName = TiberoNames.checkConstraintName(schema, tableName, oldName);
    String renameCheckSql = TiberoDdlSql.renameCheckConstraint(schema, tableName, oldName, newName);
    String renameCheckBackSql =
        TiberoDdlSql.renameCheckConstraint(schema, tableName, newName, oldName);
    connections.withDdl(
        tenantId,
        conn -> {
          // BOOLEAN 컬럼만 이름 있는 CHECK(CK_<해시(옛 이름)>) 를 가진다. 컬럼 타입을 모르므로 카탈로그로 확인한다 — 예전처럼 늘 시도하면
          // 비 BOOLEAN 컬럼에서 RENAME CONSTRAINT 가 ORA-23292 로 실패해 물리 컬럼 이름만 바뀐 채 예외가 났다.
          boolean checkRenamed = false;
          if (TiberoCatalog.constraintExists(conn, schema, checkName)) {
            // 제약을 먼저 옮긴다(제약 이름이 컬럼 이름에서 파생되므로 이 순서가 안전하다). 그새 사라졌으면 무시한다.
            checkRenamed =
                TiberoErrors.runIgnoring(conn, renameCheckSql, TiberoDialect.NotFound.CONSTRAINT);
          }
          try {
            TiberoErrors.runIgnoring(conn, renameSql);
          } catch (SQLException e) {
            if (checkRenamed) {
              // 컬럼 이름 변경이 실패하면 제약 이름을 되돌린다(최선 노력). 되돌리기 실패는 원래 오류에 덧붙인다.
              try {
                TiberoErrors.runIgnoring(
                    conn, renameCheckBackSql, TiberoDialect.NotFound.CONSTRAINT);
              } catch (SQLException back) {
                e.addSuppressed(back);
              }
            }
            throw e;
          }
          return null;
        });
    columnTypeCache.invalidate(tenantId, tableName);
  }

  @Override
  public void renameIndex(String tableName, String oldColName, String newColName) {
    long tenantId = TenantContext.require("Tibero renameIndex");
    String schema = connections.schemaOf(tenantId);
    // 인덱스가 없는 컬럼일 수 있으므로 없으면 무시한다.
    executeIgnoring(
        tenantId,
        TiberoDdlSql.renameIndex(schema, tableName, oldColName, newColName),
        TiberoDialect.NotFound.INDEX);
  }

  /**
   * 컬럼 타입을 바꾼다. 기존 데이터 변환은 Tibero 의 암묵 변환에 의존한다(PG 의 {@code USING} 같은 명시 변환은 없다) — 변환할 수 없는 값이 있으면
   * DB 가 거부한다.
   */
  @Override
  public void alterColumnType(
      String tableName,
      String columnName,
      String dataType,
      Integer maxLength,
      String currentDataType) {
    long tenantId = TenantContext.require("Tibero alterColumnType");
    String schema = connections.schemaOf(tenantId);
    // 무시할 "객체 없음" 오류는 빌더가 문장마다 알려준다(BOOLEAN 이탈 시 CHECK 삭제 — 이미 없으면 무시).
    List<TiberoDdlSql.DdlStep> steps =
        TiberoDdlSql.alterColumnType(
            schema, tableName, columnName, dataType, maxLength, currentDataType);
    try {
      for (TiberoDdlSql.DdlStep step : steps) {
        executeIgnoring(tenantId, step.sql(), step.ignorableArray());
      }
    } finally {
      // 여러 문장 중 일부만 실행되고 실패해도 타입은 이미 바뀌었을 수 있으므로 항상 무효화한다.
      columnTypeCache.invalidate(tenantId, tableName);
    }
  }

  @Override
  public void setColumnNullable(String tableName, String columnName, boolean nullable) {
    long tenantId = TenantContext.require("Tibero setColumnNullable");
    String schema = connections.schemaOf(tenantId);
    execute(tenantId, TiberoDdlSql.setNullable(schema, tableName, columnName, nullable));
  }

  /**
   * 컬럼을 지운다. 되돌릴 수 없으므로 트랜잭션(동기화)이 있으면 {@code dropTable} 처럼 PG 커밋이 확정된 뒤({@code afterCommit})로 미룬다
   * — 바로 지우면 이어지는 메타 저장·PK 인덱스 재생성이 실패해 트랜잭션이 롤백될 때 메타데이터에는 컬럼이 남고 데이터만 사라진다. 커밋 뒤 삭제가 실패하면 테이블·컬럼
   * 이름만 ERROR 로 남긴다(예외는 던지지 않는다 — 이미 커밋됐다). 트랜잭션이 없으면 바로 지운다.
   *
   * <p>검색 타입 캐시는 삭제가 실제로 실행될 때 무효화한다(미룬 경우 커밋 뒤). 미루는 동안 물리 컬럼이 남아 있으므로 같은 트랜잭션의 PK 인덱스 재생성({@code
   * DatasetService.deleteColumn})은 남은 PK 컬럼만으로 새 인덱스를 만들 뿐 이 컬럼의 존재 여부와 무관하다.
   */
  @Override
  public void dropColumn(String tableName, String columnName) {
    long tenantId = TenantContext.require("Tibero dropColumn");
    String schema = connections.schemaOf(tenantId);
    String sql = TiberoDdlSql.dropColumn(schema, tableName, columnName);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      String objects = columnObjects(tableName, columnName);
      TiberoColumnTypeCache cache = columnTypeCache;
      TransactionSynchronizationManager.registerSynchronization(
          new Compensation(
              connections,
              tenantId,
              Compensation.Phase.AFTER_COMMIT,
              List.of(TiberoDdlSql.DdlStep.of(sql)),
              objects,
              "커밋 후 Tibero 컬럼 삭제 실패 — 물리 컬럼이 남음(수동 정리 필요): " + objects,
              () -> cache.invalidate(tenantId, tableName)));
    } else {
      execute(tenantId, sql);
      columnTypeCache.invalidate(tenantId, tableName);
    }
  }

  @Override
  public void cloneTable(
      String sourceTable,
      String targetTable,
      List<String> userColumns,
      List<DatasetColumnResponse> columnDefs) {
    long tenantId = TenantContext.require("Tibero cloneTable");
    String schema = connections.schemaOf(tenantId);
    List<String> statements =
        TiberoDdlSql.cloneTable(schema, sourceTable, targetTable, userColumns, columnDefs);
    runCreating(tenantId, schema, targetTable, statements);
    columnTypeCache.invalidate(tenantId, targetTable);
    registerRollbackDrop(tenantId, schema, targetTable);
  }

  /**
   * 컬럼별 통계(읽기 전용). 샘플링이 없어 <b>컬럼마다 전체 스캔이 두 번</b>(집계 1회 + 상위 값 1회) 일어나며 각각 30초 제한으로 실행한다 — PG 가 10만
   * 행 초과 시 샘플링하는 것과 다르다. 큰 테이블·넓은 컬럼 목록에서는 느릴 수 있다(샘플링은 SAMPLE 절 확인 후 별도 작업). PG 구현과 같은 방식으로 채운다 —
   * 샘플링은 하지 않으므로 {@code sampled=false}. GEOMETRY 는 Tibero 테이블에 있을 수 없지만 방어적으로 건너뛴다. BOOLEAN 은 0/1 로
   * 저장되므로 PG 와 같은 {@code false}/{@code true} 문자열로 되돌린다.
   */
  @Override
  public List<ColumnStatsResponse> getColumnStats(
      String tableName, List<DatasetColumnResponse> columns) {
    long tenantId = TenantContext.require("Tibero getColumnStats");
    String schema = connections.schemaOf(tenantId);
    return connections.withDml(
        tenantId,
        conn -> {
          List<ColumnStatsResponse> result = new ArrayList<>();
          for (DatasetColumnResponse col : columns) {
            String type = col.dataType();
            if ("GEOMETRY".equalsIgnoreCase(type)) {
              continue;
            }
            boolean numeric = NUMERIC_TYPES.contains(type);
            long total;
            long nullCount;
            long distinct;
            String min;
            String max;
            Double avg = null;
            try (Statement st = conn.createStatement()) {
              st.setQueryTimeout(30);
              try (ResultSet rs =
                  st.executeQuery(
                      TiberoDdlSql.columnStats(schema, tableName, col.columnName(), type))) {
                rs.next();
                total = rs.getLong("TOTAL");
                nullCount = rs.getLong("NULL_COUNT");
                distinct = rs.getLong("DISTINCT_COUNT");
                min = statText(rs.getString("MIN_VAL"), type);
                max = statText(rs.getString("MAX_VAL"), type);
                if (numeric) {
                  Object raw = rs.getObject("AVG_VAL");
                  if (raw instanceof Number n) {
                    avg = n instanceof BigDecimal bd ? bd.doubleValue() : n.doubleValue();
                  }
                }
              }
            }
            List<ColumnStatsResponse.ValueCount> top = new ArrayList<>();
            try (Statement st = conn.createStatement()) {
              st.setQueryTimeout(30);
              try (ResultSet rs =
                  st.executeQuery(
                      TiberoDdlSql.topValues(schema, tableName, col.columnName(), type))) {
                while (rs.next()) {
                  top.add(
                      new ColumnStatsResponse.ValueCount(
                          statText(rs.getString("VAL"), type), rs.getLong("CNT")));
                }
              }
            }
            double nullPercent = total > 0 ? (double) nullCount / total * 100.0 : 0.0;
            result.add(
                new ColumnStatsResponse(
                    col.columnName(),
                    type,
                    total,
                    nullCount,
                    nullPercent,
                    distinct,
                    min,
                    max,
                    avg,
                    top,
                    false));
          }
          return result;
        });
  }

  /**
   * 통계 문자열을 PG {@code ::text} 모양으로 맞춘다. BOOLEAN 은 0/1 을 false/true 로, TIMESTAMP 는 마이크로초 끝자리 0 과 빈
   * 소수부를 제거한다(PG 는 소수 끝자리 0 을 생략한다).
   */
  static String statText(String value, String type) {
    if (value == null || type == null) {
      return value;
    }
    if ("BOOLEAN".equalsIgnoreCase(type)) {
      return switch (value) {
        case "0" -> "false";
        case "1" -> "true";
        default -> value;
      };
    }
    if ("TIMESTAMP".equalsIgnoreCase(type) && value.indexOf('.') >= 0) {
      int end = value.length();
      while (end > 0 && value.charAt(end - 1) == '0') {
        end--;
      }
      if (end > 0 && value.charAt(end - 1) == '.') {
        end--;
      }
      return value.substring(0, end);
    }
    return value;
  }

  // ---------- 실행 헬퍼 ----------

  /** DDL 한 문장을 별도 자동 커밋 커넥션에서 실행한다. SQL 문장은 로그에 남기지 않는다. */
  private void execute(long tenantId, String sql) {
    executeIgnoring(tenantId, sql);
  }

  /** DDL 을 실행하되 지정한 "객체 없음" 오류는 무시한다({@link TiberoErrors#runIgnoring}). */
  private void executeIgnoring(long tenantId, String sql, TiberoDialect.NotFound... ignorable) {
    connections.withDdl(
        tenantId,
        conn -> {
          TiberoErrors.runIgnoring(conn, sql, ignorable);
          return null;
        });
  }

  /** 이 저장소가 지원하지 않는 기능(422). 미지원 거부를 한 진입점으로 모은다. */
  private static DataStoreFeatureUnsupportedException unsupported(String featureKorean) {
    return new DataStoreFeatureUnsupportedException(DataStoreBackend.TIBERO, featureKorean);
  }

  /** 테이블 → 시퀀스 순의 삭제 단계 중 지정한 것만 고른다. 이미 없는 객체는 무시하도록 표시한다. */
  private static List<TiberoDdlSql.DdlStep> dropSteps(
      String schema, String tableName, boolean table, boolean sequence) {
    List<TiberoDdlSql.DdlStep> steps = new ArrayList<>();
    if (table) {
      steps.add(
          TiberoDdlSql.DdlStep.ignoring(
              TiberoDdlSql.dropTableOnly(schema, tableName), TiberoDialect.NotFound.TABLE));
    }
    if (sequence) {
      steps.add(
          TiberoDdlSql.DdlStep.ignoring(
              TiberoDdlSql.dropSequence(schema, tableName), TiberoDialect.NotFound.SEQUENCE));
    }
    return steps;
  }

  /** 단계들을 한 DDL 커넥션에서 순서대로 실행한다. 무시 대상이 아닌 오류는 그대로 던진다. 단계가 없으면 연결하지 않는다. */
  private void runSteps(long tenantId, List<TiberoDdlSql.DdlStep> steps) {
    if (steps.isEmpty()) {
      return;
    }
    connections.withDdl(
        tenantId,
        conn -> {
          for (TiberoDdlSql.DdlStep step : steps) {
            TiberoErrors.runIgnoring(conn, step.sql(), step.ignorableArray());
          }
          return null;
        });
  }

  /**
   * 생성 문장들을 순서대로 실행한다. 중간에 실패하면 <b>이번 호출이 만든</b> 테이블·시퀀스만 지우고(이미 있던 것은 건드리지 않는다) 예외를 다시 던진다. {@code
   * CREATE SEQUENCE}/{@code CREATE TABLE} 이 이름 충돌(ORA-00955)로 실패하면 409 {@code
   * DATA_STORE_TABLE_EXISTS} 로 바꾼다 — 호출자가 보상을 등록하기 전에 예외가 나므로 남의 테이블에 대한 롤백 DROP 도 등록되지 않는다.
   */
  private void runCreating(
      long tenantId, String schema, String tableName, List<String> statements) {
    boolean sequenceCreated = false;
    boolean tableCreated = false;
    try {
      for (String sql : statements) {
        boolean createsSequence = sql.startsWith("CREATE SEQUENCE ");
        boolean createsTable = sql.startsWith("CREATE TABLE ");
        try {
          execute(tenantId, sql);
        } catch (RuntimeException e) {
          if ((createsSequence || createsTable)
              && TiberoErrors.hasAnyCode(e, TiberoDialect.NAME_ALREADY_USED)) {
            throw tableExists(tableName, e);
          }
          throw e;
        }
        sequenceCreated |= createsSequence;
        tableCreated |= createsTable;
      }
    } catch (RuntimeException e) {
      try {
        runSteps(tenantId, dropSteps(schema, tableName, tableCreated, sequenceCreated));
      } catch (RuntimeException cleanup) {
        log.error("Tibero 테이블 생성 실패 후 정리도 실패: {}", tableName, cleanup);
      }
      throw e;
    }
  }

  /** 같은 이름의 테이블·시퀀스가 저장소에 이미 있음(409). 원인은 보존하되 메시지에는 테이블 이름만 싣는다. */
  private static CodedApiException tableExists(String tableName, RuntimeException cause) {
    CodedApiException ex =
        new CodedApiException(
            HttpStatus.CONFLICT,
            "DATA_STORE_TABLE_EXISTS",
            "저장소에 같은 이름의 테이블이 이미 있습니다: " + tableName);
    ex.initCause(cause);
    return ex;
  }

  /** 트랜잭션이 있으면 롤백 시 테이블·시퀀스를 지우는 보상을 등록한다. */
  private void registerRollbackDrop(long tenantId, String schema, String tableName) {
    register(
        new Compensation(
            connections,
            tenantId,
            Compensation.Phase.ROLLBACK,
            dropSteps(schema, tableName, true, true),
            tableObjects(tableName),
            "롤백 보상(테이블 삭제) 실패 — 고아 테이블이 남았을 수 있음(수동 정리 필요): " + tableName,
            null));
  }

  /** 로그용 객체 설명 — 테이블과 전용 시퀀스. SQL 이 아니라 이름만 싣는다. */
  private static String tableObjects(String tableName) {
    return "테이블 " + tableName + "(+전용 시퀀스)";
  }

  /** 로그용 객체 설명 — 테이블의 컬럼. */
  private static String columnObjects(String tableName, String columnName) {
    return "테이블 " + tableName + " 의 컬럼 " + columnName;
  }

  /** 트랜잭션(동기화)이 있을 때만 보상을 등록한다. 없으면 되돌릴 트랜잭션도 없으므로 아무것도 하지 않는다. */
  private static void register(Compensation compensation) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(compensation);
    }
  }

  // ---------- 동기화 콜백 ----------

  /**
   * DDL 보상 하나(WD-74 R2) — 롤백 시 되돌리기(테이블·시퀀스·컬럼 DROP) 또는 커밋 뒤로 미룬 삭제({@code dropTable}).
   *
   * <p>{@code this}·요청 객체를 잡지 않도록 정적 중첩 클래스로 두고 필요한 값만 가진다(트랜잭션 수명 동안 메모리를 붙잡지 않도록).
   *
   * <p><b>롤백 단계</b>는 DDL 전에 {@link ExternalStoreConnections#rollbackBound} 로 같은 트랜잭션의 묶인 DML 커넥션을
   * 먼저 롤백·반납한다 — 롤백 시 동기화 콜백 순서는 등록 순서라, 보상이 먼저 등록됐으면 묶인 커넥션이 아직 방금 쓴 행의 잠금을 쥐고 있어 다른 세션의 DROP 이
   * ORA-00054 로 실패한다. 반납이 실패해도 보상 DDL 은 계속 시도한다.
   *
   * <p>단계마다 따로 잡아 로그를 남기고 다음 단계를 계속한다(최선 노력). 로그에는 SQL 이 아니라 테이블 이름이 든 라벨과 예외만 남긴다. 어떤 실패도 트랜잭션 완료
   * 콜백 밖으로 던지지 않는다.
   *
   * <p><b>결과 불명({@code STATUS_UNKNOWN}).</b> PG 트랜잭션이 커밋됐는지 롤백됐는지 모르면 아무것도 지우지 않고(잘못 지우면 되돌릴 수 없다)
   * 고아가 됐을 수 있는 객체 이름을 ERROR 로 남긴다 — 롤백 보상이면 이번 호출이 만든 객체가, 커밋 뒤 삭제면 지우려던 객체가 남았을 수 있다.
   */
  private static final class Compensation implements TransactionSynchronization {

    /** 보상이 실행되는 시점. */
    enum Phase {
      /** 호출자 트랜잭션이 롤백됐을 때(afterCompletion). */
      ROLLBACK,
      /** 호출자 트랜잭션이 커밋된 뒤(afterCommit). */
      AFTER_COMMIT
    }

    private final ExternalStoreConnections connections;
    private final long tenantId;
    private final Phase phase;
    private final List<TiberoDdlSql.DdlStep> steps;

    /** 고아가 될 수 있는 객체 설명(테이블·컬럼 이름). 결과 불명 로그에 쓴다. */
    private final String objects;

    private final String failureLabel;

    /** 단계를 실행한 뒤(성공·실패 무관) 부를 후처리 — 예: 검색 타입 캐시 무효화. 없으면 null. */
    private final Runnable afterRun;

    Compensation(
        ExternalStoreConnections connections,
        long tenantId,
        Phase phase,
        List<TiberoDdlSql.DdlStep> steps,
        String objects,
        String failureLabel,
        Runnable afterRun) {
      this.connections = connections;
      this.tenantId = tenantId;
      this.phase = phase;
      this.steps = List.copyOf(steps);
      this.objects = objects;
      this.failureLabel = failureLabel;
      this.afterRun = afterRun;
    }

    @Override
    public void afterCommit() {
      if (phase == Phase.AFTER_COMMIT) {
        runSteps();
      }
    }

    @Override
    public void afterCompletion(int status) {
      if (status == STATUS_UNKNOWN) {
        log.error(
            "Tibero DDL 보상: PG 트랜잭션 결과를 알 수 없어(STATUS_UNKNOWN) 실행하지 않음 — 고아가 남았을 수 있음"
                + "(수동 확인 필요, 단계={}): {}",
            phase,
            objects);
        return;
      }
      if (phase != Phase.ROLLBACK || status != STATUS_ROLLED_BACK) {
        return;
      }
      try {
        connections.rollbackBound(tenantId);
      } catch (RuntimeException e) {
        log.warn("테넌트 {} 롤백 보상 전 묶인 커넥션 정리 실패 — 보상 DDL 은 계속 시도한다", tenantId, e);
      }
      runSteps();
    }

    /** 한 DDL 커넥션에서 단계를 순서대로 실행하되, 단계별 실패는 로그만 남기고 다음 단계를 계속한다. */
    private void runSteps() {
      try {
        connections.withDdl(
            tenantId,
            conn -> {
              for (TiberoDdlSql.DdlStep step : steps) {
                try {
                  TiberoErrors.runIgnoring(conn, step.sql(), step.ignorableArray());
                } catch (SQLException e) {
                  log.error("{}", failureLabel, e);
                }
              }
              return null;
            });
      } catch (RuntimeException e) {
        // 커넥션을 얻지 못한 경우(접속 실패) — 어느 단계도 실행되지 않았다.
        log.error("{}", failureLabel, e);
      } finally {
        runAfterRun();
      }
    }

    /** 후처리를 부른다. 실패해도 완료 콜백 밖으로 던지지 않는다. */
    private void runAfterRun() {
      if (afterRun == null) {
        return;
      }
      try {
        afterRun.run();
      } catch (RuntimeException e) {
        log.warn("Tibero DDL 보상 후처리 실패(무시): {}", objects, e);
      }
    }
  }
}
