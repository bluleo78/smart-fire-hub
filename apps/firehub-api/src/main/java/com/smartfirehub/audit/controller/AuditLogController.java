package com.smartfirehub.audit.controller;

import com.smartfirehub.audit.dto.AuditLogResponse;
import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.audit.time.AuditTimes;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.security.RequirePermission;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
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
      @RequestParam(required = false) Long userId,
      @RequestParam(required = false) String actionType,
      @RequestParam(required = false) String resource,
      @RequestParam(required = false) String result,
      // WD-11: 오프셋이 있으면 그 순간을 저장 TZ 로 바꾼다(웹은 toISOString 의 Z 를 보낸다). 오프셋 없는 값은
      // ai-agent 호환으로 저장 벽시계 그대로. 형식 오류는 IllegalArgumentException → 400.
      @RequestParam(required = false) String startDate,
      @RequestParam(required = false) String endDate,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    // userId 파라미터: 특정 사용자(user_id)로 정확히 일치 필터링.
    // free-text search 와 별도로 동작해 동명이인/오타 노이즈 없이 사용자별 활동 추적이 가능하다 (#89).
    ZoneId storage = AuditTimes.storageZone();
    PageResponse<AuditLogResponse> logs =
        auditLogService.getAuditLogs(
            search,
            userId,
            actionType,
            resource,
            result,
            AuditTimes.parseBoundary(startDate, storage),
            AuditTimes.parseBoundary(endDate, storage),
            page,
            size);
    return ResponseEntity.ok(logs);
  }
}
