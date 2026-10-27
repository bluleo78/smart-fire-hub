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
import com.smartfirehub.dataset.dto.SpatialFilter;
import com.smartfirehub.dataset.dto.SqlQueryRequest;
import com.smartfirehub.dataset.dto.SqlQueryResponse;
import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.repository.QueryHistoryRepository;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.pipeline.service.PipelineDataSchema;
import com.smartfirehub.pipeline.service.StoreTransactions;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.sql.GuardedSqlExecutor;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetDataService {

  private final DatasetRepository datasetRepository;
  private final DatasetColumnRepository columnRepository;
  private final DataTableService dataTableService;
  private final DataTableRowService dataTableRowService;
  private final GuardedSqlExecutor guardedSqlExecutor;
  private final ClearanceResolver clearanceResolver;
  private final QueryHistoryRepository queryHistoryRepository;

  /** 실행 구간을 코드로 나누는 트랜잭션 템플릿(쓰기 기본값) — Tibero 실행을 PG 트랜잭션 밖에 두려고 쓴다(WD-207). */
  private final TransactionTemplate tx;

  /** 테넌트 저장소 판정 — Tibero 인지에 따라 실행 구간을 나눈다. */
  private final PipelineDataSchema pipelineDataSchema;

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
        datasetRepository.findById(datasetId).orElseThrow(() -> new DatasetNotFoundException());
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
        datasetRepository.findById(datasetId).orElseThrow(() -> new DatasetNotFoundException());
    rejectIfDocument(dataset.storageType(), "데이터 삭제");
    long rowCount = dataTableRowService.countRows(dataset.tableName());
    dataTableRowService.truncateTable(dataset.tableName());
    return new DataDeleteResponse((int) rowCount);
  }

  @Transactional(readOnly = true)
  public RowCountResponse getRowCount(Long datasetId) {
    DatasetResponse dataset =
        datasetRepository.findById(datasetId).orElseThrow(() -> new DatasetNotFoundException());
    rejectIfDocument(dataset.storageType(), "행 수 조회");
    long rowCount = dataTableRowService.countRows(dataset.tableName());
    return new RowCountResponse(rowCount);
  }

  @Transactional
  public BatchRowDataResponse replaceDatasetData(Long datasetId, BatchRowDataRequest request) {
    DatasetResponse dataset =
        datasetRepository.findById(datasetId).orElseThrow(() -> new DatasetNotFoundException());
    rejectIfDocument(dataset.storageType(), "데이터 교체");

    List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(datasetId);

    List<Map<String, Object>> validatedRows = new ArrayList<>();
    for (int i = 0; i < request.rows().size(); i++) {
      try {
        validatedRows.add(validateAndConvertRowData(columns, request.rows().get(i)));
      } catch (IllegalArgumentException e) {
        // 사용자에게 보이는 행 번호는 1부터 센다(요청 배열 인덱스 0 = 1번째 행).
        throw new IllegalArgumentException((i + 1) + "번째 행: " + e.getMessage());
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
        datasetRepository.findById(datasetId).orElseThrow(() -> new DatasetNotFoundException());
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
        datasetRepository.findById(datasetId).orElseThrow(() -> new DatasetNotFoundException());
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

  public SqlQueryResponse executeQuery(Long datasetId, SqlQueryRequest request, Long userId) {
    // PG 테넌트: 존재 확인·실행·기록을 쓰기 트랜잭션 하나로 처리한다.
    // Tibero 실행(최대 35초) 동안 PG 연결을 쥐지 않으려고 구간을 나눈다(WD-207).
    // 존재 확인은 리포지토리 자체 트랜잭션(조회 템플릿 없음), 실행은 트랜잭션 밖, 기록만 쓰기 트랜잭션으로 연다.
    // 실행이 예외(400/403)면 기록 없이 그대로 올라간다.
    return StoreTransactions.loadExecuteThen(
        pipelineDataSchema,
        tx,
        null,
        // 존재 확인만 필요하다 — 조회 결과(데이터셋)는 도우미의 조회 단계 자리를 채울 뿐 쓰지 않는다. 없으면 404.
        () ->
            datasetRepository.findById(datasetId).orElseThrow(() -> new DatasetNotFoundException()),
        ignored -> executeSql(request),
        tx,
        response -> {
          saveHistory(datasetId, request, userId, response);
          return response;
        });
  }

  /** SQL 을 실행한다. 트랜잭션 구간은 호출자(executeQuery)가 정한다. */
  private SqlQueryResponse executeSql(SqlQueryRequest request) {
    // 보안 등급(S2): 경로의 데이터셋이 아니라 SQL 이 참조하는 모든 데이터셋을 실행자 기준으로 판정한다 — /query 는 data 스키마 전체에 닿는다.
    return guardedSqlExecutor.executeDatasetQuery(
        clearanceResolver.current(), request.sql(), request.maxRows());
  }

  /** 실행 결과를 쿼리 이력에 저장한다. 호출자가 연 쓰기 트랜잭션 안에서 부른다. */
  private void saveHistory(
      Long datasetId, SqlQueryRequest request, Long userId, SqlQueryResponse response) {
    queryHistoryRepository.save(
        datasetId,
        userId,
        request.sql(),
        response.queryType(),
        response.affectedRows(),
        response.executionTimeMs(),
        response.error() == null,
        response.error());
  }

  @Transactional(readOnly = true)
  public PageResponse<QueryHistoryResponse> getQueryHistory(Long datasetId, int page, int size) {
    datasetRepository.findById(datasetId).orElseThrow(() -> new DatasetNotFoundException());

    List<QueryHistoryResponse> content =
        queryHistoryRepository.findByDatasetId(datasetId, page, size);
    long totalElements = queryHistoryRepository.countByDatasetId(datasetId);
    int totalPages = (int) Math.ceil((double) totalElements / size);
    return new PageResponse<>(content, page, size, totalElements, totalPages);
  }

  @Transactional
  public RowDataResponse addRow(Long datasetId, RowDataRequest request) {
    DatasetResponse dataset =
        datasetRepository.findById(datasetId).orElseThrow(() -> new DatasetNotFoundException());
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
      if ("id".equals(entry.getKey())
          || "import_id".equals(entry.getKey())
          || DataTableService.UPDATED_AT_COLUMN.equals(entry.getKey())) {
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
        datasetRepository.findById(datasetId).orElseThrow(() -> new DatasetNotFoundException());
    rejectIfDocument(dataset.storageType(), "행 일괄 추가");

    List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(datasetId);

    List<Map<String, Object>> validatedRows = new ArrayList<>();
    for (int i = 0; i < request.rows().size(); i++) {
      try {
        validatedRows.add(validateAndConvertRowData(columns, request.rows().get(i)));
      } catch (IllegalArgumentException e) {
        // 사용자에게 보이는 행 번호는 1부터 센다(요청 배열 인덱스 0 = 1번째 행).
        throw new IllegalArgumentException((i + 1) + "번째 행: " + e.getMessage());
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
        datasetRepository.findById(datasetId).orElseThrow(() -> new DatasetNotFoundException());
    rejectIfDocument(dataset.storageType(), "행 수정");

    List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(datasetId);
    List<String> columnNames = columns.stream().map(DatasetColumnResponse::columnName).toList();
    Map<String, String> columnTypes = buildColumnTypes(columns);

    // (#672·#772) 부분 업데이트: 요청 바디에 없는 컬럼은 DB 값을 그대로 유지한다.
    // 예전에는 행 전체를 읽어 요청 값과 병합한 뒤 "모든" 컬럼을 다시 UPDATE 했는데, 그 왕복이
    // 값을 바꿨다 — GEOMETRY 는 ST_AsGeoJSON(소수 9자리)으로 읽혀 좌표가 반올림됐고, 날짜·시각은
    // 읽기 표현(#769 PG 원문 등)에 보존이 기대야 했다. 이제 요청에 "명시적으로 포함된" 컬럼(값이
    // null 이어도 포함)만 검증해 SET 하고, 나머지 컬럼은 UPDATE 문에 아예 넣지 않는다.
    List<String> requestedColumns =
        columnNames.stream().filter(request.data()::containsKey).toList();

    if (requestedColumns.isEmpty()) {
      // 바꿀 컬럼이 없으면 쓰기 없이 행 존재만 확인한다(없는 행은 기존과 같이 RowNotFound).
      dataTableRowService.getRow(dataset.tableName(), columnNames, rowId, columnTypes);
      return;
    }

    List<DatasetColumnResponse> requestedColumnDefs =
        columns.stream().filter(c -> request.data().containsKey(c.columnName())).toList();
    Map<String, Object> requestedData = new HashMap<>();
    for (String columnName : requestedColumns) {
      requestedData.put(columnName, request.data().get(columnName));
    }

    Map<String, Object> validatedData =
        validateAndConvertRowData(requestedColumnDefs, requestedData, request.data().keySet());
    dataTableRowService.updateRow(
        dataset.tableName(), rowId, requestedColumns, validatedData, columnTypes);
  }

  @Transactional(readOnly = true)
  public RowDataResponse getRow(Long datasetId, Long rowId) {
    DatasetResponse dataset =
        datasetRepository.findById(datasetId).orElseThrow(() -> new DatasetNotFoundException());
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
      if ("import_id".equals(entry.getKey())
          || DataTableService.UPDATED_AT_COLUMN.equals(entry.getKey())) {
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
    datasetRepository.findById(datasetId).orElseThrow(() -> new DatasetNotFoundException());

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
   * <p>{@code columnsToValidate}에 포함된 컬럼만 not-null 검사 및 타입 변환을 수행하고, 그 외 컬럼(예: 부분 업데이트에서 요청에 없어 기존
   * DB 값으로 병합된 컬럼)은 이미 유효한 값으로 간주해 그대로 통과시킨다 (#672). 병합된 기존 값은 DB에서 그대로 조회한 원시 타입이라 {@link
   * #convertValue}가 기대하는 입력 포맷(예: DATE 컬럼의 문자열)과 다를 수 있어 재변환하면 오히려 실패하므로, 재검증 없이 그대로 사용해야 한다.
   */
  private Map<String, Object> validateAndConvertRowData(
      List<DatasetColumnResponse> columns,
      Map<String, Object> data,
      Set<String> columnsToValidate) {
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
          errors.add(fieldLabel(col) + ": 필수 입력 항목입니다");
        }
        result.put(col.columnName(), null);
        continue;
      }

      try {
        Object converted = convertValue(col, value);
        result.put(col.columnName(), converted);
      } catch (IllegalArgumentException e) {
        // 변환기가 던지는 IllegalArgumentException 은 모두 사용자용 한국어 사유다(WD-201).
        errors.add(fieldLabel(col) + ": " + e.getMessage());
      } catch (Exception e) {
        // 그 밖의 예외 문구는 내부 정보(파서·라이브러리 원문)일 수 있어 사용자 문구에 싣지 않는다.
        log.debug("행 값 변환 실패: column={}", col.columnName(), e);
        errors.add(fieldLabel(col) + ": 값을 저장할 수 있는 형식으로 바꾸지 못했습니다");
      }
    }

    if (!errors.isEmpty()) {
      // 화면 토스트에 그대로 보이는 문구라 한국어로 묶는다(WD-201) — 칸별 사유는 "; " 로 잇는다.
      throw new IllegalArgumentException("입력값을 확인하세요: " + String.join("; ", errors));
    }

    return result;
  }

  /** 오류 문구에 쓸 필드 이름 — 화면 표시 이름이 있으면 「표시 이름(컬럼명)」, 없으면 컬럼명. */
  private static String fieldLabel(DatasetColumnResponse col) {
    String display = col.displayName();
    if (display == null || display.isBlank() || display.equals(col.columnName())) {
      return "'" + col.columnName() + "' 필드";
    }
    return "'" + display + "(" + col.columnName() + ")' 필드";
  }

  /**
   * 요청 값을 컬럼 타입의 Java 값으로 바꾼 뒤 물리 타입 한도를 엄격 모드로 검사한다 — 사람이 직접 입력한 값이라 저장값이 입력값과 달라질 값(소수부 7자리 이상,
   * 길이 초과)은 반올림·절단하지 않고 거부한다(웹 행 폼과 같은 판정, 임포트는 관대 모드).
   */
  private Object convertValue(DatasetColumnResponse col, Object value) {
    return ColumnPhysicalType.enforceLimitsStrict(
        convertFormat(col, value), col.dataType(), col.maxLength());
  }

  /** 요청 값의 형식만 컬럼 타입에 맞춰 변환한다 — 물리 한도 검사는 {@link #convertValue} 가 이어서 한다. */
  private Object convertFormat(DatasetColumnResponse col, Object value) {
    return switch (col.dataType()) {
      case "TEXT", "VARCHAR" -> {
        if (!(value instanceof String s)) {
          throw new IllegalArgumentException("문자열이어야 합니다");
        }
        yield s;
      }
      case "INTEGER" -> ColumnPhysicalType.toExactBigint(parseNumber(value), value);
      case "DECIMAL" -> parseNumber(value);
      case "BOOLEAN" -> {
        if (value instanceof Boolean b) {
          yield b;
        }
        throw new IllegalArgumentException("참/거짓(true/false) 값이어야 합니다");
      }
      case "DATE" -> {
        if (value instanceof String s) {
          try {
            yield LocalDate.parse(s);
          } catch (Exception e) {
            throw new IllegalArgumentException("날짜 형식이 아닙니다 (yyyy-MM-dd)");
          }
        }
        throw new IllegalArgumentException("날짜 형식이 아닙니다 (yyyy-MM-dd)");
      }
      case "TIMESTAMP" -> {
        if (value instanceof String s) {
          try {
            yield LocalDateTime.parse(s);
          } catch (Exception e) {
            throw new IllegalArgumentException("날짜시간 형식이 아닙니다 (yyyy-MM-ddTHH:mm:ss)");
          }
        }
        throw new IllegalArgumentException("날짜시간 형식이 아닙니다 (yyyy-MM-ddTHH:mm:ss)");
      }
      case "GEOMETRY" -> {
        if (!(value instanceof String s)) {
          throw new IllegalArgumentException("GeoJSON 문자열이어야 합니다");
        }
        // Feature 는 안의 geometry 로 바꿔 저장한다 — ST_GeomFromGeoJSON 은 Feature 를 받지 못한다(WD-201, 임포트와 같은
        // 규칙).
        yield GeoJsonValues.toStorableGeometry(s);
      }
      default -> throw new IllegalArgumentException("알 수 없는 데이터 타입입니다: " + col.dataType());
    };
  }

  /** 숫자 문자열 길이 상한 — Jackson 의 JSON 숫자 길이 기본 상한(1000자)과 맞춰 거대한 문자열 파싱 비용을 막는다. */
  private static final int MAX_NUMBER_STRING_LENGTH = 1000;

  /**
   * 숫자 입력(문자열 또는 JSON 숫자)을 값 그대로 BigDecimal 로 바꾼다 — 지수 표기 허용, 천단위 콤마·NaN·Infinity 는 형식 오류.
   *
   * <p>웹 행 폼은 입력 원문 문자열을 보내 정확히 파싱된다. JSON 숫자(API·AI 에이전트 호출)도 받지만 Jackson 이 JSON 소수를 Double 로 읽어
   * 유효숫자 약 15~17자리를 넘는 값은 이미 깎여 있다 — 정확한 값이 필요한 호출자는 문자열로 보내야 한다.
   */
  private static BigDecimal parseNumber(Object value) {
    if (!(value instanceof String) && !(value instanceof Number)) {
      throw new IllegalArgumentException("숫자 형식이 아닙니다");
    }
    String s = value.toString().trim();
    if (s.isEmpty() || s.length() > MAX_NUMBER_STRING_LENGTH) {
      throw new IllegalArgumentException("숫자 형식이 아닙니다");
    }
    try {
      return new BigDecimal(s);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("숫자 형식이 아닙니다");
    }
  }

  /** Builds a column name → data type map from column metadata. */
  private static Map<String, String> buildColumnTypes(List<DatasetColumnResponse> columns) {
    Map<String, String> types = new HashMap<>();
    for (DatasetColumnResponse col : columns) {
      types.put(col.columnName(), col.dataType());
    }
    return types;
  }
}
