package com.smartfirehub.analytics.controller;

import com.smartfirehub.analytics.dto.AddWidgetRequest;
import com.smartfirehub.analytics.dto.CreateDashboardRequest;
import com.smartfirehub.analytics.dto.DashboardDataResponse;
import com.smartfirehub.analytics.dto.DashboardResponse;
import com.smartfirehub.analytics.dto.UpdateDashboardRequest;
import com.smartfirehub.analytics.dto.UpdateWidgetLayoutRequest;
import com.smartfirehub.analytics.dto.UpdateWidgetRequest;
import com.smartfirehub.analytics.service.AnalyticsDashboardService;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.security.RequirePermission;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analytics/dashboards")
public class AnalyticsDashboardController {

  private final AnalyticsDashboardService dashboardService;

  public AnalyticsDashboardController(AnalyticsDashboardService dashboardService) {
    this.dashboardService = dashboardService;
  }

  @GetMapping
  @RequirePermission("analytics:read")
  public ResponseEntity<PageResponse<DashboardResponse>> listDashboards(
      @RequestParam(required = false) String search,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      Authentication authentication) {
    page = Math.max(0, page);
    size = Math.max(1, Math.min(size, 100));
    Long userId = (Long) authentication.getPrincipal();
    return ResponseEntity.ok(dashboardService.list(search, userId, page, size));
  }

  @PostMapping
  @RequirePermission("analytics:write")
  public ResponseEntity<DashboardResponse> createDashboard(
      @Valid @RequestBody CreateDashboardRequest request, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    DashboardResponse created = dashboardService.create(request, userId);
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  @GetMapping("/{id}")
  @RequirePermission("analytics:read")
  public ResponseEntity<DashboardResponse> getDashboard(
      @PathVariable Long id, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    return ResponseEntity.ok(dashboardService.getById(id, userId));
  }

  @PutMapping("/{id}")
  @RequirePermission("analytics:write")
  public ResponseEntity<DashboardResponse> updateDashboard(
      @PathVariable Long id,
      @Valid @RequestBody UpdateDashboardRequest request,
      Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    return ResponseEntity.ok(dashboardService.update(id, request, userId));
  }

  @DeleteMapping("/{id}")
  @RequirePermission("analytics:write")
  public ResponseEntity<Void> deleteDashboard(
      @PathVariable Long id, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    dashboardService.delete(id, userId);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{id}/data")
  @RequirePermission("analytics:read")
  public ResponseEntity<DashboardDataResponse> getDashboardData(
      @PathVariable Long id, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    return ResponseEntity.ok(dashboardService.getDashboardData(id, userId));
  }

  @PostMapping("/{id}/widgets")
  @RequirePermission("analytics:write")
  public ResponseEntity<DashboardResponse> addWidget(
      @PathVariable Long id,
      @Valid @RequestBody AddWidgetRequest request,
      Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(dashboardService.addWidget(id, request, userId));
  }

  @PutMapping("/{id}/widgets/{wId}")
  @RequirePermission("analytics:write")
  public ResponseEntity<DashboardResponse> updateWidget(
      @PathVariable Long id,
      @PathVariable Long wId,
      @Valid @RequestBody UpdateWidgetRequest request,
      Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    return ResponseEntity.ok(dashboardService.updateWidget(id, wId, request, userId));
  }

  @DeleteMapping("/{id}/widgets/{wId}")
  @RequirePermission("analytics:write")
  public ResponseEntity<Void> removeWidget(
      @PathVariable Long id, @PathVariable Long wId, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    dashboardService.removeWidget(id, wId, userId);
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/{id}/widgets/layout")
  @RequirePermission("analytics:write")
  public ResponseEntity<Void> updateWidgetLayout(
      @PathVariable Long id,
      @Valid @RequestBody UpdateWidgetLayoutRequest request,
      Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    dashboardService.updateWidgetLayout(id, request, userId);
    return ResponseEntity.noContent().build();
  }
}
