package com.smartfirehub.audit.controller;

import com.smartfirehub.audit.dto.AuditLogResponse;
import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.security.RequirePermission;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

  private final AuditLogService auditLogService;

  /**
   * 감사 로그 목록 조회
   *
   * <p>날짜 범위(startDate ~ endDate)를 포함한 복합 필터로 감사 로그를 페이지네이션 조회한다.
   */
  @GetMapping
  @RequirePermission("audit:read")
  public ResponseEntity<PageResponse<AuditLogResponse>> getAuditLogs(
      @RequestParam(required = false) String search,
      @RequestParam(required = false) String actionType,
      @RequestParam(required = false) String resource,
      @RequestParam(required = false) String result,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          LocalDateTime startDate,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          LocalDateTime endDate,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    PageResponse<AuditLogResponse> logs =
        auditLogService.getAuditLogs(
            search, actionType, resource, result, startDate, endDate, page, size);
    return ResponseEntity.ok(logs);
  }
}
