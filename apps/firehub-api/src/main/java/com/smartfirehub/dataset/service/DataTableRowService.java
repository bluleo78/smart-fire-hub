package com.smartfirehub.dataset.service;

import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.SpatialFilter;
import com.smartfirehub.dataset.storage.DataRowStore;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 데이터셋 행 조회·적재 파사드 — 테넌트 저장소 백엔드에 위임(WD-72).
 *
 * <p>호출부는 이 클래스만 알면 된다. 실제 SQL 은 {@link DataStoreResolver} 가 현재 테넌트 기준으로 고른 {@link DataRowStore}
 * 구현이 실행하며, 호출자의 트랜잭션에 그대로 참여한다. 기본 인자만 채워 다른 오버로드로 넘기는 편의 오버로드와 순수 정적 헬퍼({@link
 * #resolveGeometryColumn})는 여기에 남긴다.
 */
@Service
@RequiredArgsConstructor
public class DataTableRowService {

  private final DataStoreResolver resolver;

  /**
   * Result of an upsert batch operation. inserted: number of rows newly inserted (xmax = 0 in
   * PostgreSQL means the row was just inserted). updated: number of rows that already existed and
   * were updated.
   */
  public record UpsertResult(int inserted, int updated) {}

  /**
   * GEOMETRY 컬럼 이름 해결. geometryColumn이 null이면 첫 번째 GEOMETRY 컬럼 자동 선택.
   *
   * @throws IllegalArgumentException GEOMETRY 컬럼이 없거나 지정 컬럼이 GEOMETRY가 아닌 경우
   */
  public static String resolveGeometryColumn(
      List<DatasetColumnResponse> columns, Map<String, String> columnTypes, String geometryColumn) {
    if (geometryColumn != null) {
      if (!PostgresDataRowStore.isGeometry(geometryColumn, columnTypes)) {
        throw new IllegalArgumentException("컬럼 '" + geometryColumn + "'은 GEOMETRY 타입이 아닙니다.");
      }
      return geometryColumn;
    }
    // auto-select first GEOMETRY column
    for (DatasetColumnResponse col : columns) {
      if (PostgresDataRowStore.isGeometry(col.columnName(), columnTypes)) {
        return col.columnName();
      }
    }
    throw new IllegalArgumentException("데이터셋에 GEOMETRY 컬럼이 없습니다.");
  }

  public List<Map<String, Object>> queryData(
      String tableName, List<String> columns, String search, int page, int size) {
    return queryData(tableName, columns, search, page, size, null, "ASC", null);
  }

  public List<Map<String, Object>> queryData(
      String tableName,
      List<String> columns,
      String search,
      int page,
      int size,
      String sortBy,
      String sortDir) {
    return queryData(tableName, columns, search, page, size, sortBy, sortDir, null);
  }

  public List<Map<String, Object>> queryData(
      String tableName,
      List<String> columns,
      String search,
      int page,
      int size,
      String sortBy,
      String sortDir,
      Map<String, String> columnTypes) {
    return resolver
        .rowStore()
        .queryData(tableName, columns, search, page, size, sortBy, sortDir, columnTypes);
  }

  /** 기존 시그니처 유지 (하위 호환) */
  public List<Map<String, Object>> queryData(
      String tableName,
      List<DatasetColumnResponse> columns,
      Map<String, String> columnTypes,
      String search,
      String sortBy,
      String sortDir,
      int page,
      int size) {
    return queryData(tableName, columns, columnTypes, search, sortBy, sortDir, page, size, null);
  }

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
    return resolver
        .rowStore()
        .queryData(
            tableName, columns, columnTypes, search, sortBy, sortDir, page, size, spatialFilter);
  }

  /** 기존 시그니처 유지 (하위 호환) */
  public long countRows(
      String tableName,
      List<DatasetColumnResponse> columns,
      Map<String, String> columnTypes,
      String search) {
    return countRows(tableName, columns, columnTypes, search, null);
  }

  public long countRows(
      String tableName,
      List<DatasetColumnResponse> columns,
      Map<String, String> columnTypes,
      String search,
      SpatialFilter spatialFilter) {
    return resolver.rowStore().countRows(tableName, columns, columnTypes, search, spatialFilter);
  }

  public long countRows(String tableName) {
    return countRows(tableName, List.of(), null);
  }

  public long countRows(String tableName, List<String> columns, String search) {
    return countRows(tableName, columns, search, null);
  }

  public long countRows(
      String tableName, List<String> columns, String search, Map<String, String> columnTypes) {
    return resolver.rowStore().countRows(tableName, columns, search, columnTypes);
  }

  public boolean checkDataUniqueness(String tableName, List<String> pkColumnNames) {
    return resolver.rowStore().checkDataUniqueness(tableName, pkColumnNames);
  }

  public List<Map<String, Object>> findDuplicateRows(
      String tableName, List<String> pkColumnNames, int limit) {
    return resolver.rowStore().findDuplicateRows(tableName, pkColumnNames, limit);
  }

  public void insertBatch(String tableName, List<String> columns, List<Map<String, Object>> rows) {
    insertBatch(tableName, columns, rows, (Map<String, String>) null);
  }

  public void insertBatch(
      String tableName,
      List<String> columns,
      List<Map<String, Object>> rows,
      Map<String, String> columnTypes) {
    resolver.rowStore().insertBatch(tableName, columns, rows, columnTypes);
  }

  public void insertBatchWithProgress(
      String tableName,
      List<String> columns,
      List<Map<String, Object>> rows,
      BiConsumer<Integer, Integer> progressCallback) {
    insertBatchWithProgress(tableName, columns, rows, progressCallback, null);
  }

  public void insertBatchWithProgress(
      String tableName,
      List<String> columns,
      List<Map<String, Object>> rows,
      BiConsumer<Integer, Integer> progressCallback,
      Map<String, String> columnTypes) {
    resolver
        .rowStore()
        .insertBatchWithProgress(tableName, columns, rows, progressCallback, columnTypes);
  }

  public void insertBatch(
      String tableName, List<String> columns, List<Map<String, Object>> rows, Long importId) {
    insertBatch(tableName, columns, rows, importId, null);
  }

  public void insertBatch(
      String tableName,
      List<String> columns,
      List<Map<String, Object>> rows,
      Long importId,
      Map<String, String> columnTypes) {
    resolver.rowStore().insertBatch(tableName, columns, rows, importId, columnTypes);
  }

  public void insertBatchWithProgress(
      String tableName,
      List<String> columns,
      List<Map<String, Object>> rows,
      Long importId,
      BiConsumer<Integer, Integer> progressCallback) {
    insertBatchWithProgress(tableName, columns, rows, importId, progressCallback, null);
  }

  public void insertBatchWithProgress(
      String tableName,
      List<String> columns,
      List<Map<String, Object>> rows,
      Long importId,
      BiConsumer<Integer, Integer> progressCallback,
      Map<String, String> columnTypes) {
    resolver
        .rowStore()
        .insertBatchWithProgress(tableName, columns, rows, importId, progressCallback, columnTypes);
  }

  /**
   * Upsert a batch of rows using PostgreSQL's ON CONFLICT DO UPDATE.
   *
   * <p>The conflict target is the unique index on pkColumns (ux_{tableName}_pk). created_at is
   * intentionally excluded from DO UPDATE SET to preserve the original creation timestamp on
   * subsequent updates.
   *
   * <p>PostgreSQL xmax trick: when a row is freshly inserted, its xmax system column is 0. When it
   * is updated (DELETE + re-insert under the hood), xmax holds the transaction ID of the deleting
   * transaction (non-zero). By returning (xmax = 0) AS was_insert we can distinguish inserts from
   * updates without a separate SELECT.
   */
  public UpsertResult upsertBatch(
      String tableName,
      List<String> columns,
      List<String> pkColumns,
      List<Map<String, Object>> rows,
      Long importId) {
    return upsertBatch(tableName, columns, pkColumns, rows, importId, null);
  }

  public UpsertResult upsertBatch(
      String tableName,
      List<String> columns,
      List<String> pkColumns,
      List<Map<String, Object>> rows,
      Long importId,
      Map<String, String> columnTypes) {
    return resolver
        .rowStore()
        .upsertBatch(tableName, columns, pkColumns, rows, importId, columnTypes);
  }

  public UpsertResult upsertBatchWithProgress(
      String tableName,
      List<String> columns,
      List<String> pkColumns,
      List<Map<String, Object>> rows,
      Long importId,
      BiConsumer<Integer, Integer> progressCallback) {
    return upsertBatchWithProgress(
        tableName, columns, pkColumns, rows, importId, progressCallback, null);
  }

  public UpsertResult upsertBatchWithProgress(
      String tableName,
      List<String> columns,
      List<String> pkColumns,
      List<Map<String, Object>> rows,
      Long importId,
      BiConsumer<Integer, Integer> progressCallback,
      Map<String, String> columnTypes) {
    return resolver
        .rowStore()
        .upsertBatchWithProgress(
            tableName, columns, pkColumns, rows, importId, progressCallback, columnTypes);
  }

  public int deleteRows(String tableName, List<Long> rowIds) {
    return resolver.rowStore().deleteRows(tableName, rowIds);
  }

  public void truncateTable(String tableName) {
    resolver.rowStore().truncateTable(tableName);
  }

  public Long insertRow(String tableName, List<String> columns, Map<String, Object> row) {
    return insertRow(tableName, columns, row, null);
  }

  public Long insertRow(
      String tableName,
      List<String> columns,
      Map<String, Object> row,
      Map<String, String> columnTypes) {
    return resolver.rowStore().insertRow(tableName, columns, row, columnTypes);
  }

  public void updateRow(
      String tableName, long rowId, List<String> columns, Map<String, Object> row) {
    updateRow(tableName, rowId, columns, row, null);
  }

  public void updateRow(
      String tableName,
      long rowId,
      List<String> columns,
      Map<String, Object> row,
      Map<String, String> columnTypes) {
    resolver.rowStore().updateRow(tableName, rowId, columns, row, columnTypes);
  }

  public Map<String, Object> getRow(String tableName, List<String> columns, long rowId) {
    return getRow(tableName, columns, rowId, null);
  }

  public Map<String, Object> getRow(
      String tableName, List<String> columns, long rowId, Map<String, String> columnTypes) {
    return resolver.rowStore().getRow(tableName, columns, rowId, columnTypes);
  }

  public String createStagingTable(String targetTableName, List<String> columns) {
    return resolver.rowStore().createStagingTable(targetTableName, columns);
  }

  public void insertStagingBatchWithProgress(
      String stagingTable,
      List<String> columns,
      List<Map<String, Object>> rows,
      BiConsumer<Integer, Integer> progressCallback) {
    resolver
        .rowStore()
        .insertStagingBatchWithProgress(stagingTable, columns, rows, progressCallback);
  }

  public UpsertResult promoteStagingToUpsert(
      String stagingTable,
      String targetTableName,
      List<String> columns,
      List<String> pkColumns,
      Long importId) {
    return resolver
        .rowStore()
        .promoteStagingToUpsert(stagingTable, targetTableName, columns, pkColumns, importId);
  }

  public void promoteStagingToReplace(
      String stagingTable, String targetTableName, List<String> columns, List<String> pkColumns) {
    resolver.rowStore().promoteStagingToReplace(stagingTable, targetTableName, columns, pkColumns);
  }

  public void dropStagingTable(String stagingTable) {
    resolver.rowStore().dropStagingTable(stagingTable);
  }
}
