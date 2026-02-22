package com.smartfirehub.dataimport.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.audit.dto.AuditLogResponse;
import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.dataimport.dto.*;
import com.smartfirehub.dataimport.exception.ConcurrentImportException;
import com.smartfirehub.dataimport.exception.ImportProcessingException;
import com.smartfirehub.dataimport.exception.UnsupportedFileTypeException;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.DatasetResponse;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.job.service.AsyncJobService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.scheduling.JobScheduler;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DataImportService {

  private static final Logger log = LoggerFactory.getLogger(DataImportService.class);

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

  public DataImportService(
      DatasetRepository datasetRepository,
      DatasetColumnRepository columnRepository,
      DataTableService dataTableService,
      DataTableRowService dataTableRowService,
      FileParserService fileParserService,
      DataValidationService validationService,
      ColumnMappingService columnMappingService,
      AuditLogService auditLogService,
      JobScheduler jobScheduler,
      ObjectMapper objectMapper,
      AsyncJobService asyncJobService,
      TransactionTemplate transactionTemplate) {
    this.datasetRepository = datasetRepository;
    this.columnRepository = columnRepository;
    this.dataTableService = dataTableService;
    this.dataTableRowService = dataTableRowService;
    this.fileParserService = fileParserService;
    this.validationService = validationService;
    this.columnMappingService = columnMappingService;
    this.auditLogService = auditLogService;
    this.jobScheduler = jobScheduler;
    this.objectMapper = objectMapper;
    this.asyncJobService = asyncJobService;
    this.transactionTemplate = transactionTemplate;
  }

  public ImportPreviewResponse previewImport(Long datasetId, MultipartFile file) throws Exception {
    return previewImport(datasetId, file, ParseOptions.defaults());
  }

  public ImportPreviewResponse previewImport(
      Long datasetId, MultipartFile file, ParseOptions parseOptions) throws Exception {
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
    if (!fileType.equals("csv") && !fileType.equals("xlsx")) {
      throw new UnsupportedFileTypeException(
          "Unsupported file type. Only CSV and XLSX are supported.");
    }

    byte[] fileData = file.getBytes();

    // Parse headers
    List<String> headers = fileParserService.parseHeaders(fileData, fileType, parseOptions);

    // Parse sample rows (5 rows)
    List<Map<String, String>> sampleRows =
        fileParserService.parseSampleRows(fileData, fileType, 5, parseOptions);

    // Count total rows
    int totalRows = fileParserService.countRows(fileData, fileType, parseOptions);

    // Get dataset columns
    List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(datasetId);

    // Suggest mappings
    List<ColumnMappingDto> suggestedMappings =
        columnMappingService.suggestMappings(headers, columns);

    return new ImportPreviewResponse(headers, sampleRows, suggestedMappings, totalRows);
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
    if (!fileType.equals("csv") && !fileType.equals("xlsx")) {
      throw new UnsupportedFileTypeException(
          "Unsupported file type. Only CSV and XLSX are supported.");
    }

    byte[] fileData = file.getBytes();

    // Parse all rows
    List<Map<String, String>> rows = fileParserService.parse(fileData, fileType, parseOptions);

    // Get dataset columns
    List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(datasetId);

    // Validate with mappings
    DataValidationService.ValidationResultWithDetails validationResult =
        validationService.validateWithMapping(rows, columns, mappings);

    // Limit errors to first 100 for performance
    List<ValidationErrorDetail> limitedErrors =
        validationResult.errors().stream().limit(100).toList();

    return new ImportValidateResponse(
        validationResult.totalRows(),
        validationResult.validCount(),
        validationResult.errorCount(),
        limitedErrors);
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
    if (!fileType.equals("csv") && !fileType.equals("xlsx")) {
      throw new UnsupportedFileTypeException(
          "Unsupported file type. Only CSV and XLSX are supported.");
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
    try {
      asyncJobService.updateProgress(
          jobId, "PARSING", 10, "Parsing file...", Map.of("totalRows", 0, "processedRows", 0));

      // Read file
      byte[] fileData = Files.readAllBytes(Path.of(filePath));

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

      // Parse file
      log.info("Parsing file for import: {} → dataset {}", fileName, datasetId);
      List<Map<String, String>> parsedRows =
          fileParserService.parse(fileData, fileType.toLowerCase(), parseOptions);

      asyncJobService.updateProgress(
          jobId,
          "VALIDATING",
          30,
          "Validating data...",
          Map.of("totalRows", parsedRows.size(), "processedRows", 0));

      // Load dataset columns
      List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(datasetId);

      // Load mappings if provided
      List<ColumnMappingEntry> mappings = null;
      if (mappingsPath != null && !mappingsPath.isEmpty()) {
        String mappingsJson = Files.readString(Path.of(mappingsPath));
        mappings =
            objectMapper.readValue(mappingsJson, new TypeReference<List<ColumnMappingEntry>>() {});
      }

      // Validate data
      log.info("Validating {} rows for import", parsedRows.size());

      Object validationResult;
      int totalRows, validCount, errorCount;
      List<List<Object>> validRows;

      if (mappings != null && !mappings.isEmpty()) {
        DataValidationService.ValidationResultWithDetails result =
            validationService.validateWithMapping(parsedRows, columns, mappings);
        validationResult = result;
        totalRows = result.totalRows();
        validCount = result.validCount();
        errorCount = result.errorCount();
        validRows = result.validRows();

        if (validCount == 0 && errorCount > 0) {
          // All rows failed validation
          asyncJobService.failJob(jobId, "All rows failed validation");

          List<ValidationErrorDetail> errors = result.errors();
          String errorJson = objectMapper.writeValueAsString(Map.of("errors", errors));

          Map<String, Object> metadata =
              Map.of(
                  "fileName",
                  fileName,
                  "fileSize",
                  fileSize,
                  "fileType",
                  fileType,
                  "totalRows",
                  totalRows,
                  "successRows",
                  0,
                  "errorRows",
                  errorCount,
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
              "All rows failed validation",
              metadata);

          log.error("Import failed: all rows invalid for dataset {}", datasetId);
          return;
        }
      } else {
        DataValidationService.ValidationResult result =
            validationService.validate(parsedRows, columns);
        validationResult = result;
        totalRows = result.totalRows();
        validCount = result.validCount();
        errorCount = result.errorCount();
        validRows = result.validRows();

        if (validCount == 0 && !result.errors().isEmpty()) {
          // All rows failed validation
          asyncJobService.failJob(jobId, "All rows failed validation");

          String errorJson = objectMapper.writeValueAsString(Map.of("errors", result.errors()));

          Map<String, Object> metadata =
              Map.of(
                  "fileName",
                  fileName,
                  "fileSize",
                  fileSize,
                  "fileType",
                  fileType,
                  "totalRows",
                  totalRows,
                  "successRows",
                  0,
                  "errorRows",
                  errorCount,
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
              "All rows failed validation",
              metadata);

          log.error("Import failed: all rows invalid for dataset {}", datasetId);
          return;
        }
      }

      // Route by import mode and insert/upsert/replace valid rows
      ImportMode importMode =
          ImportMode.valueOf(
              importModeName != null && !importModeName.isEmpty()
                  ? importModeName
                  : ImportMode.APPEND.name());

      if (validCount > 0) {
        DatasetResponse dataset = datasetRepository.findById(datasetId).orElseThrow();
        List<String> columnNames = columns.stream().map(DatasetColumnResponse::columnName).toList();

        List<Map<String, Object>> rowMaps =
            validRows.stream()
                .map(
                    row -> {
                      Map<String, Object> rowMap = new HashMap<>();
                      for (int i = 0; i < columnNames.size() && i < row.size(); i++) {
                        rowMap.put(columnNames.get(i), row.get(i));
                      }
                      return rowMap;
                    })
                .toList();

        int insertTotal = rowMaps.size();

        switch (importMode) {
          case APPEND -> {
            log.info("APPEND mode: inserting {} valid rows", validCount);
            asyncJobService.updateProgress(
                jobId,
                "INSERTING",
                40,
                "Inserting data...",
                Map.of("totalRows", insertTotal, "processedRows", 0));
            dataTableRowService.insertBatchWithProgress(
                dataset.tableName(),
                columnNames,
                rowMaps,
                (processed, total) -> {
                  int pct = 40 + (int) ((processed / (double) total) * 60);
                  asyncJobService.updateProgress(
                      jobId,
                      "INSERTING",
                      pct,
                      "Inserting data...",
                      Map.of("totalRows", total, "processedRows", processed));
                });
          }
          case UPSERT -> {
            List<String> pkColumns =
                columns.stream()
                    .filter(DatasetColumnResponse::isPrimaryKey)
                    .map(DatasetColumnResponse::columnName)
                    .toList();
            if (pkColumns.isEmpty()) {
              throw new IllegalStateException(
                  "UPSERT mode requires at least one primary key column");
            }

            // Validate PK values in import data (NULL checks + within-file duplicate warnings)
            // Use rowMaps which already have dataset column names as keys
            List<Map<String, String>> rowMapsAsStrings =
                rowMaps.stream()
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
              // NULL PK values found — fail the import
              asyncJobService.failJob(
                  jobId, "Primary key validation failed: NULL values in PK columns");
              String pkErrorJson =
                  objectMapper.writeValueAsString(Map.of("errors", pkValidation.errors()));
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
            if (!pkValidation.warnings().isEmpty()) {
              log.warn(
                  "UPSERT PK warnings (within-file duplicates): {}",
                  pkValidation.warnings().size());
            }

            log.info(
                "UPSERT mode: upserting {} valid rows on PK columns {}", validCount, pkColumns);
            asyncJobService.updateProgress(
                jobId,
                "INSERTING",
                40,
                "Upserting data...",
                Map.of("totalRows", insertTotal, "processedRows", 0));
            dataTableRowService.upsertBatchWithProgress(
                dataset.tableName(),
                columnNames,
                pkColumns,
                rowMaps,
                null,
                (processed, total) -> {
                  int pct = 40 + (int) ((processed / (double) total) * 60);
                  asyncJobService.updateProgress(
                      jobId,
                      "INSERTING",
                      pct,
                      "Upserting data...",
                      Map.of("totalRows", total, "processedRows", processed));
                });
          }
          case REPLACE -> {
            log.info("REPLACE mode: truncating table then inserting {} valid rows", validCount);
            asyncJobService.updateProgress(
                jobId,
                "INSERTING",
                40,
                "Replacing table...",
                Map.of("totalRows", insertTotal, "processedRows", 0));
            // Wrap truncate + insert in a single transaction for atomicity
            transactionTemplate.executeWithoutResult(
                status -> {
                  dataTableRowService.truncateTable(dataset.tableName());
                  dataTableRowService.insertBatchWithProgress(
                      dataset.tableName(),
                      columnNames,
                      rowMaps,
                      (processed, total) -> {
                        int pct = 40 + (int) ((processed / (double) total) * 60);
                        asyncJobService.updateProgress(
                            jobId,
                            "INSERTING",
                            pct,
                            "Replacing table...",
                            Map.of("totalRows", total, "processedRows", processed));
                      });
                });
          }
        }
      }

      // Log success to audit_log
      String errorJson = null;
      if (validationResult
          instanceof DataValidationService.ValidationResultWithDetails detailedResult) {
        if (!detailedResult.errors().isEmpty()) {
          errorJson = objectMapper.writeValueAsString(Map.of("errors", detailedResult.errors()));
        }
      } else if (validationResult instanceof DataValidationService.ValidationResult simpleResult) {
        if (!simpleResult.errors().isEmpty()) {
          errorJson = objectMapper.writeValueAsString(Map.of("errors", simpleResult.errors()));
        }
      }

      Map<String, Object> metadata = new HashMap<>();
      metadata.put("fileName", fileName);
      metadata.put("fileSize", fileSize);
      metadata.put("fileType", fileType);
      metadata.put("importMode", importMode.name());
      metadata.put("totalRows", totalRows);
      metadata.put("successRows", validCount);
      metadata.put("errorRows", errorCount);
      if (errorJson != null) {
        metadata.put("errorDetails", errorJson);
      }

      asyncJobService.completeJob(
          jobId,
          Map.of("totalRows", totalRows, "successRows", validCount, "errorRows", errorCount));

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
          validCount,
          errorCount);

    } catch (Exception e) {
      log.error("Import failed for dataset {}", datasetId, e);

      asyncJobService.failJob(jobId, e.getMessage());

      Map<String, Object> metadata =
          Map.of(
              "fileName",
              fileName,
              "fileSize",
              fileSize,
              "fileType",
              fileType,
              "error",
              e.getMessage() != null ? e.getMessage() : "Unknown error");

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
          e.getMessage(),
          metadata);

      throw new ImportProcessingException("Import failed: " + e.getMessage(), e);
    } finally {
      // Clean up temp files
      try {
        Files.deleteIfExists(Path.of(filePath));
        if (mappingsPath != null && !mappingsPath.isEmpty()) {
          Files.deleteIfExists(Path.of(mappingsPath));
        }
        if (parseOptsPath != null && !parseOptsPath.isEmpty()) {
          Files.deleteIfExists(Path.of(parseOptsPath));
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
}
