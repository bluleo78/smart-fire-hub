package com.smartfirehub.dataset.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataset.dto.BatchRowDataRequest;
import com.smartfirehub.dataset.dto.BatchRowDataResponse;
import com.smartfirehub.dataset.dto.ColumnStatsResponse;
import com.smartfirehub.dataset.dto.DataDeleteResponse;
import com.smartfirehub.dataset.dto.DataQueryResponse;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.DatasetResponse;
import com.smartfirehub.dataset.dto.QueryHistoryResponse;
import com.smartfirehub.dataset.dto.RowCountResponse;
import com.smartfirehub.dataset.dto.RowDataRequest;
import com.smartfirehub.dataset.dto.RowDataResponse;
import com.smartfirehub.dataset.dto.SpatialFilter;
import com.smartfirehub.dataset.dto.SqlQueryRequest;
import com.smartfirehub.dataset.dto.SqlQueryResponse;
import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.repository.QueryHistoryRepository;
import com.smartfirehub.global.dto.PageResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DatasetDataService {

  private final DatasetRepository datasetRepository;
  private final DatasetColumnRepository columnRepository;
  private final DataTableService dataTableService;
  private final DataTableRowService dataTableRowService;
  private final DataTableQueryService dataTableQueryService;
  private final QueryHistoryRepository queryHistoryRepository;
  private final ObjectMapper objectMapper;

  // DOCUMENT 데이터셋은 data.<table> 동적 테이블이 없고 데이터가 document_chunk 에 저장된다.
  private static final String DOCUMENT_TYPE = "DOCUMENT";
  // FILE(오브젝트) 데이터셋도 개별 파일이 MinIO 에 저장되어 data.<table> 동적 테이블이 없다.
  private static final String FILE_TYPE = "FILE";

  /** 물리 테이블이 없는(DOCUMENT/FILE) 데이터셋은 행 조회/편집을 거부한다(존재하지 않는 테이블 접근 시 500 방지). */
  private void rejectIfDocument(String storageType, String operation) {
    if (DOCUMENT_TYPE.equals(storageType) || FILE_TYPE.equals(storageType)) {
      throw new IllegalArgumentException(storageType + " 데이터셋은 " + operation + " 작업을 지원하지 않습니다");
    }
  }

  @Transactional(readOnly = true)
  public List<ColumnStatsResponse> getDatasetStats(Long datasetId) {
    DatasetResponse dataset =
        datasetRepository
            .findById(datasetId)
            .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));
    rejectIfDocument(dataset.storageType(), "통계 조회");

    long rowCount = dataTableRowService.countRows(dataset.tableName());
    if (rowCount == 0) {
      return List.of();
    }

    List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(datasetId);
    return dataTableService.getColumnStats(dataset.tableName(), columns);
  }

  @Transactional
  public DataDeleteResponse truncateDatasetData(Long datasetId) {
    DatasetResponse dataset =
        datasetRepository
            .findById(datasetId)
            .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));
    rejectIfDocument(dataset.storageType(), "데이터 삭제");
    long rowCount = dataTableRowService.countRows(dataset.tableName());
    dataTableRowService.truncateTable(dataset.tableName());
    return new DataDeleteResponse((int) rowCount);
  }

  @Transactional(readOnly = true)
  public RowCountResponse getRowCount(Long datasetId) {
    DatasetResponse dataset =
        datasetRepository
            .findById(datasetId)
            .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));
    rejectIfDocument(dataset.storageType(), "행 수 조회");
    long rowCount = dataTableRowService.countRows(dataset.tableName());
    return new RowCountResponse(rowCount);
  }

  @Transactional
  public BatchRowDataResponse replaceDatasetData(Long datasetId, BatchRowDataRequest request) {
    DatasetResponse dataset =
        datasetRepository
            .findById(datasetId)
            .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));
    rejectIfDocument(dataset.storageType(), "데이터 교체");

    List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(datasetId);

    List<Map<String, Object>> validatedRows = new ArrayList<>();
    for (int i = 0; i < request.rows().size(); i++) {
      try {
        validatedRows.add(validateAndConvertRowData(columns, request.rows().get(i)));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Row " + i + ": " + e.getMessage());
      }
    }

    List<String> columnNames = columns.stream().map(DatasetColumnResponse::columnName).toList();
    Map<String, String> columnTypes = buildColumnTypes(columns);

    // Atomic: truncate then insert in same transaction
    dataTableRowService.truncateTable(dataset.tableName());
    dataTableRowService.insertBatch(dataset.tableName(), columnNames, validatedRows, columnTypes);

    return new BatchRowDataResponse(validatedRows.size());
  }

  @Transactional
  public DataDeleteResponse deleteDataRows(Long datasetId, List<Long> rowIds) {
    DatasetResponse dataset =
        datasetRepository
            .findById(datasetId)
            .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));
    rejectIfDocument(dataset.storageType(), "행 삭제");
    int deletedCount = dataTableRowService.deleteRows(dataset.tableName(), rowIds);
    return new DataDeleteResponse(deletedCount);
  }

  @Transactional(readOnly = true)
  public DataQueryResponse getDatasetData(Long datasetId, String search, int page, int size) {
    return getDatasetData(datasetId, search, page, size, null, "ASC", true);
  }

  @Transactional(readOnly = true)
  public DataQueryResponse getDatasetData(
      Long datasetId,
      String search,
      int page,
      int size,
      String sortBy,
      String sortDir,
      boolean includeTotalCount) {
    return getDatasetData(datasetId, search, page, size, sortBy, sortDir, includeTotalCount, null);
  }

  @Transactional(readOnly = true)
  public DataQueryResponse getDatasetData(
      Long datasetId,
      String search,
      int page,
      int size,
      String sortBy,
      String sortDir,
      boolean includeTotalCount,
      SpatialFilter spatialFilter) {
    DatasetResponse dataset =
        datasetRepository
            .findById(datasetId)
            .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));
    rejectIfDocument(dataset.storageType(), "데이터 조회");

    List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(datasetId);
    List<String> columnNames = columns.stream().map(DatasetColumnResponse::columnName).toList();

    if (sortBy != null && !sortBy.isBlank()) {
      if (!columnNames.contains(sortBy)) {
        throw new IllegalArgumentException("정렬할 수 없는 컬럼입니다: " + sortBy);
      }
    } else {
      sortBy = null;
    }

    Map<String, String> columnTypes = buildColumnTypes(columns);

    List<Map<String, Object>> rows =
        dataTableRowService.queryData(
            dataset.tableName(),
            columns,
            columnTypes,
            search,
            sortBy,
            sortDir,
            page,
            size,
            spatialFilter);

    long totalElements = -1;
    int totalPages = -1;
    if (includeTotalCount) {
      totalElements =
          dataTableRowService.countRows(
              dataset.tableName(), columns, columnTypes, search, spatialFilter);
      totalPages = (int) Math.ceil((double) totalElements / size);
    }

    return new DataQueryResponse(columns, rows, page, size, totalElements, totalPages);
  }

  @Transactional
  public SqlQueryResponse executeQuery(Long datasetId, SqlQueryRequest request, Long userId) {
    datasetRepository
        .findById(datasetId)
        .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));

    SqlQueryResponse response =
        dataTableQueryService.executeQuery(request.sql(), request.maxRows());

    // Save to query history
    boolean success = response.error() == null;
    queryHistoryRepository.save(
        datasetId,
        userId,
        request.sql(),
        response.queryType(),
        response.affectedRows(),
        response.executionTimeMs(),
        success,
        response.error());

    return response;
  }

  @Transactional(readOnly = true)
  public PageResponse<QueryHistoryResponse> getQueryHistory(Long datasetId, int page, int size) {
    datasetRepository
        .findById(datasetId)
        .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));

    List<QueryHistoryResponse> content =
        queryHistoryRepository.findByDatasetId(datasetId, page, size);
    long totalElements = queryHistoryRepository.countByDatasetId(datasetId);
    int totalPages = (int) Math.ceil((double) totalElements / size);
    return new PageResponse<>(content, page, size, totalElements, totalPages);
  }

  @Transactional
  public RowDataResponse addRow(Long datasetId, RowDataRequest request) {
    DatasetResponse dataset =
        datasetRepository
            .findById(datasetId)
            .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));
    rejectIfDocument(dataset.storageType(), "행 추가");

    List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(datasetId);
    Map<String, Object> validatedData = validateAndConvertRowData(columns, request.data());

    List<String> columnNames = columns.stream().map(DatasetColumnResponse::columnName).toList();
    Map<String, String> columnTypes = buildColumnTypes(columns);
    Long newId =
        dataTableRowService.insertRow(dataset.tableName(), columnNames, validatedData, columnTypes);

    // Return the newly inserted row
    Map<String, Object> rowData =
        dataTableRowService.getRow(dataset.tableName(), columnNames, newId, columnTypes);
    Map<String, Object> data = new LinkedHashMap<>();
    LocalDateTime createdAt = null;
    for (var entry : rowData.entrySet()) {
      if ("id".equals(entry.getKey()) || "import_id".equals(entry.getKey())) {
        continue;
      }
      if ("created_at".equals(entry.getKey())) {
        if (entry.getValue() instanceof LocalDateTime ldt) {
          createdAt = ldt;
        }
        continue;
      }
      data.put(entry.getKey(), entry.getValue());
    }

    return new RowDataResponse(newId, data, createdAt);
  }

  @Transactional
  public BatchRowDataResponse addRowsBatch(Long datasetId, BatchRowDataRequest request) {
    DatasetResponse dataset =
        datasetRepository
            .findById(datasetId)
            .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));
    rejectIfDocument(dataset.storageType(), "행 일괄 추가");

    List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(datasetId);

    List<Map<String, Object>> validatedRows = new ArrayList<>();
    for (int i = 0; i < request.rows().size(); i++) {
      try {
        validatedRows.add(validateAndConvertRowData(columns, request.rows().get(i)));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Row " + i + ": " + e.getMessage());
      }
    }

    List<String> columnNames = columns.stream().map(DatasetColumnResponse::columnName).toList();
    Map<String, String> columnTypes = buildColumnTypes(columns);

    dataTableRowService.insertBatch(dataset.tableName(), columnNames, validatedRows, columnTypes);

    return new BatchRowDataResponse(validatedRows.size());
  }

  @Transactional
  public void updateRow(Long datasetId, Long rowId, RowDataRequest request) {
    DatasetResponse dataset =
        datasetRepository
            .findById(datasetId)
            .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));
    rejectIfDocument(dataset.storageType(), "행 수정");

    List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(datasetId);
    List<String> columnNames = columns.stream().map(DatasetColumnResponse::columnName).toList();
    Map<String, String> columnTypes = buildColumnTypes(columns);

    // (#672) 부분 업데이트(merge-then-validate): 사용자 정의 PK 컬럼처럼 프론트 편집 폼에서
    // 읽기 전용으로 제외되어 요청 바디에 아예 포함되지 않는 컬럼이 있을 수 있다.
    // 요청에 없는 컬럼은 기존 DB 값을 그대로 유지하도록 먼저 조회해 병합한 뒤,
    // 요청에 "명시적으로 포함된" 컬럼(값이 null이어도 포함)만 재검증한다.
    Map<String, Object> existingRow =
        dataTableRowService.getRow(dataset.tableName(), columnNames, rowId, columnTypes);

    Map<String, Object> mergedData = new HashMap<>();
    for (String columnName : columnNames) {
      if (request.data().containsKey(columnName)) {
        mergedData.put(columnName, request.data().get(columnName));
      } else {
        mergedData.put(columnName, existingRow.get(columnName));
      }
    }

    Map<String, Object> validatedData =
        validateAndConvertRowData(columns, mergedData, request.data().keySet());
    dataTableRowService.updateRow(
        dataset.tableName(), rowId, columnNames, validatedData, columnTypes);
  }

  @Transactional(readOnly = true)
  public RowDataResponse getRow(Long datasetId, Long rowId) {
    DatasetResponse dataset =
        datasetRepository
            .findById(datasetId)
            .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));
    rejectIfDocument(dataset.storageType(), "행 조회");

    List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(datasetId);
    List<String> columnNames = columns.stream().map(DatasetColumnResponse::columnName).toList();
    Map<String, String> columnTypes = buildColumnTypes(columns);

    Map<String, Object> rowData =
        dataTableRowService.getRow(dataset.tableName(), columnNames, rowId, columnTypes);
    Map<String, Object> data = new LinkedHashMap<>();
    Long id = null;
    LocalDateTime createdAt = null;
    for (var entry : rowData.entrySet()) {
      if ("id".equals(entry.getKey())) {
        if (entry.getValue() instanceof Number n) {
          id = n.longValue();
        }
        continue;
      }
      if ("import_id".equals(entry.getKey())) {
        continue;
      }
      if ("created_at".equals(entry.getKey())) {
        if (entry.getValue() instanceof LocalDateTime ldt) {
          createdAt = ldt;
        }
        continue;
      }
      data.put(entry.getKey(), entry.getValue());
    }

    return new RowDataResponse(id, data, createdAt);
  }

  @Transactional
  public void propagateDescriptions(Long datasetId) {
    datasetRepository
        .findById(datasetId)
        .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));

    List<DatasetColumnResponse> targetColumns = columnRepository.findByDatasetId(datasetId);

    // Collect column names that have empty descriptions
    List<String> emptyDescCols =
        targetColumns.stream()
            .filter(c -> c.description() == null || c.description().isBlank())
            .map(DatasetColumnResponse::columnName)
            .toList();

    if (emptyDescCols.isEmpty()) {
      return;
    }

    // Find matching column names from ALL other datasets that have descriptions
    for (DatasetColumnResponse col : targetColumns) {
      if (col.description() != null && !col.description().isBlank()) {
        continue;
      }
      // Look up source descriptions from other datasets
      String sourceDesc =
          columnRepository.findFirstDescriptionByColumnName(col.columnName(), datasetId);
      if (sourceDesc != null && !sourceDesc.isBlank()) {
        columnRepository.updateDescription(col.id(), sourceDesc);
      }
    }
  }

  private Map<String, Object> validateAndConvertRowData(
      List<DatasetColumnResponse> columns, Map<String, Object> data) {
    // 전체 컬럼을 항상 검증 대상으로 삼는 기존 동작 유지 (행 추가 등 전체 삽입 경로용)
    Set<String> allColumnNames =
        columns.stream().map(DatasetColumnResponse::columnName).collect(Collectors.toSet());
    return validateAndConvertRowData(columns, data, allColumnNames);
  }

  /**
   * 행 데이터를 검증·변환한다.
   *
   * <p>{@code columnsToValidate}에 포함된 컬럼만 not-null 검사 및 타입 변환을 수행하고, 그 외
   * 컬럼(예: 부분 업데이트에서 요청에 없어 기존 DB 값으로 병합된 컬럼)은 이미 유효한 값으로
   * 간주해 그대로 통과시킨다 (#672). 병합된 기존 값은 DB에서 그대로 조회한 원시 타입이라
   * {@link #convertValue}가 기대하는 입력 포맷(예: DATE 컬럼의 문자열)과 다를 수 있어
   * 재변환하면 오히려 실패하므로, 재검증 없이 그대로 사용해야 한다.
   */
  private Map<String, Object> validateAndConvertRowData(
      List<DatasetColumnResponse> columns, Map<String, Object> data, Set<String> columnsToValidate) {
    Map<String, Object> result = new HashMap<>();
    List<String> errors = new ArrayList<>();

    for (DatasetColumnResponse col : columns) {
      Object value = data.get(col.columnName());

      if (!columnsToValidate.contains(col.columnName())) {
        // 요청에 없어 기존 DB 값을 그대로 유지하는 컬럼 — 재검증하지 않고 통과
        result.put(col.columnName(), value);
        continue;
      }

      if (value == null) {
        if (!col.isNullable()) {
          errors.add("Column '" + col.columnName() + "' cannot be null");
        }
        result.put(col.columnName(), null);
        continue;
      }

      try {
        Object converted = convertValue(col, value);
        result.put(col.columnName(), converted);
      } catch (Exception e) {
        errors.add("Column '" + col.columnName() + "': " + e.getMessage());
      }
    }

    if (!errors.isEmpty()) {
      throw new IllegalArgumentException("Validation errors: " + String.join("; ", errors));
    }

    return result;
  }

  private Object convertValue(DatasetColumnResponse col, Object value) {
    return switch (col.dataType()) {
      case "TEXT", "VARCHAR" -> {
        if (!(value instanceof String s)) {
          throw new IllegalArgumentException("Expected string value");
        }
        if ("VARCHAR".equals(col.dataType())
            && col.maxLength() != null
            && s.length() > col.maxLength()) {
          throw new IllegalArgumentException(
              "Value exceeds max length " + col.maxLength() + " (actual: " + s.length() + ")");
        }
        yield s;
      }
      case "INTEGER" -> {
        if (value instanceof Number n) {
          yield n.longValue();
        }
        throw new IllegalArgumentException("Expected numeric value");
      }
      case "DECIMAL" -> {
        if (value instanceof Number n) {
          yield new BigDecimal(n.toString());
        }
        throw new IllegalArgumentException("Expected numeric value");
      }
      case "BOOLEAN" -> {
        if (value instanceof Boolean b) {
          yield b;
        }
        throw new IllegalArgumentException("Expected boolean value");
      }
      case "DATE" -> {
        if (value instanceof String s) {
          try {
            yield LocalDate.parse(s);
          } catch (Exception e) {
            throw new IllegalArgumentException("Expected date format yyyy-MM-dd");
          }
        }
        throw new IllegalArgumentException("Expected date string in yyyy-MM-dd format");
      }
      case "TIMESTAMP" -> {
        if (value instanceof String s) {
          try {
            yield LocalDateTime.parse(s);
          } catch (Exception e) {
            throw new IllegalArgumentException("Expected timestamp format yyyy-MM-ddTHH:mm:ss");
          }
        }
        throw new IllegalArgumentException("Expected timestamp string");
      }
      case "GEOMETRY" -> {
        if (!(value instanceof String s)) {
          throw new IllegalArgumentException("Expected GeoJSON string");
        }
        validateGeoJson(s);
        yield s;
      }
      default -> throw new IllegalArgumentException("Unknown data type: " + col.dataType());
    };
  }

  /** Builds a column name → data type map from column metadata. */
  private static Map<String, String> buildColumnTypes(List<DatasetColumnResponse> columns) {
    Map<String, String> types = new HashMap<>();
    for (DatasetColumnResponse col : columns) {
      types.put(col.columnName(), col.dataType());
    }
    return types;
  }

  private static final Set<String> VALID_GEOJSON_TYPES =
      Set.of(
          "Point",
          "LineString",
          "Polygon",
          "MultiPoint",
          "MultiLineString",
          "MultiPolygon",
          "GeometryCollection");

  private void validateGeoJson(String value) {
    if (value == null || value.isBlank()) return;
    try {
      JsonNode node = objectMapper.readTree(value);
      if (!node.has("type")) {
        throw new IllegalArgumentException("Invalid GeoJSON: 'type' field is required");
      }
      String type = node.get("type").asText();
      if (!VALID_GEOJSON_TYPES.contains(type)) {
        throw new IllegalArgumentException("Unsupported GeoJSON type: " + type);
      }
      if (!"GeometryCollection".equals(type) && !node.has("coordinates")) {
        throw new IllegalArgumentException("Invalid GeoJSON: 'coordinates' field is required");
      }
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid GeoJSON: " + e.getMessage());
    }
  }
}
