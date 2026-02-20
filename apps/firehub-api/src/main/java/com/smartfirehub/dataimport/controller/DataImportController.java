package com.smartfirehub.dataimport.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataimport.dto.ColumnMappingEntry;
import com.smartfirehub.dataimport.dto.ImportPreviewResponse;
import com.smartfirehub.dataimport.dto.ImportResponse;
import com.smartfirehub.dataimport.dto.ImportStartResponse;
import com.smartfirehub.dataimport.dto.ImportValidateResponse;
import com.smartfirehub.dataimport.dto.ParseOptions;
import com.smartfirehub.dataimport.service.DataImportService;
import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@RestController
@RequestMapping("/api/v1/datasets/{datasetId}")
public class DataImportController {

    private final DataImportService importService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final DatasetRepository datasetRepository;

    public DataImportController(DataImportService importService, UserRepository userRepository, ObjectMapper objectMapper, DatasetRepository datasetRepository) {
        this.importService = importService;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.datasetRepository = datasetRepository;
    }

    @PostMapping("/imports/preview")
    @RequirePermission("data:import")
    public ResponseEntity<ImportPreviewResponse> previewImport(
            @PathVariable Long datasetId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = ",") String delimiter,
            @RequestParam(defaultValue = "UTF-8") String encoding,
            @RequestParam(defaultValue = "true") boolean hasHeader,
            @RequestParam(defaultValue = "0") int skipRows) throws Exception {
        ParseOptions parseOptions = new ParseOptions(delimiter, encoding, hasHeader, skipRows);
        return ResponseEntity.ok(importService.previewImport(datasetId, file, parseOptions));
    }

    @PostMapping("/imports/validate")
    @RequirePermission("data:import")
    public ResponseEntity<ImportValidateResponse> validateImport(
            @PathVariable Long datasetId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("mappings") String mappingsJson,
            @RequestParam(defaultValue = ",") String delimiter,
            @RequestParam(defaultValue = "UTF-8") String encoding,
            @RequestParam(defaultValue = "true") boolean hasHeader,
            @RequestParam(defaultValue = "0") int skipRows) throws Exception {
        List<ColumnMappingEntry> mappings = objectMapper.readValue(mappingsJson, new TypeReference<List<ColumnMappingEntry>>() {});
        ParseOptions parseOptions = new ParseOptions(delimiter, encoding, hasHeader, skipRows);
        return ResponseEntity.ok(importService.validateImport(datasetId, file, mappings, parseOptions));
    }

    @PostMapping("/imports")
    @RequirePermission("data:import")
    public ResponseEntity<ImportStartResponse> importFile(
            @PathVariable Long datasetId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "mappings", required = false) String mappingsJson,
            @RequestParam(defaultValue = ",") String delimiter,
            @RequestParam(defaultValue = "UTF-8") String encoding,
            @RequestParam(defaultValue = "true") boolean hasHeader,
            @RequestParam(defaultValue = "0") int skipRows,
            HttpServletRequest request) throws Exception {
        Long userId = Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
        String username = userRepository.findById(userId)
                .map(user -> user.name())
                .orElse(String.valueOf(userId));
        String ipAddress = request.getRemoteAddr();
        String userAgent = request.getHeader("User-Agent");

        List<ColumnMappingEntry> mappings = null;
        if (mappingsJson != null && !mappingsJson.isEmpty()) {
            mappings = objectMapper.readValue(mappingsJson, new TypeReference<List<ColumnMappingEntry>>() {});
        }

        ParseOptions parseOptions = new ParseOptions(delimiter, encoding, hasHeader, skipRows);
        ImportStartResponse response = importService.importFile(datasetId, file, mappings, userId, username, ipAddress, userAgent, parseOptions);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/imports")
    @RequirePermission("dataset:read")
    public ResponseEntity<List<ImportResponse>> getImports(@PathVariable Long datasetId) {
        return ResponseEntity.ok(importService.getImportsByDatasetId(datasetId));
    }

    @GetMapping("/imports/{importId}")
    @RequirePermission("dataset:read")
    public ResponseEntity<ImportResponse> getImport(
            @PathVariable Long datasetId,
            @PathVariable Long importId) {
        return ResponseEntity.ok(importService.getImportById(datasetId, importId));
    }

    @GetMapping("/data/export")
    @RequirePermission("data:export")
    public ResponseEntity<byte[]> exportCsv(@PathVariable Long datasetId) throws Exception {
        byte[] csv = importService.exportDatasetCsv(datasetId);
        String tableName = datasetRepository.findTableNameById(datasetId).orElse("export");
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String filename = tableName + "_export_" + date + ".csv";
        return ResponseEntity.ok()
                .header("Content-Type", "text/csv; charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(csv);
    }
}
