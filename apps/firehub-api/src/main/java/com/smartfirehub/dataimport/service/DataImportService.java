package com.smartfirehub.dataimport.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.audit.dto.AuditLogResponse;
import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.dataimport.dto.*;
import com.smartfirehub.dataimport.exception.ConcurrentImportException;
import com.smartfirehub.dataimport.exception.FileUnreadableException;
import com.smartfirehub.dataimport.exception.ImportNotFoundException;
import com.smartfirehub.dataimport.exception.ImportProcessingException;
import com.smartfirehub.dataimport.exception.InvalidFileHeaderException;
import com.smartfirehub.dataimport.exception.UnsupportedFileTypeException;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.DatasetResponse;
import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.service.DataExceptionTranslator;
import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.transaction.AfterCommitRunner;
import com.smartfirehub.job.service.AsyncJobService;
import com.smartfirehub.notification.service.NotificationService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
   * 스트리밍 검증(Pass1) 중 fail-fast 중단을 위한 내부 신호용 예외. 오류가 하나라도 나오면 즉시 던져 부분 적재를 방지한다 (Task2: 검증→삽입 2단계
   * 분리). RuntimeException을 상속해 REPLACE(PK 無) 트랜잭션 람다의 rethrow 분기를 그대로 통과하고 트랜잭션 롤백을 유발한다. 스택트레이스는
   * 불필요하므로 생성 비용을 없앤다.
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

  // 메서드 레벨 트랜잭션을 두지 않는다 — 리포지토리가 자기 트랜잭션을 열어 GUC 를 보장하고,
  // 여기서 감싸면 파일 전송/외부 저장이 DB 커넥션을 잡은 채로 진행된다.
  public ImportPreviewResponse previewImport(Long datasetId, MultipartFile file) throws Exception {
    return previewImport(datasetId, file, ParseOptions.defaults());
  }

  // 메서드 레벨 트랜잭션을 두지 않는다 — 리포지토리가 자기 트랜잭션을 열어 GUC 를 보장하고,
  // 여기서 감싸면 파일 전송/외부 저장이 DB 커넥션을 잡은 채로 진행된다.
  public ImportPreviewResponse previewImport(
      Long datasetId, MultipartFile file, ParseOptions parseOptions) throws Exception {
    return previewImport(datasetId, file, parseOptions, false);
  }

  /**
   * 데이터셋 임포트 미리보기. {@code partial=true}는 프론트가 대용량 CSV의 앞부분만 잘라 전송한 경우로, 파일 끝이 잘려 있다. 이때 전체
   * 행수(countRows)는 잘린 마지막 행이 열린 따옴표로 끝나면 예외를 던질 수 있고 값도 부정확하므로 계산을 건너뛴다(totalRows=-1). totalRows는
   * 미리보기 UI에서 사용되지 않으며, 실제 전체 행수는 검증/임포트 시점에 전체 파일로 확정된다.
   */
  // 메서드 레벨 트랜잭션을 두지 않는다 — 리포지토리가 자기 트랜잭션을 열어 GUC 를 보장하고,
  // 여기서 감싸면 파일 전송/외부 저장이 DB 커넥션을 잡은 채로 진행된다.
  public ImportPreviewResponse previewImport(
      Long datasetId, MultipartFile file, ParseOptions parseOptions, boolean partial)
      throws Exception {
    // Validate dataset exists
    datasetRepository
        .findById(datasetId)
        .orElseThrow(() -> new IllegalArgumentException(DatasetNotFoundException.MESSAGE));

    // Validate file type
    String originalFilename = file.getOriginalFilename();
    if (originalFilename == null || originalFilename.isEmpty()) {
      throw new UnsupportedFileTypeException(UnsupportedFileTypeException.FILE_NAME_REQUIRED);
    }

    String fileType = getFileType(originalFilename);
    // XLS(레거시 바이너리 포맷)/XLSB(바이너리 XLSX)도 XLSX와 동일한 스트리밍 파서로 처리 가능하므로 허용
    if (!fileType.equals("csv")
        && !fileType.equals("xlsx")
        && !fileType.equals("xls")
        && !fileType.equals("xlsb")) {
      throw new UnsupportedFileTypeException(UnsupportedFileTypeException.UNSUPPORTED_EXTENSION);
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

      // 엑셀이면 시트 목록과 읽을 시트(기본: 첫 번째 보이는 시트)를 먼저 정한다(WD-253). 지정한 시트가 없거나 보이는 시트가 없으면 여기서
      // 400 으로 끝난다. CSV 는 SheetSelection.NONE(둘 다 null). 아래 헤더·샘플·행 수도 같은 parseOptions(sheetName)로
      // 같은 규칙을 거쳐 이 시트를 읽는다.
      ExcelStreamingParser.SheetSelection sheetSelection =
          fileParserService.describeSheets(tempFile, fileType, parseOptions);

      List<String> headers = fileParserService.parseHeaders(tempFile, fileType, parseOptions);

      // 엑셀 오류 셀은 파서가 검증용 표식으로 넘긴다 — 미리보기에는 엑셀이 보여 주는 모양(#DIV/0!)으로 바꿔 보인다(WD-252).
      List<Map<String, String>> sampleRows =
          fileParserService.parseSampleRows(tempFile, fileType, 5, parseOptions).stream()
              .map(DataImportService::toDisplayRow)
              .toList();

      // partial 미리보기는 파일 끝이 잘려 있어 전체 행수를 셀 수 없다(위 Javadoc 참고).
      int totalRows = partial ? -1 : fileParserService.countRows(tempFile, fileType, parseOptions);

      // Get dataset columns
      List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(datasetId);

      // Suggest mappings
      List<ColumnMappingDto> suggestedMappings =
          columnMappingService.suggestMappings(headers, columns);

      return new ImportPreviewResponse(
          headers,
          sampleRows,
          suggestedMappings,
          totalRows,
          sheetSelection.selectedName(),
          sheetSelection.sheets());
    } finally {
      // 미리보기 완료 후 임시 파일 정리 — 누적 방지
      Files.deleteIfExists(tempFile);
    }
  }

  /** 미리보기 행의 엑셀 오류 셀 표식을 표시 문자열로 바꾼다. 순서·null 값은 그대로 둔다. */
  private static Map<String, String> toDisplayRow(Map<String, String> row) {
    Map<String, String> display = new LinkedHashMap<>();
    row.forEach((k, v) -> display.put(k, LegacyExcelDataFormatter.toDisplay(v)));
    return display;
  }

  // 메서드 레벨 트랜잭션을 두지 않는다 — 리포지토리가 자기 트랜잭션을 열어 GUC 를 보장하고,
  // 여기서 감싸면 파일 전송/외부 저장이 DB 커넥션을 잡은 채로 진행된다.
  public ImportValidateResponse validateImport(
      Long datasetId, MultipartFile file, List<ColumnMappingEntry> mappings) throws Exception {
    return validateImport(datasetId, file, mappings, ParseOptions.defaults());
  }

  // 메서드 레벨 트랜잭션을 두지 않는다 — 리포지토리가 자기 트랜잭션을 열어 GUC 를 보장하고,
  // 여기서 감싸면 파일 전송/외부 저장이 DB 커넥션을 잡은 채로 진행된다.
  public ImportValidateResponse validateImport(
      Long datasetId,
      MultipartFile file,
      List<ColumnMappingEntry> mappings,
      ParseOptions parseOptions)
      throws Exception {
    // Validate dataset exists
    datasetRepository
        .findById(datasetId)
        .orElseThrow(() -> new IllegalArgumentException(DatasetNotFoundException.MESSAGE));

    // Validate file type
    String originalFilename = file.getOriginalFilename();
    if (originalFilename == null || originalFilename.isEmpty()) {
      throw new UnsupportedFileTypeException(UnsupportedFileTypeException.FILE_NAME_REQUIRED);
    }

    String fileType = getFileType(originalFilename);
    // XLS(레거시 바이너리 포맷)/XLSB(바이너리 XLSX)도 XLSX와 동일한 스트리밍 파서로 처리 가능하므로 허용
    if (!fileType.equals("csv")
        && !fileType.equals("xlsx")
        && !fileType.equals("xls")
        && !fileType.equals("xlsb")) {
      throw new UnsupportedFileTypeException(UnsupportedFileTypeException.UNSUPPORTED_EXTENSION);
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

      // 이름이 같은 빈 열 여럿 중 하나를 매핑했으면 값이 서로 덮어써지므로 400 으로 거절한다(WD-369).
      requireMappableBlankColumns(tempFile, fileType, parseOptions, mappings);

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

  // 메서드 레벨 트랜잭션을 두지 않는다 — 리포지토리가 자기 트랜잭션을 열어 GUC 를 보장하고,
  // 여기서 감싸면 파일 전송/외부 저장이 DB 커넥션을 잡은 채로 진행된다.
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

  // 메서드 레벨 트랜잭션을 두지 않는다 — 리포지토리가 자기 트랜잭션을 열어 GUC 를 보장하고,
  // 여기서 감싸면 파일 전송/외부 저장이 DB 커넥션을 잡은 채로 진행된다.
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

  // 메서드 레벨 트랜잭션을 두지 않는다 — 리포지토리가 자기 트랜잭션을 열어 GUC 를 보장하고,
  // 여기서 감싸면 파일 전송/외부 저장이 DB 커넥션을 잡은 채로 진행된다.
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
        .orElseThrow(() -> new IllegalArgumentException(DatasetNotFoundException.MESSAGE));

    // Validate file type
    String originalFilename = file.getOriginalFilename();
    if (originalFilename == null || originalFilename.isEmpty()) {
      throw new UnsupportedFileTypeException(UnsupportedFileTypeException.FILE_NAME_REQUIRED);
    }

    String fileType = getFileType(originalFilename);
    // XLS(레거시 바이너리 포맷)/XLSB(바이너리 XLSX)도 XLSX와 동일한 스트리밍 파서로 처리 가능하므로 허용
    if (!fileType.equals("csv")
        && !fileType.equals("xlsx")
        && !fileType.equals("xls")
        && !fileType.equals("xlsb")) {
      throw new UnsupportedFileTypeException(UnsupportedFileTypeException.UNSUPPORTED_EXTENSION);
    }

    long fileSize = file.getSize();
    String upperFileType = fileType.toUpperCase();
    String safeFileName =
        originalFilename.length() > 255 ? originalFilename.substring(0, 255) : originalFilename;

    // 이미 진행 중인 가져오기가 있으면 업로드 전체를 디스크에 쓰기 전에 바로 409 로 끝낸다. 여기서 통과해도 동시에 들어온 요청은
    // 아래 createJob 의 partial unique index 가 최종으로 막는다(이 확인은 큰 파일을 헛되이 저장하지 않으려는 빠른 길일 뿐이다).
    String datasetKey = String.valueOf(datasetId);
    if (asyncJobService.hasActiveJob("IMPORT", "dataset", datasetKey)) {
      throw new ConcurrentImportException(CONCURRENT_IMPORT_MESSAGE);
    }

    // Save file to temp location for Jobrunr processing
    // 잡 행을 만들기 전에 저장한다 — 아래 시트 사전 확인(WD-253)이 파일을 읽어야 하고, 잡 행을 만든 뒤 400 으로 끝나면
    // 활성 잡이 남아 이 데이터셋의 다음 가져오기가 모두 409 로 막힌다.
    Path tempDir = Path.of(System.getProperty("java.io.tmpdir"), "firehub-imports");
    Files.createDirectories(tempDir);
    Path tempFile = Files.createTempFile(tempDir, "import-", "." + fileType);

    // 저장·시트 확인·잡 생성이 실패하면 잡이 없으니 저장한 파일은 쓰일 일이 없다 — 아래 catch 에서 지운다(업로드 크기만 한 파일이 tmp 에
    // 쌓이지 않게).
    String jobId;
    try {
      file.transferTo(tempFile.toFile());

      // 엑셀이면 잡을 만들기 전에 읽을 시트를 정한다(WD-253) — 시트를 지정했든(없는 이름) 안 했든(보이는 시트 없음) 미리보기와 같은
      // 한국어 400 으로 바로 돌려준다. 잡으로 넘기면 「진행 중」 잡이 잠깐 생겼다가 실패로 끝나 화면·AI 가 실패 이유를 잡 상태에서 따로
      // 찾아야 한다. 워크북 머리만 읽어 싸다.
      if (!"csv".equals(fileType)) {
        fileParserService.describeSheets(tempFile, fileType, parseOptions);
      }

      // 같은 규칙을 잡을 만들기 전에 확인한다(WD-369) — 잡으로 넘기면 「진행 중」 잡이 생겼다가 실패로 끝나 사유를 잡 상태에서 따로 찾아야
      // 한다. 여기서 던지면 아래 catch 가 저장한 파일을 지운다.
      requireMappableBlankColumns(tempFile, fileType, parseOptions, mappings);

      // Create async job — partial unique index enforces one active import per dataset atomically
      jobId =
          asyncJobService.createJob(
              "IMPORT",
              "dataset",
              datasetKey,
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
      Files.deleteIfExists(tempFile);
      // 409 응답 메시지. 프론트가 자체 한국어 문구를 쓰긴 하지만, API를 직접 쓰는 경로(ai-agent·외부 호출)에도
      // 그대로 노출되므로 저장소의 한국어 예외 메시지 관행에 맞춘다(#309).
      throw new ConcurrentImportException(CONCURRENT_IMPORT_MESSAGE);
    } catch (Exception e) {
      Files.deleteIfExists(tempFile);
      throw e;
    }

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
    // 배경 잡에는 요청 스코프의 테넌트가 승계되지 않으므로 페이로드에 실어 보낸다.
    long tenantId = TenantContext.require();

    // Enqueue Jobrunr job
    // JobRunr StorageProvider 는 DataSource 에서 자기 커넥션을 직접 얻어(DataSourceUtils 를
    // 거치지 않음) 이 메서드의 스프링 트랜잭션에 합류하지 않는다. 즉 방금 만든 async_job 행이 이
    // 트랜잭션의 커밋 전에는 다른 커넥션(백그라운드 워커)에 보이지 않을 수 있는데, 워커가 커밋보다
    // 먼저 잡을 집어가면 processImport의 asyncJobService.updateProgress(jobId, ...)가 존재하지
    // 않는 잡을 갱신하려다 조용히 실패한다. T3 이전에는 importFile()에 트랜잭션이 없어 createJob()이
    // 즉시 커밋됐으므로 이 경합이 없었다 — 트랜잭션 경계를 추가하며 새로 생긴 회귀이므로 커밋 이후로
    // enqueue 를 미룬다.
    AfterCommitRunner.run(
        () ->
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
                        importModeName,
                        tenantId)));

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
      String importModeName,
      long tenantId) {
    // 잡 스레드에는 요청 컨텍스트가 없다. RLS가 걸린 dataset/data 테이블을 읽고 쓰려면 스코프가 필요하다.
    // 손으로 set → try → finally clear 하지 않고 runScoped 로 감싼다 — 본문이 어디서 던지든 진입 전
    // 상태로 되돌아가므로(진입 전이 null 이면 clear 와 동일), "컨텍스트가 새지 않게 하는 규칙"을 사람이
    // 기억하는 대신 구조가 보장한다(TenantContext#runScoped 참조).
    // 이 메서드에 @Transactional을 붙이면 안 된다 — 본문 시작 전에 트랜잭션이 열려 이미 늦는다.
    TenantContext.runScoped(
        tenantId,
        () ->
            processImportScoped(
                jobId,
                datasetId,
                filePath,
                mappingsPath,
                parseOptsPath,
                fileName,
                fileSize,
                fileType,
                userId,
                username,
                ipAddress,
                userAgent,
                importModeName));
  }

  /**
   * 임포트 본문 — 항상 테넌트 스코프 <b>안에서</b> 호출된다({@link #processImport} 가 유일한 호출자).
   *
   * <p>스코프 수립/해제를 본문에서 분리한 이유: 본문이 500줄에 걸쳐 여러 트랜잭션과 재시도 억제 로직을 다루는데, 그 안에 컨텍스트 정리 책임까지 섞이면 "어느 문장이
   * try 안이어야 하는가" 를 사람이 계속 따져야 한다. 스코프는 호출부의 {@code runScoped} 가 구조로 보장한다.
   */
  private void processImportScoped(
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
    // UPSERT/REPLACE(PK 有)가 사용하는 staging 테이블명. finally에서 성공/실패 무관하게 항상 정리한다.
    String stagingTable = null;
    // 알림 문구용 데이터셋 이름. 조회 자체가 실패해도 알림은 나가야 하므로 id 로 초기화한다.
    String datasetNameForNotification = String.valueOf(datasetId);

    try {
      datasetNameForNotification =
          datasetRepository
              .findById(datasetId)
              .map(d -> d.name())
              .orElse(String.valueOf(datasetId));
      asyncJobService.updateProgress(
          jobId, "PARSING", 10, "Parsing file...", Map.of("totalRows", 0, "processedRows", 0));

      // Load parse options
      // 옵션 파일을 읽지 못하면 기본값으로 이어 가지 않고 잡을 실패시킨다(WD-253) — 기본값으로 읽으면 사용자가 고른 시트·인코딩·구분자
      // 대신 다른 데이터가 조용히 적재된다. 아래 catch 경로가 실패 처리·알림·감사 로그를 남긴다. 경로가 비었으면(옵션 없이 시작) 기본값이 맞다.
      ParseOptions parseOptions = ParseOptions.defaults();
      if (parseOptsPath != null && !parseOptsPath.isEmpty()) {
        try {
          String optsJson = Files.readString(Path.of(parseOptsPath));
          parseOptions = objectMapper.readValue(optsJson, ParseOptions.class);
        } catch (Exception e) {
          throw new ImportProcessingException(PARSE_OPTIONS_UNREADABLE, e);
        }
      }

      Path path = Path.of(filePath);
      String fileTypeLower = fileType.toLowerCase();
      // 람다(transactionTemplate)에서 캡처하려면 effectively-final이어야 하므로 확정 값을 복사한다.
      final ParseOptions parseOptionsFinal = parseOptions;

      // Task2: 검증(Pass1)이 전량 스트리밍하며 행수를 세므로, 별도의 countRows() pre-scan은 더 이상 필요 없다
      // (기존에는 진행률 분모를 위해 파일을 한 번 더 스캔했으나, Pass1이 그 역할을 겸한다).
      asyncJobService.updateProgress(
          jobId,
          "VALIDATING",
          20,
          "Validating data...",
          Map.of("totalRows", 0, "processedRows", 0));

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

      // 시작 요청(importFile)이 이미 거르지만, 잡 페이로드로 바로 들어온 매핑도 같은 규칙을 따르게 한 번 더 확인한다(WD-369). 던지면 아래
      // catch 가 InvalidFileHeaderException 의 한국어 메시지를 실패 사유로 남긴다.
      requireMappableBlankColumns(path, fileTypeLower, parseOptionsFinal, mappings);

      ImportMode importMode =
          ImportMode.valueOf(
              importModeName != null && !importModeName.isEmpty()
                  ? importModeName
                  : ImportMode.APPEND.name());

      DatasetResponse dataset = datasetRepository.findById(datasetId).orElseThrow();
      List<String> columnNames = columns.stream().map(DatasetColumnResponse::columnName).toList();
      // 대상 테이블에 바로 넣는 경로(APPEND·PK 없는 REPLACE)는 컬럼 타입을 넘겨야 GEOMETRY 가
      // ST_GeomFromGeoJSON(?) 로 들어간다 — 없으면 GeoJSON 문자열이 varchar 로 묶여 PG 가 거부한다(WD-160).
      // staging 경로는 staging 테이블을 introspect 해 같은 placeholder 를 이미 쓴다.
      Map<String, String> columnTypes = new HashMap<>();
      for (DatasetColumnResponse col : columns) {
        columnTypes.put(col.columnName(), col.dataType());
      }
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
      // 열마다 일·월 순서(일/월/연도 vs 월/일/연도)를 파일 전체 기준으로 하나로 정하는 상태(WD-246). Pass1 이 전량을 훑으며
      // 근거 값으로 순서를 정하고 섞인 열을 거부한 뒤, Pass2 가 같은 상태로 모든 행을 해석한다 — 파일을 더 읽지 않는다.
      DataValidationService.DateOrderContext dateOrders =
          new DataValidationService.DateOrderContext();

      // Pass1 콜백: 검증 전용. 삽입하지 않고 카운트/오류만 누적하며, 첫 오류가 나오는 배치에서 fail-fast로
      // 중단한다(부분 적재 방지). rowIndexBase는 이전까지 누적 처리된 행 수(전역 오프셋)로, 오류 rowNumber가
      // 배치 로컬이 아닌 파일 전역 기준이 되게 한다(validateImport와 동일 패턴).
      Consumer<List<Map<String, String>>> validateBatch =
          batch -> {
            if (hasMappings) {
              DataValidationService.ValidationResultWithDetails vr =
                  validationService.validateWithMapping(
                      batch, columns, mappingsFinal, processedSoFar[0], dateOrders);
              validCount[0] += vr.validCount();
              errorCount[0] += vr.errorCount();
              if (detailErrorsAccum.size() < 100) {
                int remaining = 100 - detailErrorsAccum.size();
                detailErrorsAccum.addAll(vr.errors().stream().limit(remaining).toList());
              }
            } else {
              DataValidationService.ValidationResult vr =
                  validationService.validate(batch, columns, processedSoFar[0], dateOrders);
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
      // 파서가 건너뛴 빈 행 수(WD-255)는 전량을 읽은 Pass1 값을 결과에 쓴다 — Pass2 도 같은 행을 건너뛴다.
      boolean validationPassed;
      int skippedRowsFinal = 0;
      try {
        skippedRowsFinal =
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
        // 한국어 UI와 문구를 맞춘다(#309). 행/컬럼/값/사유의 정보 구조는 그대로 유지해 사용자가 어디를 고칠지 알 수 있게 한다.
        String sampleError =
            hasMappings
                ? (detailErrorsAccum.isEmpty()
                    ? null
                    : detailErrorsAccum.get(0).rowNumber()
                        + "행 '"
                        + detailErrorsAccum.get(0).columnName()
                        + "' 컬럼 - "
                        + detailErrorsAccum.get(0).error())
                : (simpleErrorsAccum.isEmpty() ? null : simpleErrorsAccum.get(0));
        String failMessage =
            "검증 실패로 한 행도 적재되지 않았습니다 (오류 "
                + errorCountFinal
                + "건)"
                + (sampleError != null ? " — 예: " + sampleError : "");

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
            "Import failed: validation error(s) found for dataset {} — {}", datasetId, failMessage);
        // 감사 로그 뒤·return 직전 — 순서 규칙은 failImport 참조
        failImport(jobId, failMessage, userId, datasetId, datasetNameForNotification);
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
            List<List<Object>> rows =
                validationService.toRows(batch, columns, mappingsFinal, dateOrders);
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
                      int pct = 40 + (int) ((globalProcessed / (double) totalRowsForProgress) * 60);
                      asyncJobService.updateProgress(
                          jobId,
                          "INSERTING",
                          Math.min(pct, 100),
                          "Inserting data...",
                          Map.of("totalRows", totalRowsFinal, "processedRows", globalProcessed));
                    };
                dataTableRowService.insertBatchWithProgress(
                    dataset.tableName(), columnNames, rowMapsBatch, wrapped, columnTypes);
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
                      int pct = 40 + (int) ((globalProcessed / (double) totalRowsForProgress) * 60);
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
                      int pct = 40 + (int) ((globalProcessed / (double) totalRowsForProgress) * 60);
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
                      dataset.tableName(), columnNames, rowMapsBatch, wrapped, columnTypes);
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
        // 감사 로그 뒤·return 직전 — 순서 규칙은 failImport 참조
        failImport(
            jobId,
            "Primary key validation failed: NULL values in PK columns",
            userId,
            datasetId,
            datasetNameForNotification);
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
      metadata.put("skippedRows", skippedRowsFinal); // WD-255: 건너뛴 빈 행 수
      if (errorJson != null) {
        metadata.put("errorDetails", errorJson);
      }

      asyncJobService.completeJob(
          jobId,
          Map.of(
              "totalRows", totalRowsFinal,
              "successRows", validCountFinal,
              "errorRows", errorCountFinal,
              "skippedRows", skippedRowsFinal));

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
      // 예외 경로는 기존대로 감사 로그보다 먼저 실패 처리·알림을 한다(감사 로그가 던져도 알림은 이미 1번 나감).
      failImport(jobId, userMessage, userId, datasetId, datasetNameForNotification);

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
      // 여기서 TenantContext 를 지우지 않는다 — 스코프 해제는 호출부의 runScoped 가 담당한다.
      // 이 정리(임시 파일·staging DROP)는 RLS 대상 테이블을 건드리므로 스코프 안이어야 한다.
    }
  }

  /**
   * 임포트 잡을 실패(FAILED)로 끝내고 요청자에게 「임포트 실패」 알림(토스트·알림함)을 보낸다. 임포트가 실패로 끝나는 모든 지점은 이 메서드 하나를 거친다 — 잡
   * 상태와 알림을 따로 부르던 시절, WD-96 fail-fast 전환으로 생긴 return 경로(검증 실패·기본 키 빈 값)에서 알림만 빠졌던 결함(WD-102)이 실패
   * 지점이 늘어날 때 되풀이되지 않게 한다.
   *
   * <p>호출 순서 규칙(같은 실패에 알림이 정확히 한 번 가도록):
   *
   * <ul>
   *   <li>return 으로 끝나는 실패 경로는 감사 로그까지 남긴 <b>뒤</b>, return 직전에 부른다. 그 앞의 직렬화·감사 로그 저장이 던지면 catch 가
   *       이 메서드를 대신 한 번 부르므로, 여기를 먼저 부르면 알림이 두 번 간다.
   *   <li>catch 경로는 감사 로그 <b>앞</b>에서 부른다(기존 동작). 감사 로그가 던져도 실패 처리·알림은 이미 끝나 있다.
   * </ul>
   *
   * @param failMessage 잡에 남길 실패 사유(진행 화면에 그대로 보인다)
   * @param datasetName 알림 문구에 넣을 데이터셋 이름
   */
  private void failImport(
      String jobId, String failMessage, Long userId, Long datasetId, String datasetName) {
    asyncJobService.failJob(jobId, failMessage);
    notificationService.notifyImportCompleted(userId, datasetId, datasetName, false);
  }

  public List<ImportResponse> getImportsByDatasetId(Long datasetId) {
    datasetRepository
        .findById(datasetId)
        .orElseThrow(() -> new IllegalArgumentException(DatasetNotFoundException.MESSAGE));

    List<AuditLogResponse> auditLogs =
        auditLogService.findByResource("IMPORT", "dataset", String.valueOf(datasetId));
    return auditLogs.stream().map(this::mapToImportResponse).toList();
  }

  public ImportResponse getImportById(Long datasetId, Long importId) {
    datasetRepository
        .findById(datasetId)
        .orElseThrow(() -> new IllegalArgumentException(DatasetNotFoundException.MESSAGE));

    // 없는 id·다른 데이터셋의 가져오기·가져오기가 아닌 감사 기록(같은 데이터셋의 내보내기 등)은 모두 같은 404 로 답한다(WD-250) —
    // 감사 기록 id 는 모든 행위가 공유하는 번호라, 행위 종류를 보지 않으면 내보내기 기록이 가져오기 이력처럼 보였다.
    AuditLogResponse auditLog =
        auditLogService.findById(importId).orElseThrow(ImportNotFoundException::new);

    if (!"IMPORT".equals(auditLog.actionType())
        || !"dataset".equals(auditLog.resource())
        || !String.valueOf(datasetId).equals(auditLog.resourceId())) {
      throw new ImportNotFoundException();
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

  /**
   * 이름이 같은 빈 열 여럿 중 하나를 매핑한 요청이면 {@link InvalidFileHeaderException}(400)을 던진다(WD-369). 헤더를 다시 읽는 비용(엑셀은
   * 통합 문서를 다시 연다)은 빈 이름 열을 매핑했을 때만 치른다.
   */
  private void requireMappableBlankColumns(
      Path file, String fileType, ParseOptions parseOptions, List<ColumnMappingEntry> mappings)
      throws Exception {
    if (!FileHeaderValidator.mapsBlankColumn(mappings)) return;
    FileHeaderValidator.requireMappableBlankColumns(
        fileParserService.parseHeaders(file, fileType, parseOptions), mappings);
  }

  /** 같은 데이터셋에 진행 중인 가져오기가 있을 때의 409 메시지 — 사전 확인과 createJob 제약 위반 두 곳이 같은 문구를 쓴다. */
  static final String CONCURRENT_IMPORT_MESSAGE = "이미 진행 중인 임포트가 있습니다. 완료된 뒤 다시 시도해 주세요.";

  /** 임포트 잡이 파싱 옵션 파일을 읽지 못했을 때의 실패 사유(WD-253). */
  static final String PARSE_OPTIONS_UNREADABLE =
      "가져오기 설정(구분자·인코딩·시트 등)을 읽지 못해 가져오기를 중단했습니다. 파일을 다시 올려 가져오기를 다시 시작해 주세요.";

  private String toUserFriendlyMessage(Exception e) {
    // 잡이 스스로 실패 사유를 정한 경우(옵션 파일을 읽지 못함 등, WD-253)는 그 한국어 문구를 그대로 쓴다.
    if (e instanceof ImportProcessingException processing) {
      return processing.getMessage();
    }
    // 파서가 파일을 읽지 못한 경우(엑셀 아님·손상·암호·깨진 CSV)는 파서가 이미 사용자용 한국어 문구를 실어 두었다 — 그대로 쓴다(WD-204).
    // REPLACE 경로는 트랜잭션 람다가 예외를 감쌀 수 있어 원인 체인까지 본다.
    for (Throwable cur = e; cur != null; cur = cur.getCause() == cur ? null : cur.getCause()) {
      if (cur instanceof FileUnreadableException unreadable) {
        return unreadable.getMessage();
      }
    }
    // 헤더 규칙 위반(같은 이름 열 중복, WD-205)은 파서가 만든 한국어 문구를 그대로 쓴다. 메시지에 열 이름이 들어가므로
    // 아래 문자열 매칭("Connection" 등)보다 먼저 판정해야 열 이름 때문에 엉뚱한 문구로 바뀌지 않는다. 래핑돼 올 수 있어 원인 체인을 훑는다.
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof InvalidFileHeaderException headerError) {
        return headerError.getMessage();
      }
    }

    // DB 단계 데이터 오류(SQLState 22xxx)는 원인 체인의 SQLState 로 먼저 판정한다 — 메시지 문자열 매칭(아래 "Connection" 등)은
    // 예외 메시지에 실린 SQL 문 텍스트에 걸릴 수 있어 순서상 앞에 둔다(WD-96).
    // 사유 문장은 행 검증이 빠뜨린 값(메타데이터와 물리 타입 불일치 등)이 INSERT 에서 실패한 경우에도 무엇을 고칠지 알려 준다.
    String dataErrorReason = DataExceptionTranslator.describe(e);
    if (dataErrorReason != null) {
      return "데이터 오류로 임포트에 실패했습니다: " + dataErrorReason + ". 파일의 값 또는 컬럼 설정을 확인해주세요.";
    }

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
