package com.smartfirehub.dashboard.controller;

import com.smartfirehub.dashboard.dto.ActivityFeedResponse;
import com.smartfirehub.dashboard.dto.AttentionItemResponse;
import com.smartfirehub.dashboard.dto.DashboardStatsResponse;
import com.smartfirehub.dashboard.dto.SystemHealthResponse;
import com.smartfirehub.dashboard.service.DashboardService;
import com.smartfirehub.global.security.RequirePermission;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

  private final DashboardService dashboardService;

  @GetMapping("/stats")
  @RequirePermission("dataset:read")
  public ResponseEntity<DashboardStatsResponse> getStats() {
    return ResponseEntity.ok(dashboardService.getStats());
  }

  @GetMapping("/health")
  @RequirePermission("dataset:read")
  public ResponseEntity<SystemHealthResponse> getSystemHealth() {
    return ResponseEntity.ok(dashboardService.getSystemHealth());
  }

  @GetMapping("/attention")
  @RequirePermission("dataset:read")
  public ResponseEntity<List<AttentionItemResponse>> getAttentionItems() {
    return ResponseEntity.ok(dashboardService.getAttentionItems());
  }

  @GetMapping("/activity")
  @RequirePermission("dataset:read")
  public ResponseEntity<ActivityFeedResponse> getActivityFeed(
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String severity,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    int safePage = Math.max(0, page);
    int safeSize = Math.max(1, Math.min(size, 100));
    return ResponseEntity.ok(dashboardService.getActivityFeed(type, severity, safePage, safeSize));
  }
}
