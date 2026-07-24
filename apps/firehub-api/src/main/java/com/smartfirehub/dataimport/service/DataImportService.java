package com.smartfirehub.dataimport.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.audit.dto.AuditLogResponse;
import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.dataimport.dto.*;
import com.smartfirehub.dataimport.exception.ConcurrentImportException;
import com.smartfirehub.dataimport.exception.UnsupportedFileTypeException;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.DatasetResponse;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.job.service.AsyncJobService;
import com.smartfirehub.notification.service.NotificationService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.scheduling.JobScheduler;
import org.jooq.JSONB;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Slf4j
public class DataImportService {

  // previewImport/validateImport 스트리밍 파싱 시 배치 크기.
  // 배치 단위로 파싱+검증하여 전체 행을 한 번에 메모리에 적재하지 않는다(400MB xlsb preview/validate OOM 해소, #7).
  private static final int BATCH_SIZE = 2000;

  // 사전 검증 샘플 크기 — 전량 검증(임포트 잡)이 아닌, 스키마/매핑 빠른 확인용 앞 N행.
  private static final int SAMPLE_VALIDATION_ROWS = 200;

  /**
   * 스트리밍 검증(Pass1) 중 fail-fast 중단을 위한 내부 신호용 예외. 오류가 하나라도 나오면 즉시 던져 부분 적재를 방지한다
   * (Task2: 검증→삽입 2단계 분리). RuntimeException을 상속해 REPLACE(PK 無) 트랜잭션 람다의 rethrow 분기를 그대로 통과하고
   * 트랜잭션 롤백을 유발한다. 스택트레이스는 불필요하므로 생성 비용을 없앤다.
   */
  private static final class ValidationAbortException extends RuntimeException {
    ValidationAbortException() {
      super(null, null, false, false);
    }
  }

  private final DatasetRepository datasetRepository;
  private final DatasetColumnRepository columnRepository;
  private final DataTableService dataTableService;
  private final DataTableRowService dataTableRowService;
  private final FileParserService fileParserService;
  private final DataValidationService validationService;
  private final ColumnMappingService columnMappingService;
  private final AuditLogService auditLogService;
  private final JobScheduler jobScheduler;
  private final ObjectMapper objectMapper;
  private final AsyncJobService asyncJobService;
  private final TransactionTemplate transactionTemplate;
  private final NotificationService notificationService;

  public ImportPreviewResponse previewImport(Long datasetId, MultipartFile file) throws Exception {
    return previewImport(datasetId, file, ParseOptions.defaults());
  }

  public ImportPreviewResponse previewImport(
      Long datasetId, MultipartFile file, ParseOptions parseOptions) throws Exception {
    return previewImport(datasetId, file, parseOptions, false);
  }

  /**
   * 데이터셋 임포트 미리보기. {@code partial=true}는 프론트가 대용량 CSV의 앞부분만 잘라 전송한 경우로, 파일 끝이 잘려 있다. 이때 전체
   * 행수(countRows)는 잘린 마지막 행이 열린 따옴표로 끝나면 예외를 던질 수 있고 값도 부정확하므로 계산을 건너뛴다(totalRows=-1). totalRows는
   * 미리보기 UI에서 사용되지 않으며, 실제 전체 행수는 검증/임포트 시점에 전체 파일로 확정된다.
   */
  public ImportPreviewResponse previewImport(
      Long datasetId, MultipartFile file, ParseOptions parseOptions, boolean partial)
      throws Exception {
    // Validate dataset exists
    datasetRepository
        .findById(datasetId)
        .orElseThrow(() -> new IllegalArgumentException("Dataset not found: " + datasetId));

    // Validate file type
    String originalFilename = file.getOriginalFilename();
    if (originalFilename == null || originalFilename.isEmpty()) {
      throw new UnsupportedFileTypeException("File name is required");
    }

    String fileType = getFileType(originalFilename);
    // XLS(레거시 바이너리 포맷)/XLSB(바이너리 XLSX)도 XLSX와 동일한 스트리밍 파서로 처리 가능하므로 허용
    if (!fileType.equals("csv")
        && !fileType.equals("xlsx")
        && !fileType.equals("xls")
        && !fileType.equals("xlsb")) {
      throw new UnsupportedFileTypeException(
          "Unsupported file type. Only CSV, XLSX, XLS, and XLSB are supported.");
    }

    // MultipartFile을 임시 파일로 1회 spill 후 Path 기반 파서로 3회 모두 재사용한다.
    // 기존에는 file.getInputStream()을 3번(헤더/샘플/카운트) 호출했는데, Excel은 매 호출마다
    // 전체를 다시 읽어들여 400MB급 xlsb에서 미리보기만으로도 OOM이 발생했다(#7 실제 신고 케이스).
    // Path 오버로드는 Excel도 File 기반(랜덤액세스)으로 열어 매번 새로 메모리에 적재하지 않는다.
    Path tempDir = Path.of(System.getProperty("java.io.tmpdir"), "firehub-imports");
    Files.createDirectories(tempDir);
    Path tempFile = Files.createTempFile(tempDir, "preview-", "." + fileType);
    try {
      file.transferTo(tempFile.toFile());

      List<String> headers = fileParserService.parseHeaders(tempFile, fileType, parseOptions);

      List<Map<String, String>> sampleRows =
          fileParserService.parseSampleRows(tempFile, fileType, 5, parseOptions);

      // partial 미리보기는 파일 끝이 잘려 있어 전체 행수를 셀 수 없다(위 Javadoc 참고).
      int totalRows =
          partial ? -1 : fileParserService.countRows(tempFile, fileType, parseOptions);

      // Get dataset columns
      List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(datasetId);

      // Suggest mappings
      List<ColumnMappingDto> suggestedMappings =
          columnMappingService.suggestMappings(headers, columns);

      return new ImportPreviewResponse(headers, sampleRows, suggestedMappings, totalRows);
    } finally {
      // 미리보기 완료 후 임시 파일 정리 — 누적 방지
      Files.deleteIfExists(tempFile);
    }
  }

