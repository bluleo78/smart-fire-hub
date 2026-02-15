package com.smartfirehub.dataimport.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataimport.dto.ColumnMappingEntry;
import com.smartfirehub.dataimport.dto.ImportPreviewResponse;
import com.smartfirehub.dataimport.dto.ImportResponse;
import com.smartfirehub.dataimport.dto.ImportValidateResponse;
import com.smartfirehub.dataimport.service.DataImportService;
import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/datasets/{datasetId}")
public class DataImportController {

    private final DataImportService importService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public DataImportController(DataImportService importService, UserRepository userRepository, ObjectMapper objectMapper) {
        this.importService = importService;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/imports/preview")
    @RequirePermission("data:import")
    public ResponseEntity<ImportPreviewResponse> previewImport(
            @PathVariable Long datasetId,
            @RequestParam("file") MultipartFile file) throws Exception {
        return ResponseEntity.ok(importService.previewImport(datasetId, file));
    }

    @PostMapping("/imports/validate")
    @RequirePermission("data:import")
    public ResponseEntity<ImportValidateResponse> validateImport(
            @PathVariable Long datasetId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("mappings") String mappingsJson) throws Exception {
        List<ColumnMappingEntry> mappings = objectMapper.readValue(mappingsJson, new TypeReference<List<ColumnMappingEntry>>() {});
        return ResponseEntity.ok(importService.validateImport(datasetId, file, mappings));
    }

    @PostMapping("/imports")
    @RequirePermission("data:import")
    public ResponseEntity<ImportResponse> importFile(
            @PathVariable Long datasetId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "mappings", required = false) String mappingsJson,
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

        ImportResponse response = importService.importFile(datasetId, file, mappings, userId, username, ipAddress, userAgent);
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
        return ResponseEntity.ok()
                .header("Content-Type", "text/csv; charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=\"export.csv\"")
                .body(csv);
    }
}
