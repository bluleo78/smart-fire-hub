package com.smartfirehub.dataimport.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataimport.dto.ImportResponse;
import com.smartfirehub.dataimport.exception.ImportValidationException;
import com.smartfirehub.dataimport.exception.UnsupportedFileTypeException;
import com.smartfirehub.dataimport.repository.DataImportRepository;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.DatasetResponse;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.service.DataTableService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.opencsv.CSVWriter;

@Service
public class DataImportService {

    private static final Logger log = LoggerFactory.getLogger(DataImportService.class);

    private final DataImportRepository importRepository;
    private final DatasetRepository datasetRepository;
    private final DatasetColumnRepository columnRepository;
    private final DataTableService dataTableService;
    private final FileParserService fileParserService;
    private final DataValidationService validationService;
    private final ObjectMapper objectMapper;

    public DataImportService(DataImportRepository importRepository,
                           DatasetRepository datasetRepository,
                           DatasetColumnRepository columnRepository,
                           DataTableService dataTableService,
                           FileParserService fileParserService,
                           DataValidationService validationService,
                           ObjectMapper objectMapper) {
        this.importRepository = importRepository;
        this.datasetRepository = datasetRepository;
        this.columnRepository = columnRepository;
        this.dataTableService = dataTableService;
        this.fileParserService = fileParserService;
        this.validationService = validationService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ImportResponse importFile(Long datasetId, MultipartFile file, Long userId) throws Exception {
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

        // Create import record
        ImportResponse importRecord = importRepository.save(
                datasetId,
                originalFilename,
                file.getSize(),
                fileType.toUpperCase(),
                userId
        );

        // Process asynchronously
        byte[] fileData = file.getBytes();
        processImportAsync(importRecord.id(), datasetId, fileData, fileType);

        return importRecord;
    }

    @Async
    public void processImportAsync(Long importId, Long datasetId, byte[] fileData, String fileType) {
        LocalDateTime startedAt = LocalDateTime.now();

        try {
            // Update status to PROCESSING
            importRepository.updateStarted(importId);

            // Parse file
            log.info("Parsing file for import {}", importId);
            List<Map<String, String>> parsedRows = fileParserService.parse(fileData, fileType);

            // Load dataset columns
            List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(datasetId);

            // Validate data
            log.info("Validating {} rows for import {}", parsedRows.size(), importId);
            DataValidationService.ValidationResult validationResult = validationService.validate(parsedRows, columns);

            if (validationResult.validCount() == 0 && !validationResult.errors().isEmpty()) {
                // All rows failed validation
                String errorJson = objectMapper.writeValueAsString(Map.of("errors", validationResult.errors()));
                importRepository.updateStatus(
                        importId,
                        "FAILED",
                        validationResult.totalRows(),
                        0,
                        validationResult.errorCount(),
                        errorJson,
                        startedAt,
                        LocalDateTime.now()
                );
                log.error("Import {} failed: all rows invalid", importId);
                return;
            }

            // Insert valid rows
            if (validationResult.validCount() > 0) {
                DatasetResponse dataset = datasetRepository.findById(datasetId).orElseThrow();
                List<String> columnNames = columns.stream().map(DatasetColumnResponse::columnName).toList();

                log.info("Inserting {} valid rows for import {}", validationResult.validCount(), importId);

                // Convert List<List<Object>> to List<Map<String, Object>>
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

            // Update import record with results
            String errorJson = null;
            if (!validationResult.errors().isEmpty()) {
                errorJson = objectMapper.writeValueAsString(Map.of("errors", validationResult.errors()));
            }

            importRepository.updateStatus(
                    importId,
                    "COMPLETED",
                    validationResult.totalRows(),
                    validationResult.validCount(),
                    validationResult.errorCount(),
                    errorJson,
                    startedAt,
                    LocalDateTime.now()
            );

            log.info("Import {} completed successfully. Valid: {}, Errors: {}",
                    importId, validationResult.validCount(), validationResult.errorCount());

        } catch (Exception e) {
            log.error("Import {} failed with exception", importId, e);
            try {
                String errorJson = objectMapper.writeValueAsString(Map.of("error", e.getMessage()));
                importRepository.updateStatus(
                        importId,
                        "FAILED",
                        null,
                        0,
                        null,
                        errorJson,
                        startedAt,
                        LocalDateTime.now()
                );
            } catch (Exception ex) {
                log.error("Failed to update import status for import {}", importId, ex);
            }
        }
    }

    public List<ImportResponse> getImportsByDatasetId(Long datasetId) {
        // Verify dataset exists
        datasetRepository.findById(datasetId)
                .orElseThrow(() -> new IllegalArgumentException("Dataset not found: " + datasetId));

        return importRepository.findByDatasetId(datasetId);
    }

    public ImportResponse getImportById(Long datasetId, Long importId) {
        // Verify dataset exists
        datasetRepository.findById(datasetId)
                .orElseThrow(() -> new IllegalArgumentException("Dataset not found: " + datasetId));

        ImportResponse importRecord = importRepository.findById(importId)
                .orElseThrow(() -> new IllegalArgumentException("Import not found: " + importId));

        // Verify import belongs to this dataset
        if (!importRecord.datasetId().equals(datasetId)) {
            throw new IllegalArgumentException("Import does not belong to this dataset");
        }

        return importRecord;
    }

    public byte[] exportDatasetCsv(Long datasetId) throws Exception {
        // Load dataset
        DatasetResponse dataset = datasetRepository.findById(datasetId)
                .orElseThrow(() -> new IllegalArgumentException("Dataset not found: " + datasetId));

        // Load columns
        List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(datasetId);
        List<String> columnNames = columns.stream().map(DatasetColumnResponse::columnName).toList();

        // Query all data (use pagination for large datasets)
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

            // Safety limit to prevent memory issues
            if (allRows.size() > 100000) {
                break;
            }
        }

        // Write CSV with BOM for Excel compatibility
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(0xEF);
        baos.write(0xBB);
        baos.write(0xBF);

        try (OutputStreamWriter osw = new OutputStreamWriter(baos, StandardCharsets.UTF_8);
             CSVWriter writer = new CSVWriter(osw)) {

            // Write headers (use displayName or columnName)
            String[] headers = columns.stream()
                    .map(col -> col.displayName() != null && !col.displayName().isEmpty()
                            ? col.displayName()
                            : col.columnName())
                    .toArray(String[]::new);
            writer.writeNext(headers);

            // Write data rows
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
