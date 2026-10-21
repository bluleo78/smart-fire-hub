package com.smartfirehub.dataset.service;

import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.SpatialFilter;
import com.smartfirehub.dataset.storage.DataColumnTypes;
import com.smartfirehub.dataset.storage.DataRowStore;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import com.smartfirehub.dataset.storage.DataTableNames;
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
    return DataColumnTypes.resolveGeometryColumn(columns, columnTypes, geometryColumn);
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
    // 테넌트 해석 전에 이름부터 검증한다(기존 동작 유지).
    DataTableNames.validate(tableName);
    for (String col : columns) {
      DataTableNames.validate(col);
    }
    if (sortBy != null) {
      DataTableNames.validate(sortBy);
    }
    return resolver
        .rowStore()
        .queryData(tableName, columns, search, page, size, sortBy, sortDir, columnTypes);
  }

  /**
   * 출력 컬럼과 검색 대상 컬럼을 따로 받아 조회한다 — 일부 컬럼만 내보내도 검색은 데이터 탭처럼 전체 컬럼에 건다(WD-158).
   *
   * @param columns SELECT 할 컬럼(출력 칸)
   * @param searchColumns 검색어 ILIKE 를 걸 컬럼(GEOMETRY 는 저장소가 뺀다)
   */
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
    // 테넌트 해석 전에 이름부터 검증한다(위 오버로드와 같은 순서).
    DataTableNames.validate(tableName);
    for (String col : columns) {
      DataTableNames.validate(col);
    }
    for (String col : searchColumns) {
      DataTableNames.validate(col);
    }
    if (sortBy != null) {
      DataTableNames.validate(sortBy);
    }
    return resolver
        .rowStore()
        .queryData(
            tableName, columns, searchColumns, search, page, size, sortBy, sortDir, columnTypes);
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
    // 테넌트 해석 전에 이름부터 검증한다(기존 동작 유지).
    DataTableNames.validate(tableName);
    if (sortBy != null) {
      DataTableNames.validate(sortBy);
    }
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
    // 테넌트 해석 전에 이름부터 검증한다(기존 동작 유지).
    DataTableNames.validate(tableName);
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
    // 테넌트 해석 전에 이름부터 검증한다(기존 동작 유지).
    DataTableNames.validate(tableName);
    for (String col : columns) {
      DataTableNames.validate(col);
    }
    return resolver.rowStore().countRows(tableName, columns, search, columnTypes);
  }

  public boolean checkDataUniqueness(String tableName, List<String> pkColumnNames) {
    // 테넌트 해석 전에 이름부터 검증한다(기존 동작 유지).
    DataTableNames.validate(tableName);
    for (String pkCol : pkColumnNames) {
      DataTableNames.validate(pkCol);
    }
    return resolver.rowStore().checkDataUniqueness(tableName, pkColumnNames);
  }

  public List<Map<String, Object>> findDuplicateRows(
      String tableName, List<String> pkColumnNames, int limit) {
    // 테넌트 해석 전에 이름부터 검증한다(기존 동작 유지).
    DataTableNames.validate(tableName);
    for (String pkCol : pkColumnNames) {
      DataTableNames.validate(pkCol);
    }
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
    // 테넌트 해석 전에 이름부터 검증한다(기존 동작 유지).
    DataTableNames.validate(tableName);
    for (String col : columns) {
      DataTableNames.validate(col);
    }
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
    // 테넌트 해석 전에 이름부터 검증한다(기존 동작 유지).
    DataTableNames.validate(tableName);
    for (String col : columns) {
      DataTableNames.validate(col);
    }
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
    // 테넌트 해석 전에 이름부터 검증한다(기존 동작 유지).
    DataTableNames.validate(tableName);
    for (String col : columns) {
      DataTableNames.validate(col);
    }
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
    // 테넌트 해석 전에 이름부터 검증한다(기존 동작 유지).
    DataTableNames.validate(tableName);
    for (String col : columns) {
      DataTableNames.validate(col);
    }
    resolver
        .rowStore()
        .insertBatchWithProgress(tableName, columns, rows, importId, progressCallback, columnTypes);
  }

  /**
   * Upsert a batch of rows using PostgreSQL's ON CONFLICT DO UPDATE.
   *
   * <p>The conflict target is the unique index on pkColumns (기본 이름 ux_{tableName}_pk — REPLACE 맞바꿈
   * 뒤에는 다른 이름일 수 있으나 ON CONFLICT 는 컬럼 목록으로 추론하므로 무관하다). created_at is intentionally excluded from
   * DO UPDATE SET to preserve the original creation timestamp on subsequent updates.
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
    // 테넌트 해석 전에 이름부터 검증한다(기존 동작 유지).
    DataTableNames.validate(tableName);
    for (String col : columns) {
      DataTableNames.validate(col);
    }
    for (String pkCol : pkColumns) {
      DataTableNames.validate(pkCol);
    }
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
    // 테넌트 해석 전에 이름부터 검증한다(기존 동작 유지).
    DataTableNames.validate(tableName);
    for (String col : columns) {
      DataTableNames.validate(col);
    }
    for (String pkCol : pkColumns) {
      DataTableNames.validate(pkCol);
    }
    return resolver
        .rowStore()
        .upsertBatchWithProgress(
            tableName, columns, pkColumns, rows, importId, progressCallback, columnTypes);
  }

  public int deleteRows(String tableName, List<Long> rowIds) {
    // 테넌트 해석 전에 이름부터 검증한다(기존 동작 유지).
    DataTableNames.validate(tableName);
    return resolver.rowStore().deleteRows(tableName, rowIds);
  }

  public void truncateTable(String tableName) {
    // 테넌트 해석 전에 이름부터 검증한다(기존 동작 유지).
    DataTableNames.validate(tableName);
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
    // 테넌트 해석 전에 이름부터 검증한다(기존 동작 유지).
    DataTableNames.validate(tableName);
    for (String col : columns) {
      DataTableNames.validate(col);
    }
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
    // 테넌트 해석 전에 이름부터 검증한다(기존 동작 유지).
    DataTableNames.validate(tableName);
    for (String col : columns) {
      DataTableNames.validate(col);
    }
    resolver.rowStore().updateRow(tableName, rowId, columns, row, columnTypes);
  }

  public Map<String, Object> getRow(String tableName, List<String> columns, long rowId) {
    return getRow(tableName, columns, rowId, null);
  }

  public Map<String, Object> getRow(
      String tableName, List<String> columns, long rowId, Map<String, String> columnTypes) {
    // 테넌트 해석 전에 이름부터 검증한다(기존 동작 유지).
    DataTableNames.validate(tableName);
    for (String col : columns) {
      DataTableNames.validate(col);
    }
    return resolver.rowStore().getRow(tableName, columns, rowId, columnTypes);
  }

  public String createStagingTable(String targetTableName, List<String> columns) {
    // 테넌트 해석 전에 이름부터 검증한다(기존 동작 유지).
    DataTableNames.validate(targetTableName);
    for (String col : columns) {
      DataTableNames.validate(col);
    }
    return resolver.rowStore().createStagingTable(targetTableName, columns);
  }

  public void insertStagingBatchWithProgress(
      String stagingTable,
      List<String> columns,
      List<Map<String, Object>> rows,
      BiConsumer<Integer, Integer> progressCallback) {
    // 테넌트 해석 전에 이름부터 검증한다(기존 동작 유지).
    DataTableNames.validate(stagingTable);
    for (String col : columns) {
      DataTableNames.validate(col);
    }
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
    // 테넌트 해석 전에 이름부터 검증한다(기존 동작 유지).
    DataTableNames.validate(stagingTable);
    DataTableNames.validate(targetTableName);
    for (String col : columns) {
      DataTableNames.validate(col);
    }
    for (String pkCol : pkColumns) {
      DataTableNames.validate(pkCol);
    }
    return resolver
        .rowStore()
        .promoteStagingToUpsert(stagingTable, targetTableName, columns, pkColumns, importId);
  }

  public void promoteStagingToReplace(
      String stagingTable, String targetTableName, List<String> columns, List<String> pkColumns) {
    // 테넌트 해석 전에 이름부터 검증한다(기존 동작 유지).
    DataTableNames.validate(stagingTable);
    DataTableNames.validate(targetTableName);
    for (String col : columns) {
      DataTableNames.validate(col);
    }
    for (String pkCol : pkColumns) {
      DataTableNames.validate(pkCol);
    }
    resolver.rowStore().promoteStagingToReplace(stagingTable, targetTableName, columns, pkColumns);
  }

  public void dropStagingTable(String stagingTable) {
    // 테넌트 해석 전에 이름부터 검증한다(기존 동작 유지).
    DataTableNames.validate(stagingTable);
    resolver.rowStore().dropStagingTable(stagingTable);
  }
}
