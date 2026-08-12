package com.smartfirehub.proactive.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.proactive.dto.ReportListItemResponse;
import com.smartfirehub.proactive.service.ProactiveJobService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 전역 리포트 목록 컨트롤러.
 *
 * <p>ProactiveJobController 는 /jobs 하위로 매핑되어 있어 잡에 속하지 않는 횡단 조회를 담을 수 없다. 리포트는 잡 실행의
 * 산출물이지만 사용자 입장에서는 잡과 무관하게 한 곳에서 열람하는 대상이므로 별도 리소스로 노출한다.
 */
@RestController
@RequestMapping("/api/v1/proactive/reports")
@RequiredArgsConstructor
public class ProactiveReportController {

  /** 한 번에 조회할 수 있는 리포트 최대 건수 — 과대 limit 요청으로 전량 조회되는 것을 막는다. */
  private static final int MAX_LIMIT = 100;

  private final ProactiveJobService proactiveJobService;

  /** 인증 주체의 소유 잡이 생성한 리포트를 최신순으로 조회한다. */
  @GetMapping
  @RequirePermission("proactive:read")
  public ResponseEntity<List<ReportListItemResponse>> getReports(
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset,
      Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    // 음수 limit 은 jOOQ 를 거쳐 Postgres 의 "LIMIT must not be negative" 로 터져 500 이 된다.
    // 과대 요청도 전량 조회로 이어지므로 경계에서 클램프한다.
    int safeLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
    int safeOffset = Math.max(offset, 0);
    return ResponseEntity.ok(proactiveJobService.getReports(userId, safeLimit, safeOffset));
  }
}
