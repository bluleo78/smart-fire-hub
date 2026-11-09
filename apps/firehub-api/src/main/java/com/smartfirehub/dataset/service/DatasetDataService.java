package com.smartfirehub.dataset.service;

import com.smartfirehub.analytics.service.AnalyticsQueryConcurrencyGate;
import com.smartfirehub.analytics.service.InteractiveQueryConcurrencyGate;
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
import com.smartfirehub.dataset.repository.DatasetWriteTarget;
import com.smartfirehub.dataset.repository.QueryHistoryRepository;
import com.smartfirehub.dataset.storage.DataStoreBackend;
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

  /** 데이터 테이블 쓰기 공용 잠금(WD-666). */
  private final DatasetDataWriteLock writeLock;

  /** 실행 구간을 코드로 나누는 트랜잭션 템플릿(쓰기 기본값) — Tibero 실행을 PG 트랜잭션 밖에 두려고 쓴다(WD-207). */
  private final TransactionTemplate tx;

  /** 테넌트 저장소 판정 — Tibero 인지에 따라 실행 구간을 나눈다. */
  private final PipelineDataSchema pipelineDataSchema;

  /** 데이터 탭 행 추가·수정·일괄 삭제 감사 기록(WD-463). */
  private final DatasetAuditRecorder datasetAuditRecorder;

  /** 화면 쿼리 동시 실행 상한(WD-599) — 트랜잭션·커넥션을 열기 전에 허가를 얻어, 기다리는 동안 풀을 쥐지 않는다. */
  private final InteractiveQueryConcurrencyGate interactiveQueryGate;

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

  // WD-551·WD-566·WD-666: 쓰기 잠금은 공용 층(DatasetDataWriteLock)이 맡는다.
  // 행 쓰기는 KEY SHARE, 이 클래스의 truncate·replace(데이터 탭 비우기·교체)는 FOR UPDATE 다 — 행 수를 센 뒤 TRUNCATE 로
  // 격상하므로 서로 배타로 줄 세운다. 파일 가져오기의 REPLACE 는 KEY SHARE 다(사다리 전체와 그 차이는 DatasetDataWriteLock 참고,
  // WD-674).
  // 호출자가 모두 @Transactional 이라 잠금이 데이터 테이블 쓰기와 같은 트랜잭션에서 커밋까지 유지된다.

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
    DatasetWriteTarget dataset = writeLock.lockForStructureChange(datasetId);
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
    DatasetWriteTarget dataset = writeLock.lockForStructureChange(datasetId);
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

  /** 행위자 없는 내부·테스트 경로 — 감사는 행위자 "system" 으로 남는다. 사용자 요청은 userId 를 받는 오버로드로 들어온다. */
  @Transactional
  public DataDeleteResponse deleteDataRows(Long datasetId, List<Long> rowIds) {
    return deleteDataRows(datasetId, rowIds, null);
  }

  /**
   * 행을 일괄 삭제하고, 지운 행이 있으면 요청 한 건당 감사 한 행(WD-463, ROWS_DELETE)을 남긴다. metadata 에는 지운 개수와 요청한 내부 행 id
   * 앞 {@value DatasetAuditRecorder#MAX_ROW_IDS}개만 싣는다 — 행 값은 싣지 않는다.
   */
  @Transactional
  public DataDeleteResponse deleteDataRows(Long datasetId, List<Long> rowIds, Long userId) {
    DatasetWriteTarget dataset = writeLock.lockForDataWrite(datasetId);
    rejectIfDocument(dataset.storageType(), "행 삭제");
    int deletedCount = dataTableRowService.deleteRows(dataset.tableName(), rowIds);
    if (deletedCount > 0) {
      datasetAuditRecorder.record(
          userId,
          "DELETE",
          datasetId,
          dataset.name(),
          "ROWS_DELETE",
          "행 일괄 삭제: " + dataset.name() + " (" + deletedCount + "건)",
          Map.of("count", deletedCount, "rowIds", DatasetAuditRecorder.cappedRowIds(rowIds)));
    }
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
    // WD-638: 순서는 존재 확인(404) → 판정(400/403) → 허가 → (PG) 재판정 → 실행·기록. 차트·대시보드(WD-625)와 같이
    // 허가는 「실제로 실행한다」가 정해진 뒤에만 얻는다 — 게이트가 차 있어도 없는 데이터셋·거부·잘못된 SQL 은 줄 서지 않고 곧바로
    // 끝나고, 실행하지 않을 요청이 자리를 차지하지 않는다.
    // 존재 확인은 리포지토리 자체 트랜잭션, 판정은 트랜잭션 밖(가드는 리포지토리가 트랜잭션을 보장하고, 거부 감사는 감사 실행기
    // 스레드가 쓴다 — WD-682) — 둘 다 허가 전에 커넥션을 돌려준다.
    requireDataset(datasetId);
    // 보안 등급(S2): 경로의 데이터셋이 아니라 SQL 이 참조하는 모든 데이터셋을 실행자 기준으로 판정한다 — /query 는 data 스키마 전체에 닿는다.
    // 이 앞 판정은 거부(403)·잘못된 SQL(400)을 허가 없이 곧바로 끝내기 위한 것이다. PG 는 허가를 기다리는 동안(최대 wait-ms) 보안 등급
    // 상향·테이블 매핑 변경이 있을 수 있어 실행 직전에 다시 판정하고 그 토큰을 실행한다(아래 judgeForExecution). Tibero 는 허가 대기가 없어
    // 이 토큰을 그대로 실행한다.
    GuardedSqlExecutor.DatasetQueryJudgment judgment =
        guardedSqlExecutor.judgeDatasetQuery(clearanceResolver.current(), request.sql());
    // WD-599: 허가는 쓰기 트랜잭션(=커넥션)보다 먼저, 트랜잭션 밖에서 얻는다 — 기다리는 동안 풀을 쥐지 않는다. 바깥(컨트롤러·필터)에 트랜잭션이
    // 없는 경로다. 예외로 나가도 try-with-resources 가 반납한다. Tibero 테넌트는 db-executor 로 실행해 PG 풀 커넥션을 쥐지 않으므로
    // (WD-207) 허가 없이 지난다(acquireUnlessTibero).
    try (AnalyticsQueryConcurrencyGate.Permit permit =
        interactiveQueryGate.acquireUnlessTibero(pipelineDataSchema, userId)) {
      // PG 테넌트: 재확인·실행·기록을 쓰기 트랜잭션 하나로 처리한다(DML 쓰기 잠금·DML 결과와 이력이 함께 커밋/롤백).
      // Tibero: 실행(최대 35초) 동안 PG 연결을 쥐지 않으려고 재확인은 리포지토리 자체 트랜잭션, 실행은 트랜잭션 밖, 기록만 쓰기 트랜잭션(WD-207).
      return StoreTransactions.loadExecuteThen(
          pipelineDataSchema,
          tx,
          null,
          // 허가를 기다리는 동안(최대 wait-ms) 데이터셋이 지워졌으면 실행 전에 404 로 끝낸다 — 이력 저장이 지워진 데이터셋을 가리키지 않게.
          () -> requireDataset(datasetId),
          ignored -> executeJudged(judgeForExecution(judgment, request), request),
          tx,
          response -> {
            saveHistory(datasetId, request, userId, response);
            return response;
          });
    }
  }

  /** 데이터셋이 있는지만 확인한다(없으면 404). 조회 결과는 쓰지 않는다. */
  private DatasetResponse requireDataset(Long datasetId) {
    return datasetRepository.findById(datasetId).orElseThrow(() -> new DatasetNotFoundException());
  }

  /**
   * 실행할 판정 토큰을 고른다. PG 는 쓰기 트랜잭션 안, 쓰기 잠금·실행 바로 앞에서 다시 판정한다 — 앞 판정은 허가 대기 전이라, 기다리는 동안 관리자가 보안 등급을
   * 올리거나 매핑을 바꿨으면 낡은 판정으로 실행하게 된다(WD-638 전에는 판정이 이 트랜잭션 안 실행 직전이었다). 거부면 여기서 403(감사 포함)·400 이 나
   * 트랜잭션이 롤백되고 실행·이력 저장이 없다. Tibero 는 허가 대기가 없어 앞 판정을 그대로 쓴다.
   *
   * <p>열람자 자격도 다시 읽는다({@link ClearanceResolver#readFresh()}) — {@code current()} 는 요청당 한 번 읽어
   * 캐시하므로, 앞 판정의 자격을 그대로 쓰면 기다리는 동안 낮아진 열람자 등급(역할 최대 등급 하향 등)이 재판정에 반영되지 않는다(WD-682). 그래서 재판정은 매핑
   * 조회에 더해 자격 조회(역할 행·권한 목록)까지 하지만, 모두 이 트랜잭션 안의 짧은 조회라 커넥션을 더 잡지 않고 실행에 비하면 작다. 허가가 실제로 기다렸을 때만이
   * 아니라 늘 다시 판정한다 — 대기 없이 들어와도 앞 판정과 허가 사이에 변경이 끼어들 수 있어 「기다렸는가」는 낡음의 기준이 아니다.
   */
  private GuardedSqlExecutor.DatasetQueryJudgment judgeForExecution(
      GuardedSqlExecutor.DatasetQueryJudgment early, SqlQueryRequest request) {
    if (pipelineDataSchema.isTibero()) {
      return early;
    }
    return guardedSqlExecutor.judgeDatasetQuery(clearanceResolver.readFresh(), request.sql());
  }

  /** 판정 토큰을 실행한다. 트랜잭션 구간은 호출자(executeQuery)가 정한다. */
  private SqlQueryResponse executeJudged(
      GuardedSqlExecutor.DatasetQueryJudgment judgment, SqlQueryRequest request) {
    // WD-666: DML 이면 쓰기 대상 데이터셋들의 쓰기 잠금(KEY SHARE, id 오름차순)을 실행 직전에 같은 PG 트랜잭션에서 잡는다 — 잠금이
    // 이력 저장 커밋까지 유지돼 truncate·NOT NULL 필드 추가·삭제와 줄 선다. 허가는 이 트랜잭션을 열기 전에 이미 얻었다(허가 → 트랜잭션 → 잠금).
    // Tibero 는 실행이 PG 트랜잭션 밖이라(WD-207) 잠금을 쥘 수 없어 잡지 않는다(남은 위험).
    return guardedSqlExecutor.executeJudgedDatasetQuery(
        judgment,
        request.maxRows(),
        pipelineDataSchema.isTibero() ? writeTargets -> {} : writeLock::lockAllForDataWrite);
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

  /** 행위자 없는 내부·테스트 경로 — 감사는 행위자 "system" 으로 남는다. 사용자 요청은 userId 를 받는 오버로드로 들어온다. */
  @Transactional
  public RowDataResponse addRow(Long datasetId, RowDataRequest request) {
    return addRow(datasetId, request, null);
  }

  /** 행을 추가하고, 성공하면 감사(WD-463, ROW_ADD)를 남긴다 — 새 내부 행 id 와 값을 넣은 필드 이름만 싣는다. */
  @Transactional
  public RowDataResponse addRow(Long datasetId, RowDataRequest request, Long userId) {
    DatasetWriteTarget dataset = writeLock.lockForDataWrite(datasetId);
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

    // 감사(WD-463) — 검증·INSERT 가 끝난 뒤. 값은 싣지 않고 요청에 들어 있던 필드 이름만 싣는다.
    List<String> filledFields = columnNames.stream().filter(request.data()::containsKey).toList();
    datasetAuditRecorder.record(
        userId,
        "UPDATE",
        datasetId,
        dataset.name(),
        "ROW_ADD",
        "행 추가: " + dataset.name() + " (행 ID " + newId + ")",
        Map.of("count", 1, "rowIds", List.of(newId), "changedFields", filledFields));

    return new RowDataResponse(newId, data, createdAt);
  }

  @Transactional
  public BatchRowDataResponse addRowsBatch(Long datasetId, BatchRowDataRequest request) {
    DatasetWriteTarget dataset = writeLock.lockForDataWrite(datasetId);
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

  /** 행위자 없는 내부·테스트 경로 — 감사는 행위자 "system" 으로 남는다. 사용자 요청은 userId 를 받는 오버로드로 들어온다. */
  @Transactional
  public void updateRow(Long datasetId, Long rowId, RowDataRequest request) {
    updateRow(datasetId, rowId, request, null);
  }

  /**
   * 행을 부분 수정하고, 성공하면 감사(WD-463, ROW_UPDATE)를 남긴다. 웹 편집 창은 바뀐 칸만 보내므로 요청에 든 필드 이름을 바뀐 항목으로 싣는다 — 값은
   * 싣지 않는다. 바꿀 필드가 없는 요청(쓰기 없음)과 없는 행(404)은 남기지 않는다.
   */
  @Transactional
  public void updateRow(Long datasetId, Long rowId, RowDataRequest request, Long userId) {
    DatasetWriteTarget dataset = writeLock.lockForDataWrite(datasetId);
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

    datasetAuditRecorder.record(
        userId,
        "UPDATE",
        datasetId,
        dataset.name(),
        "ROW_UPDATE",
        "행 수정: "
            + dataset.name()
            + " (행 ID "
            + rowId
            + ", 바뀐 항목: "
            + String.join("·", requestedColumns)
            + ")",
        Map.of("count", 1, "rowIds", List.of(rowId), "changedFields", requestedColumns));
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
    // 길이 미지정 VARCHAR 의 한도는 저장소마다 다르다(PG 255, Tibero 4000) — 행마다 한 번 현재 테넌트 저장소를 정해 모든 칸에
    // 쓴다(WD-659).
    DataStoreBackend backend = dataTableService.currentBackend();

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
        Object converted = convertValue(col, value, backend);
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
   * 길이 초과)은 반올림·절단하지 않고 거부한다(웹 행 폼과 같은 판정, 임포트는 관대 모드). 길이 미지정 VARCHAR 는 {@code backend} 의 기본 길이로
   * 검사한다(WD-659 — Tibero 는 4000자까지 받는다).
   */
  private Object convertValue(DatasetColumnResponse col, Object value, DataStoreBackend backend) {
    return ColumnPhysicalType.enforceLimitsStrict(
        convertFormat(col, value), col.dataType(), col.maxLength(), backend);
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
