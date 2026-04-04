package com.smartfirehub.proactive.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.proactive.dto.CreateProactiveJobRequest;
import com.smartfirehub.proactive.dto.ProactiveJobExecutionResponse;
import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.dto.ProactiveResult;
import com.smartfirehub.proactive.dto.RecipientResponse;
import com.smartfirehub.proactive.dto.UpdateProactiveJobRequest;
import com.smartfirehub.proactive.service.PdfExportService;
import com.smartfirehub.proactive.service.ProactiveJobService;
import com.smartfirehub.proactive.util.ProactiveConfigParser;
import jakarta.validation.Valid;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/proactive/jobs")
@RequiredArgsConstructor
public class ProactiveJobController {

  private final ProactiveJobService proactiveJobService;
  private final PdfExportService pdfExportService;
  private final ObjectMapper objectMapper;

  @GetMapping
  @RequirePermission("proactive:read")
  public ResponseEntity<List<ProactiveJobResponse>> getJobs(Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    return ResponseEntity.ok(proactiveJobService.getJobs(userId));
  }

  @GetMapping("/{id}")
  @RequirePermission("proactive:read")
  public ResponseEntity<ProactiveJobResponse> getJob(
      @PathVariable Long id, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    return ResponseEntity.ok(proactiveJobService.getJob(id, userId));
  }

  @PostMapping
  @RequirePermission("proactive:write")
  public ResponseEntity<ProactiveJobResponse> createJob(
      @Valid @RequestBody CreateProactiveJobRequest request, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    ProactiveConfigParser.parseChannels(request.config()).stream()
        .flatMap(ch -> ch.recipientEmails().stream())
        .forEach(ProactiveConfigParser::validateEmail);
    ProactiveJobResponse response = proactiveJobService.createJob(request, userId);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @PutMapping("/{id}")
  @RequirePermission("proactive:write")
  public ResponseEntity<Void> updateJob(
      @PathVariable Long id,
      @RequestBody UpdateProactiveJobRequest request,
      Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    if (request.config() != null) {
      ProactiveConfigParser.parseChannels(request.config()).stream()
          .flatMap(ch -> ch.recipientEmails().stream())
          .forEach(ProactiveConfigParser::validateEmail);
    }
    proactiveJobService.updateJob(id, request, userId);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{id}")
  @RequirePermission("proactive:write")
  public ResponseEntity<Void> deleteJob(@PathVariable Long id, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    proactiveJobService.deleteJob(id, userId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{id}/execute")
  @RequirePermission("proactive:write")
  public ResponseEntity<Void> executeJob(@PathVariable Long id, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    proactiveJobService.executeJob(id, userId);
    return ResponseEntity.accepted().build();
  }

  @GetMapping("/{id}/executions")
  @RequirePermission("proactive:read")
  public ResponseEntity<List<ProactiveJobExecutionResponse>> getExecutions(
      @PathVariable Long id,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset,
      Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    return ResponseEntity.ok(proactiveJobService.getExecutions(id, userId, limit, offset));
  }

  @GetMapping("/{jobId}/executions/{executionId}/pdf")
  @RequirePermission("proactive:read")
  public ResponseEntity<byte[]> downloadExecutionPdf(
      @PathVariable Long jobId, @PathVariable Long executionId, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();

    // Validate job ownership
    ProactiveJobResponse job = proactiveJobService.getJob(jobId, userId);

    // Validate execution
    ProactiveJobExecutionResponse execution = proactiveJobService.getExecution(executionId);
    if (!jobId.equals(execution.jobId())) {
      return ResponseEntity.badRequest().build();
    }
    if (!"COMPLETED".equals(execution.status()) || execution.result() == null) {
      return ResponseEntity.badRequest().build();
    }

    // Convert result map to ProactiveResult
    ProactiveResult result = objectMapper.convertValue(execution.result(), ProactiveResult.class);

    // Generate PDF
    byte[] pdfBytes = pdfExportService.generatePdf(result, job.name());

    // Encode filename for Content-Disposition
    String encodedName =
        URLEncoder.encode(result.title() + ".pdf", StandardCharsets.UTF_8).replace("+", "%20");

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_PDF);
    headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName);

    return ResponseEntity.ok().headers(headers).body(pdfBytes);
  }

  @GetMapping("/recipients")
  @RequirePermission("proactive:read")
  public ResponseEntity<List<RecipientResponse>> searchRecipients(
      @RequestParam(required = false, defaultValue = "") String search) {
    return ResponseEntity.ok(proactiveJobService.searchRecipients(search));
  }
}