  public ImportValidateResponse validateImport(
      Long datasetId, MultipartFile file, List<ColumnMappingEntry> mappings) throws Exception {
    return validateImport(datasetId, file, mappings, ParseOptions.defaults());
  }

  public ImportValidateResponse validateImport(
      Long datasetId,
      MultipartFile file,
      List<ColumnMappingEntry> mappings,
      ParseOptions parseOptions)
      throws Exception {
    // Validate dataset exists
    datasetRepository
        .findById(datasetId)
        .orElseThrow(() -> new IllegalArgumentException("Dataset not found: " + datasetId));

    // Validate file type
    String originalFilename = file.getOriginalFilename();
    if (originalFilename == null || originalFilename.isEmpty()) {
      throw new UnsupportedFileTypeException("File name is required");
    }

    String fileType = getFileType(originalFilename);
    // XLS(레거시 바이너리 포맷)/XLSB(바이너리 XLSX)도 XLSX와 동일한 스트리밍 파서로 처리 가능하므로 허용
    if (!fileType.equals("csv")
        && !fileType.equals("xlsx")
        && !fileType.equals("xls")
        && !fileType.equals("xlsb")) {
      throw new UnsupportedFileTypeException(
          "Unsupported file type. Only CSV, XLSX, XLS, and XLSB are supported.");
    }

    // Get dataset columns
    List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(datasetId);

    // 앞 200행만 파싱해 스키마/매핑을 빠르게 검사한다(전량 검증은 임포트 잡이 담당).
    // countRows/전량 parseStreaming을 제거해 512MB 파일에서도 O(샘플)로 즉시 응답한다.
    Path tempDir = Path.of(System.getProperty("java.io.tmpdir"), "firehub-imports");
    Files.createDirectories(tempDir);
    Path tempFile = Files.createTempFile(tempDir, "validate-", "." + fileType);
    try {
      file.transferTo(tempFile.toFile());

      List<Map<String, String>> sample =
          fileParserService.parseSampleRows(
              tempFile, fileType, SAMPLE_VALIDATION_ROWS, parseOptions);

      // 임포트 잡 배치 검증과 동일한 로직 재사용. rowIndexBase=0 (샘플은 파일 선두).
      List<ValidationErrorDetail> errors;
      int validCount;
      int errorCount;
      if (mappings != null && !mappings.isEmpty()) {
        DataValidationService.ValidationResultWithDetails vr =
            validationService.validateWithMapping(sample, columns, mappings, 0);
        errors = vr.errors();
        validCount = vr.validCount();
        errorCount = vr.errorCount();
      } else {
        DataValidationService.ValidationResult vr = validationService.validate(sample, columns, 0);
        // ValidationResult.errors()는 List<String>이므로 상세 4필드로 매핑 불가 → 상세 없음 처리.
        // (매핑 없는 경로는 기존에도 상세 컬럼 정보를 제공하지 않음)
        errors = List.of();
        validCount = vr.validCount();
        errorCount = vr.errorCount();
      }

      return new ImportValidateResponse(sample.size(), validCount, errorCount, true, errors);
    } finally {
      // 검증 완료 후 임시 파일 정리 — 누적 방지
      Files.deleteIfExists(tempFile);
    }
  }

  public ImportStartResponse importFile(
      Long datasetId,
      MultipartFile file,
      List<ColumnMappingEntry> mappings,
      Long userId,
      String username,
      String ipAddress,
      String userAgent)
      throws Exception {
    return importFile(
        datasetId,
        file,
        mappings,
        userId,
        username,
        ipAddress,
        userAgent,
        ParseOptions.defaults(),
        ImportMode.APPEND);
  }

  public ImportStartResponse importFile(
      Long datasetId,
      MultipartFile file,
      List<ColumnMappingEntry> mappings,
      Long userId,
      String username,
      String ipAddress,
      String userAgent,
      ParseOptions parseOptions)
      throws Exception {
    return importFile(
        datasetId,
        file,
        mappings,
        userId,
        username,
        ipAddress,
        userAgent,
        parseOptions,
        ImportMode.APPEND);
  }

