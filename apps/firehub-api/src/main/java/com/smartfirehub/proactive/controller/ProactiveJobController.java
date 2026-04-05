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
    ProactiveJobResponse job = proactiveJobService.getJob(jobId, userId);
    ProactiveResult result = getValidatedResult(jobId, executionId);
    if (result == null) {
      return ResponseEntity.badRequest().build();
    }

    byte[] pdfBytes = pdfExportService.generatePdf(result, job.name());
    String encodedName =
        URLEncoder.encode(result.effectiveTitle(job.name()) + ".pdf", StandardCharsets.UTF_8)
            .replace("+", "%20");

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_PDF);
    headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName);

    return ResponseEntity.ok().headers(headers).body(pdfBytes);
  }

  /**
   * 실행 결과의 HTML 리포트를 반환한다. htmlContent가 없으면 404를 반환한다. 프론트엔드 ReportViewerPage에서 DOMPurify를 거쳐 렌더링에
   * 사용한다.
   */
  @GetMapping("/{jobId}/executions/{executionId}/html")
  @RequirePermission("proactive:read")
  public ResponseEntity<String> getExecutionHtml(
      @PathVariable Long jobId, @PathVariable Long executionId, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    proactiveJobService.getJob(jobId, userId);
    ProactiveResult result = getValidatedResult(jobId, executionId);
    if (result == null) {
      return ResponseEntity.badRequest().build();
    }

    if (result.htmlContent() == null || result.htmlContent().isBlank()) {
      return ResponseEntity.notFound().build();
    }

    return ResponseEntity.ok()
        .contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8))
        .body(result.htmlContent());
  }

  /**
   * execution이 해당 job에 속하고 COMPLETED 상태인지 검증한 후 ProactiveResult를 반환한다. 검증 실패 시 null을 반환하며, 호출측에서
   * badRequest 등으로 처리한다.
   */
  private ProactiveResult getValidatedResult(Long jobId, Long executionId) {
    ProactiveJobExecutionResponse execution = proactiveJobService.getExecution(executionId);
    if (!jobId.equals(execution.jobId())) {
      return null;
    }
    if (!"COMPLETED".equals(execution.status()) || execution.result() == null) {
      return null;
    }
    return objectMapper.convertValue(execution.result(), ProactiveResult.class);
  }

  @GetMapping("/recipients")
  @RequirePermission("proactive:read")
  public ResponseEntity<List<RecipientResponse>> searchRecipients(
      @RequestParam(required = false, defaultValue = "") String search) {
    return ResponseEntity.ok(proactiveJobService.searchRecipients(search));
  }
}
