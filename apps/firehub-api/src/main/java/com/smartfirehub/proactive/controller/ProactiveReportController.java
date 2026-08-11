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

  private final ProactiveJobService proactiveJobService;

  /** 인증 주체의 소유 잡이 생성한 리포트를 최신순으로 조회한다. */
  @GetMapping
  @RequirePermission("proactive:read")
  public ResponseEntity<List<ReportListItemResponse>> getReports(
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset,
      Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    return ResponseEntity.ok(proactiveJobService.getReports(userId, limit, offset));
  }
}
