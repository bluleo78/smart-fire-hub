package com.smartfirehub.dataset.service;

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
import com.smartfirehub.dataset.dto.SqlQueryRequest;
import com.smartfirehub.dataset.dto.SqlQueryResponse;
import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.repository.QueryHistoryRepository;
import com.smartfirehub.global.dto.PageResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatasetDataService {

  private final DatasetRepository datasetRepository;
  private final DatasetColumnRepository columnRepository;
  private final DataTableService dataTableService;
  private final DataTableRowService dataTableRowService;
  private final DataTableQueryService dataTableQueryService;
  private final QueryHistoryRepository queryHistoryRepository;
  private final ObjectMapper objectMapper;

  public DatasetDataService(
      DatasetRepository datasetRepository,
      DatasetColumnRepository columnRepository,
      DataTableService dataTableService,
      DataTableRowService dataTableRowService,
      DataTableQueryService dataTableQueryService,
      QueryHistoryRepository queryHistoryRepository,
      ObjectMapper objectMapper) {
    this.datasetRepository = datasetRepository;
    this.columnRepository = columnRepository;
    this.dataTableService = dataTableService;
    this.dataTableRowService = dataTableRowService;
    this.dataTableQueryService = dataTableQueryService;
    this.queryHistoryRepository = queryHistoryRepository;
    this.objectMapper = objectMapper;
  }

  @Transactional(readOnly = true)
  public List<ColumnStatsResponse> getDatasetStats(Long datasetId) {
    DatasetResponse dataset =
        datasetRepository
            .findById(datasetId)
            .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));

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
    long rowCount = dataTableRowService.countRows(dataset.tableName());
    return new RowCountResponse(rowCount);
  }

  @Transactional
  public BatchRowDataResponse replaceDatasetData(Long datasetId, BatchRowDataRequest request) {
    DatasetResponse dataset =
        datasetRepository
            .findById(datasetId)
            .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));

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
    DatasetResponse dataset =
        datasetRepository
            .findById(datasetId)
            .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));

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
            dataset.tableName(), columnNames, search, page, size, sortBy, sortDir, columnTypes);

    long totalElements = -1;
    int totalPages = -1;
    if (includeTotalCount) {
      totalElements =
          dataTableRowService.countRows(dataset.tableName(), columnNames, search, columnTypes);
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

    List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(datasetId);
    Map<String, Object> validatedData = validateAndConvertRowData(columns, request.data());

    List<String> columnNames = columns.stream().map(DatasetColumnResponse::columnName).toList();
    Map<String, String> columnTypes = buildColumnTypes(columns);
    dataTableRowService.updateRow(
        dataset.tableName(), rowId, columnNames, validatedData, columnTypes);
  }

  @Transactional(readOnly = true)
  public RowDataResponse getRow(Long datasetId, Long rowId) {
    DatasetResponse dataset =
        datasetRepository
            .findById(datasetId)
            .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));

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
    Map<String, Object> result = new HashMap<>();
    List<String> errors = new ArrayList<>();

    for (DatasetColumnResponse col : columns) {
      Object value = data.get(col.columnName());

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
          "Point", "LineString", "Polygon",
          "MultiPoint", "MultiLineString", "MultiPolygon", "GeometryCollection");

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
