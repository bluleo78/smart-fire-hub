package com.smartfirehub.audit.controller;

import com.smartfirehub.audit.dto.AuditLogResponse;
import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.security.RequirePermission;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/audit-logs")
public class AuditLogController {

  private final AuditLogService auditLogService;

  public AuditLogController(AuditLogService auditLogService) {
    this.auditLogService = auditLogService;
  }

  @GetMapping
  @RequirePermission("audit:read")
  public ResponseEntity<PageResponse<AuditLogResponse>> getAuditLogs(
      @RequestParam(required = false) String search,
      @RequestParam(required = false) String actionType,
      @RequestParam(required = false) String resource,
      @RequestParam(required = false) String result,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    PageResponse<AuditLogResponse> logs =
        auditLogService.getAuditLogs(search, actionType, resource, result, page, size);
    return ResponseEntity.ok(logs);
  }
}