  public ImportStartResponse importFile(
      Long datasetId,
      MultipartFile file,
      List<ColumnMappingEntry> mappings,
      Long userId,
      String username,
      String ipAddress,
      String userAgent,
      ParseOptions parseOptions,
      ImportMode importMode)
      throws Exception {
    // Validate dataset exists
    datasetRepository
        .findById(datasetId)
        .orElseThrow(() -> new IllegalArgumentException("Dataset not found: " + datasetId));

    // Validate file type
    String originalFilename = file.getOriginalFilename();
    if (originalFilename == null || originalFilename.isEmpty()) {
      throw new UnsupportedFileTypeException("File name is required");
    }

    String fileType = getFileType(originalFilename);
    // XLS(레거시 바이너리 포맷)/XLSB(바이너리 XLSX)도 XLSX와 동일한 스트리밍 파서로 처리 가능하므로 허용
    if (!fileType.equals("csv")
        && !fileType.equals("xlsx")
        && !fileType.equals("xls")
        && !fileType.equals("xlsb")) {
      throw new UnsupportedFileTypeException(
          "Unsupported file type. Only CSV, XLSX, XLS, and XLSB are supported.");
    }

    long fileSize = file.getSize();
    String upperFileType = fileType.toUpperCase();
    String safeFileName =
        originalFilename.length() > 255 ? originalFilename.substring(0, 255) : originalFilename;

    // Create async job — partial unique index enforces one active import per dataset atomically
    String jobId;
    try {
      jobId =
          asyncJobService.createJob(
              "IMPORT",
              "dataset",
              String.valueOf(datasetId),
              userId,
              Map.of(
                  "fileName",
                  safeFileName,
                  "fileSize",
                  fileSize,
                  "fileType",
                  upperFileType,
                  "importMode",
                  importMode.name()));
    } catch (DataIntegrityViolationException e) {
      throw new ConcurrentImportException(
          "An import is already in progress. Please wait for it to complete and try again.");
    }

    // Save file to temp location for Jobrunr processing
    Path tempDir = Path.of(System.getProperty("java.io.tmpdir"), "firehub-imports");
    Files.createDirectories(tempDir);
    Path tempFile = Files.createTempFile(tempDir, "import-", "." + fileType);
    file.transferTo(tempFile.toFile());

    // Extract to local variables for Jobrunr lambda serialization
    String filePath = tempFile.toString();

    // Save mappings to temp file if provided
    String mappingsPath = "";
    if (mappings != null && !mappings.isEmpty()) {
      Path mappingsTempFile = Files.createTempFile(tempDir, "mappings-", ".json");
      String mappingsJson = objectMapper.writeValueAsString(mappings);
      Files.writeString(mappingsTempFile, mappingsJson);
      mappingsPath = mappingsTempFile.toString();
    }

    // Save parseOptions to temp file for Jobrunr serialization
    Path parseOptsTempFile = Files.createTempFile(tempDir, "parseopts-", ".json");
    Files.writeString(parseOptsTempFile, objectMapper.writeValueAsString(parseOptions));
    String parseOptsPath = parseOptsTempFile.toString();

    // Extract to local variables for lambda
    String finalMappingsPath = mappingsPath;
    // Use String for importMode so Jobrunr can serialize it without enum class issues
    String importModeName = importMode.name();

    // Enqueue Jobrunr job
    jobScheduler.enqueue(
        () ->
            processImport(
                jobId,
                datasetId,
                filePath,
                finalMappingsPath,
                parseOptsPath,
                originalFilename,
                fileSize,
                upperFileType,
                userId,
                username,
                ipAddress,
                userAgent,
                importModeName));

    return new ImportStartResponse(jobId, "PENDING");
  }

