package com.smartfirehub.proactive.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.proactive.dto.CreateProactiveJobRequest;
import com.smartfirehub.proactive.dto.ProactiveJobExecutionResponse;
import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.dto.RecipientResponse;
import com.smartfirehub.proactive.dto.UpdateProactiveJobRequest;
import com.smartfirehub.proactive.service.ProactiveJobService;
import com.smartfirehub.proactive.util.ProactiveConfigParser;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/proactive/jobs")
@RequiredArgsConstructor
public class ProactiveJobController {

  private final ProactiveJobService proactiveJobService;

  @GetMapping
  @RequirePermission("proactive:read")
  public ResponseEntity<List<ProactiveJobResponse>> getJobs(Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    return ResponseEntity.ok(proactiveJobService.getJobs(userId));
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

  @GetMapping("/recipients")
  @RequirePermission("proactive:read")
  public ResponseEntity<List<RecipientResponse>> searchRecipients(
      @RequestParam(required = false, defaultValue = "") String search) {
    return ResponseEntity.ok(proactiveJobService.searchRecipients(search));
  }
}
