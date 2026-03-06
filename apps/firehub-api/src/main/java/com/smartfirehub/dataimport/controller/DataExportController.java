package com.smartfirehub.dataimport.controller;

import com.smartfirehub.dataimport.dto.ExportEstimate;
import com.smartfirehub.dataimport.dto.ExportRequest;
import com.smartfirehub.dataimport.dto.ExportResult;
import com.smartfirehub.dataimport.dto.QueryResultExportRequest;
import com.smartfirehub.dataimport.service.DataExportService;
import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.job.dto.AsyncJobStatusResponse;
import com.smartfirehub.job.service.AsyncJobService;
import com.smartfirehub.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1")
public class DataExportController {

  private final DataExportService exportService;
  private final AsyncJobService asyncJobService;
  private final UserRepository userRepository;

  public DataExportController(
      DataExportService exportService,
      AsyncJobService asyncJobService,
      UserRepository userRepository) {
    this.exportService = exportService;
    this.asyncJobService = asyncJobService;
    this.userRepository = userRepository;
  }

  @GetMapping("/datasets/{datasetId}/export/estimate")
  @RequirePermission("data:export")
  public ResponseEntity<ExportEstimate> estimateExport(
      @PathVariable Long datasetId, @RequestParam(required = false) String search) {
    ExportRequest request = new ExportRequest(null, null, search, null);
    return ResponseEntity.ok(exportService.estimateExport(datasetId, request));
  }

  @PostMapping("/datasets/{datasetId}/export")
  @RequirePermission("data:export")
  public ResponseEntity<?> exportDataset(
      @PathVariable Long datasetId,
      @RequestBody ExportRequest request,
      HttpServletRequest httpRequest,
      Authentication authentication) {

    Long userId = (Long) authentication.getPrincipal();
    String username =
        userRepository.findById(userId).map(u -> u.name()).orElse(String.valueOf(userId));
    String ipAddress = httpRequest.getRemoteAddr();
    String userAgent = httpRequest.getHeader("User-Agent");

    ExportResult result =
        exportService.exportDataset(datasetId, request, userId, username, ipAddress, userAgent);

    if (result.async()) {
      return ResponseEntity.accepted().body(Map.of("jobId", result.jobId()));
    } else {
      return ResponseEntity.ok()
          .header("Content-Type", result.contentType())
          .header("Content-Disposition", buildContentDisposition(result.filename()))
          .body(result.streamingBody());
    }
  }

  @GetMapping("/exports/{jobId}/file")
  @RequirePermission("data:export")
  public ResponseEntity<StreamingResponseBody> downloadExportFile(
      @PathVariable String jobId, Authentication authentication) {

    Long userId = (Long) authentication.getPrincipal();
    Path filePath = exportService.getExportFile(jobId, userId);

    AsyncJobStatusResponse job = asyncJobService.getJobStatus(jobId, userId);
    String filename = (String) job.metadata().getOrDefault("filename", "export");
    String contentType =
        (String) job.metadata().getOrDefault("contentType", "application/octet-stream");

    StreamingResponseBody body =
        outputStream -> {
          try (InputStream is = Files.newInputStream(filePath)) {
            is.transferTo(outputStream);
          }
        };

    return ResponseEntity.ok()
        .header("Content-Type", contentType)
        .header("Content-Disposition", buildContentDisposition(filename))
        .header("Content-Length", String.valueOf(filePath.toFile().length()))
        .body(body);
  }

  @PostMapping("/query-results/export")
  @RequirePermission("data:export")
  public ResponseEntity<StreamingResponseBody> exportQueryResult(
      @RequestBody QueryResultExportRequest request) {

    StreamingResponseBody body =
        exportService.exportQueryResult(request.columnNames(), request.rows(), request.format());

    String filename =
        "query_result_"
            + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
            + "."
            + request.format().getExtension();

    return ResponseEntity.ok()
        .header("Content-Type", request.format().getContentType())
        .header("Content-Disposition", buildContentDisposition(filename))
        .body(body);
  }

  private String buildContentDisposition(String filename) {
    String sanitized = filename.replaceAll("[^a-zA-Z0-9가-힣._\\-]", "_");
    String encoded = URLEncoder.encode(sanitized, StandardCharsets.UTF_8).replace("+", "%20");
    return "attachment; filename=\"" + sanitized + "\"; filename*=UTF-8''" + encoded;
  }
}