  @Job(name = "Data import: %5 → dataset %1")
  public void processImport(
      String jobId,
      Long datasetId,
      String filePath,
      String mappingsPath,
      String parseOptsPath,
      String fileName,
      Long fileSize,
      String fileType,
      Long userId,
      String username,
      String ipAddress,
      String userAgent,
      String importModeName) {
    // Resolve dataset name once for notifications (used in both success and failure paths)
    String datasetNameForNotification =
        datasetRepository.findById(datasetId).map(d -> d.name()).orElse(String.valueOf(datasetId));

    // UPSERT/REPLACE(PK 有)가 사용하는 staging 테이블명. finally에서 성공/실패 무관하게 항상 정리한다.
    String stagingTable = null;

    try {
      asyncJobService.updateProgress(
          jobId, "PARSING", 10, "Parsing file...", Map.of("totalRows", 0, "processedRows", 0));

      // Load parse options
      ParseOptions parseOptions = ParseOptions.defaults();
      if (parseOptsPath != null && !parseOptsPath.isEmpty()) {
        try {
          String optsJson = Files.readString(Path.of(parseOptsPath));
          parseOptions = objectMapper.readValue(optsJson, ParseOptions.class);
        } catch (Exception e) {
          log.warn("Failed to read parse options from {}, using defaults", parseOptsPath, e);
        }
      }

      Path path = Path.of(filePath);
      String fileTypeLower = fileType.toLowerCase();
      // 람다(transactionTemplate)에서 캡처하려면 effectively-final이어야 하므로 확정 값을 복사한다.
      final ParseOptions parseOptionsFinal = parseOptions;

      // Task2: 검증(Pass1)이 전량 스트리밍하며 행수를 세므로, 별도의 countRows() pre-scan은 더 이상 필요 없다
      // (기존에는 진행률 분모를 위해 파일을 한 번 더 스캔했으나, Pass1이 그 역할을 겸한다).
      asyncJobService.updateProgress(
          jobId, "VALIDATING", 20, "Validating data...", Map.of("totalRows", 0, "processedRows", 0));

      // Load dataset columns / mappings / mode
      List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(datasetId);
      List<ColumnMappingEntry> mappings = null;
      if (mappingsPath != null && !mappingsPath.isEmpty()) {
        String mappingsJson = Files.readString(Path.of(mappingsPath));
        mappings =
            objectMapper.readValue(mappingsJson, new TypeReference<List<ColumnMappingEntry>>() {});
      }
      boolean hasMappings = mappings != null && !mappings.isEmpty();
      List<ColumnMappingEntry> mappingsFinal = mappings;

      ImportMode importMode =
          ImportMode.valueOf(
              importModeName != null && !importModeName.isEmpty()
                  ? importModeName
                  : ImportMode.APPEND.name());

      DatasetResponse dataset = datasetRepository.findById(datasetId).orElseThrow();
      List<String> columnNames = columns.stream().map(DatasetColumnResponse::columnName).toList();
      List<String> pkColumns =
          columns.stream()
              .filter(DatasetColumnResponse::isPrimaryKey)
              .map(DatasetColumnResponse::columnName)
              .toList();

      // UPSERT/REPLACE(PK 有)는 staging 테이블에 순서대로(배치 커밋) 적재한 뒤, promote 시점에
      // SQL의 DISTINCT ON (pk) ORDER BY pk, _seq DESC로 파일 내 중복 PK를 last-write-wins로 접는다
      // (#281과 동일 목적). 배치 경계를 넘나드는 dedup을 JVM 힙(LinkedHashMap) 대신 DB 엔진에 위임해
      // 대용량 파일에서도 전체 행을 메모리에 들고 있지 않는다. REPLACE(PK 無)는 unique index가 없어
      // dedup 자체가 불필요하므로 staging 없이 target에 직접 적재한다.
      boolean useStaging =
          importMode == ImportMode.UPSERT
              || (importMode == ImportMode.REPLACE && !pkColumns.isEmpty());

      // ---- 배치 콜백에서 캡처할 누적 상태 (1-요소 배열/가변 리스트로 effectively-final 우회) ----
      int[] processedSoFar = {0}; // 검증까지 완료한 행 수(유효+오류) — rowIndexBase 및 최종 totalRows로 사용
      int[] validCount = {0};
      int[] errorCount = {0};
      int[] outerProcessed = {0}; // 실제 INSERT/staging에 반영된 행 수 누적 — progress 40~100% 분자
      List<ValidationErrorDetail> detailErrorsAccum = new ArrayList<>();
      List<String> simpleErrorsAccum = new ArrayList<>();
      boolean[] pkNullFound = {false};
      List<ValidationErrorDetail> pkErrorsAccum = new ArrayList<>();
      boolean[] replaceNoPkTruncated = {false}; // REPLACE(PK 無) lazy truncate: 유효 행 발견 시 1회만

      // Pass1 콜백: 검증 전용. 삽입하지 않고 카운트/오류만 누적하며, 첫 오류가 나오는 배치에서 fail-fast로
      // 중단한다(부분 적재 방지). rowIndexBase는 이전까지 누적 처리된 행 수(전역 오프셋)로, 오류 rowNumber가
      // 배치 로컬이 아닌 파일 전역 기준이 되게 한다(validateImport와 동일 패턴).
      Consumer<List<Map<String, String>>> validateBatch =
          batch -> {
            if (hasMappings) {
              DataValidationService.ValidationResultWithDetails vr =
                  validationService.validateWithMapping(
                      batch, columns, mappingsFinal, processedSoFar[0]);
              validCount[0] += vr.validCount();
              errorCount[0] += vr.errorCount();
              if (detailErrorsAccum.size() < 100) {
                int remaining = 100 - detailErrorsAccum.size();
                detailErrorsAccum.addAll(vr.errors().stream().limit(remaining).toList());
              }
            } else {
              DataValidationService.ValidationResult vr =
                  validationService.validate(batch, columns, processedSoFar[0]);
              validCount[0] += vr.validCount();
              errorCount[0] += vr.errorCount();
              if (simpleErrorsAccum.size() < 100) {
                int remaining = 100 - simpleErrorsAccum.size();
                simpleErrorsAccum.addAll(vr.errors().stream().limit(remaining).toList());
              }
            }
            processedSoFar[0] += batch.size();

            // fail-fast: 검증 오류가 하나라도 나오면 즉시 스트림을 중단한다(부분 적재 방지, Task2).
            // 예외를 던져 parseStreaming 루프를 빠져나오고, 바깥에서 잡아 검증 실패 판정으로 진입시킨다.
            if (errorCount[0] >= 1) {
              throw new ValidationAbortException();
            }

            // 검증 단계는 전체 행수(분모)를 아직 모르므로 % 대신 고정값(20)으로 두어 UI가
            // "검증 중…"을 스피너로 렌더하게 하고, processedRows만 실시간으로 갱신한다.
            asyncJobService.updateProgress(
                jobId,
                "VALIDATING",
                20,
                "Validating data...",
                Map.of("processedRows", processedSoFar[0]));
          };

      // Pass 1: 전량 검증. 첫 오류가 나오는 배치에서 ValidationAbortException으로 중단한다.
      boolean validationPassed;
      try {
        fileParserService.parseStreaming(
            path, fileTypeLower, parseOptionsFinal, BATCH_SIZE, validateBatch);
        validationPassed = errorCount[0] == 0;
      } catch (ValidationAbortException abort) {
        validationPassed = false;
        log.warn(
            "Validation aborted (fail-fast) for dataset {} at row ~{}: {} error(s)",
            datasetId,
            processedSoFar[0],
            errorCount[0]);
      }

      int totalRowsFinal = processedSoFar[0]; // Pass1이 전량 스트리밍하며 센 파일 전체 행수
      final int totalRowsForProgress = Math.max(totalRowsFinal, 1); // 0 나눗셈 방지
      int validCountFinal;
      int errorCountFinal = errorCount[0];

      if (!validationPassed) {
        // fail-fast: 검증 오류가 하나라도 있으면 전량 미적재. 첫 불량 행 상세를 메시지에 실어 원인 파악을 돕는다.
        String sampleError =
            hasMappings
                ? (detailErrorsAccum.isEmpty()
                    ? null
                    : "Row "
                        + detailErrorsAccum.get(0).rowNumber()
                        + " column '"
                        + detailErrorsAccum.get(0).columnName()
                        + "' - "
                        + detailErrorsAccum.get(0).error())
                : (simpleErrorsAccum.isEmpty() ? null : simpleErrorsAccum.get(0));
        String failMessage =
            "Import validation failed, no rows were loaded ("
                + errorCountFinal
                + " error(s) found)"
                + (sampleError != null ? " — e.g. " + sampleError : "");
        asyncJobService.failJob(jobId, failMessage);

        Object errorsForJson = hasMappings ? detailErrorsAccum : simpleErrorsAccum;
        String errorJson = objectMapper.writeValueAsString(Map.of("errors", errorsForJson));

        Map<String, Object> metadata =
            Map.of(
                "fileName",
                fileName,
                "fileSize",
                fileSize,
                "fileType",
                fileType,
                "totalRows",
                totalRowsFinal,
                "successRows",
                0,
                "errorRows",
                errorCountFinal,
                "errorDetails",
                errorJson);

        auditLogService.log(
            userId,
            username,
            "IMPORT",
            "dataset",
            String.valueOf(datasetId),
            "File import: " + fileName,
            ipAddress,
            userAgent,
            "FAILURE",
            failMessage,
            metadata);

        log.error(
            "Import failed: validation error(s) found for dataset {} — {}",
            datasetId,
            failMessage);
        return;
      }

      // ---- Pass 2: 삽입. 여기 도달했다는 것은 파일 전체가 검증을 통과했다는 뜻이다(validationPassed==true). ----
      validCountFinal = validCount[0];

      // 스테이징 생성은 삽입 경로에서만 수행한다 — 검증 중단(fail-fast) 시엔 빈 스테이징을 만들지 않는다
      // (Task2 핵심 위험 1: 스테이징 생명주기). finally의 dropStagingTable은 stagingTable==null이면 no-op.
      if (useStaging) {
        stagingTable = dataTableRowService.createStagingTable(dataset.tableName(), columnNames);
      }
      final String stagingTableFinal = stagingTable;

      // Pass 2 콜백: 삽입 전용. 이미 전량 검증을 통과했으므로 배치 전 행을 그대로 적재한다. 검증기와 동일한
      // toRows() 변환을 사용해 "검증 통과 == 값 변환 성공"이 두 패스에서 어긋나지 않게 한다(핵심 위험 3).
      Consumer<List<Map<String, String>>> insertBatch =
          batch -> {
            List<List<Object>> rows = validationService.toRows(batch, columns, mappingsFinal);
            List<Map<String, Object>> rowMapsBatch =
                rows.stream()
                    .map(
                        row -> {
                          Map<String, Object> rowMap = new HashMap<>();
                          for (int i = 0; i < columnNames.size() && i < row.size(); i++) {
                            rowMap.put(columnNames.get(i), row.get(i));
                          }
                          return rowMap;
                        })
                    .toList();

            final int base = outerProcessed[0];

            switch (importMode) {
              case APPEND -> {
                // NOTE: 검증(Pass1)이 삽입(Pass2) 전에 전량 완료되므로 "검증 실패로 인한" 부분 적재는
                // 없다. 단 APPEND Pass2는 트랜잭션으로 감싸지 않는다(스트리밍 본질 — 대용량 파일을
                // 메모리에 올리지 않기 위함). 따라서 "삽입 도중 DB 오류"가 나면 그 이전까지 커밋된
                // 배치는 롤백되지 않고 남는다. 이는 REPLACE(PK 無)만 truncate+insert를 트랜잭션으로
                // 감싼 것과의 차이이며, 기존 스트리밍 구조가 의도적으로 감수하는 trade-off다.
                BiConsumer<Integer, Integer> wrapped =
                    (processed, total) -> {
                      int globalProcessed = base + processed;
                      int pct =
                          40 + (int) ((globalProcessed / (double) totalRowsForProgress) * 60);
                      asyncJobService.updateProgress(
                          jobId,
                          "INSERTING",
                          Math.min(pct, 100),
                          "Inserting data...",
                          Map.of("totalRows", totalRowsFinal, "processedRows", globalProcessed));
                    };
                dataTableRowService.insertBatchWithProgress(
                    dataset.tableName(), columnNames, rowMapsBatch, wrapped);
                outerProcessed[0] = base + rowMapsBatch.size();
              }
              case UPSERT -> {
                if (pkNullFound[0]) {
                  return; // 이미 NULL PK 확정 — 남은 배치는 staging 적재를 건너뛴다(promote 안 함)
                }
                // Use rowMaps which already have dataset column names as keys
                List<Map<String, String>> rowMapsAsStrings =
                    rowMapsBatch.stream()
                        .map(
                            row -> {
                              Map<String, String> strRow = new HashMap<>();
                              for (var entry : row.entrySet()) {
                                strRow.put(
                                    entry.getKey(),
                                    entry.getValue() != null ? entry.getValue().toString() : null);
                              }
                              return strRow;
                            })
                        .toList();
                DataValidationService.PkValidationResult pkValidation =
                    validationService.validatePrimaryKeys(rowMapsAsStrings, pkColumns);
                if (!pkValidation.errors().isEmpty()) {
                  pkNullFound[0] = true;
                  if (pkErrorsAccum.size() < 100) {
                    int remaining = 100 - pkErrorsAccum.size();
                    pkErrorsAccum.addAll(pkValidation.errors().stream().limit(remaining).toList());
                  }
                  return;
                }
                BiConsumer<Integer, Integer> wrapped =
                    (processed, total) -> {
                      int globalProcessed = base + processed;
                      int pct =
                          40 + (int) ((globalProcessed / (double) totalRowsForProgress) * 60);
                      asyncJobService.updateProgress(
                          jobId,
                          "INSERTING",
                          Math.min(pct, 100),
                          "Upserting data...",
                          Map.of("totalRows", totalRowsFinal, "processedRows", globalProcessed));
                    };
                dataTableRowService.insertStagingBatchWithProgress(
                    stagingTableFinal, columnNames, rowMapsBatch, wrapped);
                outerProcessed[0] = base + rowMapsBatch.size();
              }
              case REPLACE -> {
                BiConsumer<Integer, Integer> wrapped =
                    (processed, total) -> {
                      int globalProcessed = base + processed;
                      int pct =
                          40 + (int) ((globalProcessed / (double) totalRowsForProgress) * 60);
                      asyncJobService.updateProgress(
                          jobId,
                          "INSERTING",
                          Math.min(pct, 100),
                          "Replacing table...",
                          Map.of("totalRows", totalRowsFinal, "processedRows", globalProcessed));
                    };
                if (!pkColumns.isEmpty()) {
                  dataTableRowService.insertStagingBatchWithProgress(
                      stagingTableFinal, columnNames, rowMapsBatch, wrapped);
                } else {
                  // PK가 없으면 unique index가 없어 dedup이 불필요 — target에 직접 적재.
                  // lazy truncate: 유효 행이 처음 나올 때 1회만 truncate. 여기 도달했다는 것 자체가
                  // 전량 검증을 통과했다는 뜻이므로(fail-fast), 기존의 "전량 무효 시 보존" lazy 방어는
                  // 이제 도달하지 않지만 안전하게 유지한다.
                  if (!replaceNoPkTruncated[0]) {
                    dataTableRowService.truncateTable(dataset.tableName());
                    replaceNoPkTruncated[0] = true;
                  }
                  dataTableRowService.insertBatchWithProgress(
                      dataset.tableName(), columnNames, rowMapsBatch, wrapped);
                }
                outerProcessed[0] = base + rowMapsBatch.size();
              }
            }
          };

      // REPLACE(PK 無)만 truncate+insert 원자성이 필요하므로 삽입 스트림 전체를 트랜잭션으로 감싼다.
      // 나머지 모드는 staging(별도 영구 테이블)에 배치 커밋하며 스트리밍하므로 트랜잭션이 불필요하다
      // (하나의 거대한 트랜잭션으로 커넥션을 점유하면 스트리밍의 이점이 사라진다).
      if (importMode == ImportMode.REPLACE && pkColumns.isEmpty()) {
        transactionTemplate.executeWithoutResult(
            status -> {
              try {
                fileParserService.parseStreaming(
                    path, fileTypeLower, parseOptionsFinal, BATCH_SIZE, insertBatch);
              } catch (Exception e) {
                throw (e instanceof RuntimeException re) ? re : new RuntimeException(e);
              }
            });
      } else {
        fileParserService.parseStreaming(
            path, fileTypeLower, parseOptionsFinal, BATCH_SIZE, insertBatch);
      }

      // UPSERT에서 NULL PK가 발견되면 promote 없이 실패 처리(기존과 동일 메시지/감사 로그).
      if (importMode == ImportMode.UPSERT && pkNullFound[0]) {
        asyncJobService.failJob(jobId, "Primary key validation failed: NULL values in PK columns");
        String pkErrorJson = objectMapper.writeValueAsString(Map.of("errors", pkErrorsAccum));
        auditLogService.log(
            userId,
            username,
            "IMPORT",
            "dataset",
            String.valueOf(datasetId),
            "File import: " + fileName,
            ipAddress,
            userAgent,
            "FAILURE",
            "Primary key columns contain NULL values",
            Map.of(
                "fileName",
                fileName,
                "fileSize",
                fileSize,
                "fileType",
                fileType,
                "importMode",
                importModeName,
                "errorDetails",
                pkErrorJson));
        return;
      }

      // 스트림 중 staging에 적재된 내용을 target으로 promote (UPSERT / REPLACE with PK)
      if (validCountFinal > 0) {
        if (importMode == ImportMode.UPSERT) {
          log.info("UPSERT mode: promoting staging to target on PK columns {}", pkColumns);
          DataTableRowService.UpsertResult upsertResult =
              dataTableRowService.promoteStagingToUpsert(
                  stagingTableFinal, dataset.tableName(), columnNames, pkColumns, null);
          log.info(
              "UPSERT mode: promoted — inserted={}, updated={}",
              upsertResult.inserted(),
              upsertResult.updated());
        } else if (importMode == ImportMode.REPLACE && !pkColumns.isEmpty()) {
          log.info("REPLACE mode: promoting staging to target (truncate + insert) atomically");
          transactionTemplate.executeWithoutResult(
              status ->
                  dataTableRowService.promoteStagingToReplace(
                      stagingTableFinal, dataset.tableName(), columnNames, pkColumns));
        }
      }

      // Log success to audit_log
      String errorJson = null;
      List<?> errorsForJson = hasMappings ? detailErrorsAccum : simpleErrorsAccum;
      if (!errorsForJson.isEmpty()) {
        errorJson = objectMapper.writeValueAsString(Map.of("errors", errorsForJson));
      }

      Map<String, Object> metadata = new HashMap<>();
      metadata.put("fileName", fileName);
      metadata.put("fileSize", fileSize);
      metadata.put("fileType", fileType);
      metadata.put("importMode", importMode.name());
      metadata.put("totalRows", totalRowsFinal);
      metadata.put("successRows", validCountFinal);
      metadata.put("errorRows", errorCountFinal);
      if (errorJson != null) {
        metadata.put("errorDetails", errorJson);
      }

      asyncJobService.completeJob(
          jobId,
          Map.of(
              "totalRows", totalRowsFinal,
              "successRows", validCountFinal,
              "errorRows", errorCountFinal));

      notificationService.notifyImportCompleted(
          userId, datasetId, datasetNameForNotification, true);

      auditLogService.log(
          userId,
          username,
          "IMPORT",
          "dataset",
          String.valueOf(datasetId),
          "File import: " + fileName,
          ipAddress,
          userAgent,
          "SUCCESS",
          null,
          metadata);

      log.info(
          "Import completed for dataset {}. Valid: {}, Errors: {}",
          datasetId,
          validCountFinal,
          errorCountFinal);

    } catch (Exception e) {
      log.error("Import failed for dataset {}", datasetId, e);

      String userMessage = toUserFriendlyMessage(e);
      asyncJobService.failJob(jobId, userMessage);

      notificationService.notifyImportCompleted(
          userId, datasetId, datasetNameForNotification, false);

      Map<String, Object> metadata =
          Map.of(
              "fileName",
              fileName,
              "fileSize",
              fileSize,
              "fileType",
              fileType,
              "error",
              userMessage);

      auditLogService.log(
          userId,
          username,
          "IMPORT",
          "dataset",
          String.valueOf(datasetId),
          "File import: " + fileName,
          ipAddress,
          userAgent,
          "FAILURE",
          userMessage,
          metadata);

      // Jobrunr 재시도를 막기 위해 예외를 재투척하지 않고 여기서 종료.
      // 재투척 시 Jobrunr이 자동 재시도하여 이미 삭제된 임시 파일에 접근하거나
      // 감사 로그가 중복 기록되는 문제(#168)가 발생한다.
    } finally {
      // Clean up temp files + staging table (성공/실패 무관하게 항상 정리)
      try {
        Files.deleteIfExists(Path.of(filePath));
        if (mappingsPath != null && !mappingsPath.isEmpty()) {
          Files.deleteIfExists(Path.of(mappingsPath));
        }
        if (parseOptsPath != null && !parseOptsPath.isEmpty()) {
          Files.deleteIfExists(Path.of(parseOptsPath));
        }
        if (stagingTable != null) {
          dataTableRowService.dropStagingTable(stagingTable);
        }
      } catch (Exception e) {
        log.warn("Failed to delete temp file: {}", filePath, e);
      }
    }
  }

