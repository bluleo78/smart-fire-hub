package com.smartfirehub.dataimport.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.audit.dto.AuditLogResponse;
import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.dataimport.dto.ImportResponse;
import com.smartfirehub.dataimport.exception.UnsupportedFileTypeException;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.DatasetResponse;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.service.DataTableService;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.scheduling.JobScheduler;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.opencsv.CSVWriter;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DataImportService {

    private static final Logger log = LoggerFactory.getLogger(DataImportService.class);

    private final DatasetRepository datasetRepository;
    private final DatasetColumnRepository columnRepository;
    private final DataTableService dataTableService;
    private final FileParserService fileParserService;
    private final DataValidationService validationService;
    private final AuditLogService auditLogService;
    private final JobScheduler jobScheduler;
    private final ObjectMapper objectMapper;

    public DataImportService(DatasetRepository datasetRepository,
                             DatasetColumnRepository columnRepository,
                             DataTableService dataTableService,
                             FileParserService fileParserService,
                             DataValidationService validationService,
                             AuditLogService auditLogService,
                             JobScheduler jobScheduler,
                             ObjectMapper objectMapper) {
        this.datasetRepository = datasetRepository;
        this.columnRepository = columnRepository;
        this.dataTableService = dataTableService;
        this.fileParserService = fileParserService;
        this.validationService = validationService;
        this.auditLogService = auditLogService;
        this.jobScheduler = jobScheduler;
        this.objectMapper = objectMapper;
    }

    public ImportResponse importFile(Long datasetId, MultipartFile file, Long userId,
                                     String username, String ipAddress, String userAgent) throws Exception {
        // Validate dataset exists
        datasetRepository.findById(datasetId)
                .orElseThrow(() -> new IllegalArgumentException("Dataset not found: " + datasetId));

        // Validate file type
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isEmpty()) {
            throw new UnsupportedFileTypeException("File name is required");
        }

        String fileType = getFileType(originalFilename);
        if (!fileType.equals("csv") && !fileType.equals("xlsx")) {
            throw new UnsupportedFileTypeException("Unsupported file type. Only CSV and XLSX are supported.");
        }

        long fileSize = file.getSize();

        // Save file to temp location for Jobrunr processing
        Path tempDir = Path.of(System.getProperty("java.io.tmpdir"), "firehub-imports");
        Files.createDirectories(tempDir);
        Path tempFile = Files.createTempFile(tempDir, "import-", "." + fileType);
        file.transferTo(tempFile.toFile());

        // Extract to local variables for Jobrunr lambda serialization
        String filePath = tempFile.toString();
        String upperFileType = fileType.toUpperCase();

        // Enqueue Jobrunr job
        jobScheduler.enqueue(() -> processImport(
                datasetId, filePath, originalFilename, fileSize,
                upperFileType, userId, username, ipAddress, userAgent
        ));

        // Return PENDING response
        return new ImportResponse(
                null,
                datasetId,
                originalFilename,
                fileSize,
                fileType.toUpperCase(),
                "PENDING",
                null, null, null, null,
                username,
                null, null,
                LocalDateTime.now()
        );
    }

    @Job(name = "데이터 임포트: %2 → dataset %0")
    public void processImport(Long datasetId, String filePath, String fileName,
                              Long fileSize, String fileType,
                              Long userId, String username,
                              String ipAddress, String userAgent) {
        try {
            // Read file
            byte[] fileData = Files.readAllBytes(Path.of(filePath));

            // Parse file
            log.info("Parsing file for import: {} → dataset {}", fileName, datasetId);
            List<Map<String, String>> parsedRows = fileParserService.parse(fileData, fileType.toLowerCase());

            // Load dataset columns
            List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(datasetId);

            // Validate data
            log.info("Validating {} rows for import", parsedRows.size());
            DataValidationService.ValidationResult validationResult = validationService.validate(parsedRows, columns);

            if (validationResult.validCount() == 0 && !validationResult.errors().isEmpty()) {
                // All rows failed validation
                String errorJson = objectMapper.writeValueAsString(Map.of("errors", validationResult.errors()));

                Map<String, Object> metadata = Map.of(
                        "fileName", fileName, "fileSize", fileSize, "fileType", fileType,
                        "totalRows", validationResult.totalRows(),
                        "successRows", 0,
                        "errorRows", validationResult.errorCount(),
                        "errorDetails", errorJson
                );

                auditLogService.log(userId, username, "IMPORT", "dataset",
                        String.valueOf(datasetId), "파일 임포트: " + fileName,
                        ipAddress, userAgent, "FAILURE",
                        "All rows failed validation", metadata);

                log.error("Import failed: all rows invalid for dataset {}", datasetId);
                return;
            }

            // Insert valid rows
            if (validationResult.validCount() > 0) {
                DatasetResponse dataset = datasetRepository.findById(datasetId).orElseThrow();
                List<String> columnNames = columns.stream().map(DatasetColumnResponse::columnName).toList();

                log.info("Inserting {} valid rows", validationResult.validCount());

                List<Map<String, Object>> rowMaps = validationResult.validRows().stream()
                        .map(row -> {
                            Map<String, Object> rowMap = new HashMap<>();
                            for (int i = 0; i < columnNames.size() && i < row.size(); i++) {
                                rowMap.put(columnNames.get(i), row.get(i));
                            }
                            return rowMap;
                        })
                        .toList();

                dataTableService.insertBatch(dataset.tableName(), columnNames, rowMaps);
            }

            // Log success to audit_log
            String errorJson = null;
            if (!validationResult.errors().isEmpty()) {
                errorJson = objectMapper.writeValueAsString(Map.of("errors", validationResult.errors()));
            }

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("fileName", fileName);
            metadata.put("fileSize", fileSize);
            metadata.put("fileType", fileType);
            metadata.put("totalRows", validationResult.totalRows());
            metadata.put("successRows", validationResult.validCount());
            metadata.put("errorRows", validationResult.errorCount());
            if (errorJson != null) {
                metadata.put("errorDetails", errorJson);
            }

            auditLogService.log(userId, username, "IMPORT", "dataset",
                    String.valueOf(datasetId), "파일 임포트: " + fileName,
                    ipAddress, userAgent, "SUCCESS", null, metadata);

            log.info("Import completed for dataset {}. Valid: {}, Errors: {}",
                    datasetId, validationResult.validCount(), validationResult.errorCount());

        } catch (Exception e) {
            log.error("Import failed for dataset {}", datasetId, e);

            Map<String, Object> metadata = Map.of(
                    "fileName", fileName, "fileSize", fileSize, "fileType", fileType,
                    "error", e.getMessage() != null ? e.getMessage() : "Unknown error"
            );

            auditLogService.log(userId, username, "IMPORT", "dataset",
                    String.valueOf(datasetId), "파일 임포트: " + fileName,
                    ipAddress, userAgent, "FAILURE", e.getMessage(), metadata);

            throw new RuntimeException("Import failed: " + e.getMessage(), e);
        } finally {
            // Clean up temp file
            try {
                Files.deleteIfExists(Path.of(filePath));
            } catch (Exception e) {
                log.warn("Failed to delete temp file: {}", filePath, e);
            }
        }
    }

    public List<ImportResponse> getImportsByDatasetId(Long datasetId) {
        datasetRepository.findById(datasetId)
                .orElseThrow(() -> new IllegalArgumentException("Dataset not found: " + datasetId));

        List<AuditLogResponse> auditLogs = auditLogService.findByResource("IMPORT", "dataset", String.valueOf(datasetId));
        return auditLogs.stream()
                .map(this::mapToImportResponse)
                .toList();
    }

    public ImportResponse getImportById(Long datasetId, Long importId) {
        datasetRepository.findById(datasetId)
                .orElseThrow(() -> new IllegalArgumentException("Dataset not found: " + datasetId));

        AuditLogResponse auditLog = auditLogService.findById(importId)
                .orElseThrow(() -> new IllegalArgumentException("Import not found: " + importId));

        if (!String.valueOf(datasetId).equals(auditLog.resourceId())) {
            throw new IllegalArgumentException("Import does not belong to this dataset");
        }

        return mapToImportResponse(auditLog);
    }

    private ImportResponse mapToImportResponse(AuditLogResponse auditLog) {
        Map<String, Object> meta = parseMetadata(auditLog.metadata());

        String status = switch (auditLog.result()) {
            case "SUCCESS" -> "COMPLETED";
            case "FAILURE" -> "FAILED";
            default -> auditLog.result();
        };

        Long datasetId = null;
        if (auditLog.resourceId() != null) {
            try {
                datasetId = Long.parseLong(auditLog.resourceId());
            } catch (NumberFormatException ignored) {}
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
                auditLog.actionTime()
        );
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
            try { return Long.parseLong(value.toString()); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private Integer getMetaInteger(Map<String, Object> meta, String key) {
        Object value = meta.get(key);
        if (value instanceof Number n) return n.intValue();
        if (value != null) {
            try { return Integer.parseInt(value.toString()); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    public byte[] exportDatasetCsv(Long datasetId) throws Exception {
        DatasetResponse dataset = datasetRepository.findById(datasetId)
                .orElseThrow(() -> new IllegalArgumentException("Dataset not found: " + datasetId));

        List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(datasetId);
        List<String> columnNames = columns.stream().map(DatasetColumnResponse::columnName).toList();

        int pageSize = 1000;
        int page = 0;
        List<Map<String, Object>> allRows = new java.util.ArrayList<>();

        while (true) {
            List<Map<String, Object>> pageRows = dataTableService.queryData(dataset.tableName(), columnNames, null, page, pageSize);
            if (pageRows.isEmpty()) {
                break;
            }
            allRows.addAll(pageRows);
            page++;

            if (allRows.size() > 100000) {
                break;
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(0xEF);
        baos.write(0xBB);
        baos.write(0xBF);

        try (OutputStreamWriter osw = new OutputStreamWriter(baos, StandardCharsets.UTF_8);
             CSVWriter writer = new CSVWriter(osw)) {

            String[] headers = columns.stream()
                    .map(col -> col.displayName() != null && !col.displayName().isEmpty()
                            ? col.displayName()
                            : col.columnName())
                    .toArray(String[]::new);
            writer.writeNext(headers);

            for (Map<String, Object> row : allRows) {
                String[] rowData = new String[columnNames.size()];
                for (int i = 0; i < columnNames.size(); i++) {
                    Object value = row.get(columnNames.get(i));
                    rowData[i] = value != null ? value.toString() : "";
                }
                writer.writeNext(rowData);
            }
        }

        return baos.toByteArray();
    }

    private String getFileType(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0 && lastDot < filename.length() - 1) {
            return filename.substring(lastDot + 1).toLowerCase();
        }
        return "";
    }
}
