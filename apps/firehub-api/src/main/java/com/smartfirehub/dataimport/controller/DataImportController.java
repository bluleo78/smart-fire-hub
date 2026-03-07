package com.smartfirehub.dataimport.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataimport.dto.ColumnMappingEntry;
import com.smartfirehub.dataimport.dto.ImportMode;
import com.smartfirehub.dataimport.dto.ImportPreviewResponse;
import com.smartfirehub.dataimport.dto.ImportResponse;
import com.smartfirehub.dataimport.dto.ImportStartResponse;
import com.smartfirehub.dataimport.dto.ImportValidateResponse;
import com.smartfirehub.dataimport.dto.ParseOptions;
import com.smartfirehub.dataimport.service.DataImportService;
import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/datasets/{datasetId}")
public class DataImportController {

  private final DataImportService importService;
  private final UserRepository userRepository;
  private final ObjectMapper objectMapper;

  public DataImportController(
      DataImportService importService, UserRepository userRepository, ObjectMapper objectMapper) {
    this.importService = importService;
    this.userRepository = userRepository;
    this.objectMapper = objectMapper;
  }

  @PostMapping("/imports/preview")
  @RequirePermission("data:import")
  public ResponseEntity<ImportPreviewResponse> previewImport(
      @PathVariable Long datasetId,
      @RequestParam("file") MultipartFile file,
      @RequestParam(defaultValue = ",") String delimiter,
      @RequestParam(defaultValue = "AUTO") String encoding,
      @RequestParam(defaultValue = "true") boolean hasHeader,
      @RequestParam(defaultValue = "0") int skipRows)
      throws Exception {
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
      @RequestParam(defaultValue = "AUTO") String encoding,
      @RequestParam(defaultValue = "true") boolean hasHeader,
      @RequestParam(defaultValue = "0") int skipRows)
      throws Exception {
    List<ColumnMappingEntry> mappings =
        objectMapper.readValue(mappingsJson, new TypeReference<List<ColumnMappingEntry>>() {});
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
      @RequestParam(defaultValue = "AUTO") String encoding,
      @RequestParam(defaultValue = "true") boolean hasHeader,
      @RequestParam(defaultValue = "0") int skipRows,
      @RequestParam(defaultValue = "APPEND") String importMode,
      HttpServletRequest request,
      Authentication authentication)
      throws Exception {
    Long userId = (Long) authentication.getPrincipal();
    String username =
        userRepository.findById(userId).map(user -> user.name()).orElse(String.valueOf(userId));
    String ipAddress = request.getRemoteAddr();
    String userAgent = request.getHeader("User-Agent");

    ImportMode resolvedImportMode;
    try {
      resolvedImportMode = ImportMode.valueOf(importMode.toUpperCase());
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().build();
    }

    List<ColumnMappingEntry> mappings = null;
    if (mappingsJson != null && !mappingsJson.isEmpty()) {
      mappings =
          objectMapper.readValue(mappingsJson, new TypeReference<List<ColumnMappingEntry>>() {});
    }

    ParseOptions parseOptions = new ParseOptions(delimiter, encoding, hasHeader, skipRows);
    ImportStartResponse response =
        importService.importFile(
            datasetId,
            file,
            mappings,
            userId,
            username,
            ipAddress,
            userAgent,
            parseOptions,
            resolvedImportMode);
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
      @PathVariable Long datasetId, @PathVariable Long importId) {
    return ResponseEntity.ok(importService.getImportById(datasetId, importId));
  }
}
