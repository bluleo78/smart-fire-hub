package com.smartfirehub.platform.controller;

import com.smartfirehub.audit.dto.AuditLogResponse;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.platform.service.PlatformAuditLogService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영자 평면 플랫폼 감사 로그 조회(WD-4). 응답 DTO 는 테넌트 감사 로그({@code /api/v1/admin/audit-logs})와 같은 {@link
 * AuditLogResponse} 다.
 *
 * <p><b>권한 코드를 새로 만들지 않은 이유</b>({@link PlatformAccountController} 와 같은 판단): platform_role 은
 * SUPER_ADMIN 하나뿐이라 새 코드가 오늘 좁히는 것이 없다. 감사 행이 계정·로그인(사람) 사건이라 조회 계열 중 가장 가까운 {@code
 * platform:member:read} 를 쓴다. 두 번째 플랫폼 롤이 생기면 {@code platform:audit:read} 를 신설한다.
 */
@RestController
@RequestMapping("/api/platform/audit-logs")
@RequiredArgsConstructor
public class PlatformAuditLogController {

  private final PlatformAuditLogService platformAuditLogService;

  /** 기간(from·to, yyyy-MM-dd, 양끝 포함)·행위자·대상·액션 필터 + 페이지네이션. 잘못된 값은 400. */
  @GetMapping
  @RequirePermission("platform:member:read")
  public ResponseEntity<PageResponse<AuditLogResponse>> search(
      @RequestParam(required = false) String actor,
      @RequestParam(required = false) String target,
      @RequestParam(required = false) String actionType,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ResponseEntity.ok(
        platformAuditLogService.search(actor, target, actionType, from, to, page, size));
  }
}