  public List<ImportResponse> getImportsByDatasetId(Long datasetId) {
    datasetRepository
        .findById(datasetId)
        .orElseThrow(() -> new IllegalArgumentException("Dataset not found: " + datasetId));

    List<AuditLogResponse> auditLogs =
        auditLogService.findByResource("IMPORT", "dataset", String.valueOf(datasetId));
    return auditLogs.stream().map(this::mapToImportResponse).toList();
  }

  public ImportResponse getImportById(Long datasetId, Long importId) {
    datasetRepository
        .findById(datasetId)
        .orElseThrow(() -> new IllegalArgumentException("Dataset not found: " + datasetId));

    AuditLogResponse auditLog =
        auditLogService
            .findById(importId)
            .orElseThrow(() -> new IllegalArgumentException("Import not found: " + importId));

    if (!String.valueOf(datasetId).equals(auditLog.resourceId())) {
      throw new IllegalArgumentException("Import does not belong to this dataset");
    }

    return mapToImportResponse(auditLog);
  }

  private ImportResponse mapToImportResponse(AuditLogResponse auditLog) {
    Map<String, Object> meta = parseMetadata(auditLog.metadata());

    String status =
        switch (auditLog.result()) {
          case "SUCCESS" -> "COMPLETED";
          case "FAILURE" -> "FAILED";
          default -> auditLog.result();
        };

    Long datasetId = null;
    if (auditLog.resourceId() != null) {
      try {
        datasetId = Long.parseLong(auditLog.resourceId());
      } catch (NumberFormatException ignored) {
        // Value is not a valid number; skip numeric detection
      }
    }

    return new ImportResponse(
        auditLog.id(),
        datasetId,
        getMetaString(meta, "fileName"),
        getMetaLong(meta, "fileSize"),
        getMetaString(meta, "fileType"),
        status,
        getMetaInteger(meta, "totalRows"),
        getMetaInteger(meta, "successRows"),
        getMetaInteger(meta, "errorRows"),
        meta.get("errorDetails"),
        auditLog.errorMessage(),
        auditLog.username(),
        null,
        auditLog.actionTime(),
        auditLog.actionTime());
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parseMetadata(Object metadata) {
    if (metadata == null) {
      return Map.of();
    }
    try {
      String json;
      if (metadata instanceof JSONB jsonb) {
        json = jsonb.data();
      } else {
        json = metadata.toString();
      }
      return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      log.warn("Failed to parse audit log metadata", e);
      return Map.of();
    }
  }

  private String getMetaString(Map<String, Object> meta, String key) {
    Object value = meta.get(key);
    return value != null ? value.toString() : null;
  }

  private Long getMetaLong(Map<String, Object> meta, String key) {
    Object value = meta.get(key);
    if (value instanceof Number n) return n.longValue();
    if (value != null) {
      try {
        return Long.parseLong(value.toString());
      } catch (NumberFormatException ignored) {
        // Value is not parseable as Long; try next type
      }
    }
    return null;
  }

  private Integer getMetaInteger(Map<String, Object> meta, String key) {
    Object value = meta.get(key);
    if (value instanceof Number n) return n.intValue();
    if (value != null) {
      try {
        return Integer.parseInt(value.toString());
      } catch (NumberFormatException ignored) {
        // Value is not parseable as Integer; fall through to return null
      }
    }
    return null;
  }

  private String getFileType(String filename) {
    int lastDot = filename.lastIndexOf('.');
    if (lastDot > 0 && lastDot < filename.length() - 1) {
      return filename.substring(lastDot + 1).toLowerCase();
    }
    return "";
  }

  private String toUserFriendlyMessage(Exception e) {
    String msg = e.getMessage() != null ? e.getMessage() : "";

    // Duplicate key (data table or async_job)
    if (msg.contains("duplicate key value violates unique constraint")) {
      if (msg.contains("idx_async_job_active_unique")) {
        return "이미 진행 중인 임포트가 있습니다. 완료 후 다시 시도해주세요.";
      }
      return "중복 데이터가 존재합니다. '업서트(UPSERT)' 모드를 사용하면 기존 데이터를 업데이트할 수 있습니다.";
    }

    // File not found / upload interrupted
    if (e instanceof java.nio.file.NoSuchFileException
        || msg.contains("NoSuchFileException")
        || msg.contains("Stream ended unexpectedly")) {
      return "파일 업로드가 중간에 실패했습니다. 다시 시도해주세요.";
    }

    // CSV/XLSX/XLSB parse errors
    if (msg.contains("ArrayIndexOutOfBoundsException") || msg.contains("Cannot parse")) {
      return "파일 형식이 올바르지 않습니다. CSV, XLSX, 또는 XLSB 파일을 확인해주세요.";
    }

    // Connection / timeout
    if (msg.contains("Connection") || msg.contains("Timeout") || msg.contains("timed out")) {
      return "데이터베이스 연결에 문제가 발생했습니다. 잠시 후 다시 시도해주세요.";
    }

    // Generic fallback — do not expose raw exception
    return "임포트 처리 중 오류가 발생했습니다. 관리자에게 문의해주세요.";
  }
}
