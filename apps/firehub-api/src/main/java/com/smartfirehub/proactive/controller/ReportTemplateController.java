package com.smartfirehub.proactive.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.proactive.dto.CreateReportTemplateRequest;
import com.smartfirehub.proactive.dto.ReportTemplateResponse;
import com.smartfirehub.proactive.dto.UpdateReportTemplateRequest;
import com.smartfirehub.proactive.service.ReportTemplateService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/proactive/templates")
@RequiredArgsConstructor
public class ReportTemplateController {

  private final ReportTemplateService reportTemplateService;

  @GetMapping
  @RequirePermission("proactive:read")
  public ResponseEntity<List<ReportTemplateResponse>> getTemplates(Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    return ResponseEntity.ok(reportTemplateService.getTemplates(userId));
  }

  @PostMapping
  @RequirePermission("proactive:write")
  public ResponseEntity<ReportTemplateResponse> createTemplate(
      @Valid @RequestBody CreateReportTemplateRequest request, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    ReportTemplateResponse response = reportTemplateService.createTemplate(request, userId);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @PutMapping("/{id}")
  @RequirePermission("proactive:write")
  public ResponseEntity<Void> updateTemplate(
      @PathVariable Long id,
      @Valid @RequestBody UpdateReportTemplateRequest request,
      Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    reportTemplateService.updateTemplate(id, request, userId);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{id}")
  @RequirePermission("proactive:write")
  public ResponseEntity<Void> deleteTemplate(@PathVariable Long id, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    reportTemplateService.deleteTemplate(id, userId);
    return ResponseEntity.noContent().build();
  }
}
