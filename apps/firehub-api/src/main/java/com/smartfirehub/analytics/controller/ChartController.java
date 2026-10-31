package com.smartfirehub.analytics.controller;

import com.smartfirehub.analytics.dto.ChartDataResponse;
import com.smartfirehub.analytics.dto.ChartResponse;
import com.smartfirehub.analytics.dto.CreateChartRequest;
import com.smartfirehub.analytics.dto.DeleteImpactResponse;
import com.smartfirehub.analytics.dto.UpdateChartRequest;
import com.smartfirehub.analytics.service.ChartService;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.security.RequirePermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/analytics/charts")
@RequiredArgsConstructor
public class ChartController {

  private final ChartService chartService;

  @GetMapping
  @RequirePermission("analytics:read")
  public ResponseEntity<PageResponse<ChartResponse>> listCharts(
      @RequestParam(required = false) String search,
      @RequestParam(required = false) String chartType,
      @RequestParam(required = false) Long savedQueryId,
      @RequestParam(required = false) Boolean sharedOnly,
      // 「내 …」 탭 전용 — 내가 만든 항목만(남의 공유 항목 제외, WD-328). 없으면 기존처럼 내 것 + 공유된 것
      @RequestParam(required = false) Boolean mineOnly,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      Authentication authentication) {
    page = Math.max(0, page);
    size = Math.max(1, Math.min(size, 100));
    Long userId = (Long) authentication.getPrincipal();
    return ResponseEntity.ok(
        chartService.list(
            search, chartType, savedQueryId, sharedOnly, mineOnly, userId, page, size));
  }

  @PostMapping
  @RequirePermission("analytics:write")
  public ResponseEntity<ChartResponse> createChart(
      @Valid @RequestBody CreateChartRequest request, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    ChartResponse created = chartService.create(request, userId);
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  @GetMapping("/{id}")
  @RequirePermission("analytics:read")
  public ResponseEntity<ChartResponse> getChart(
      @PathVariable Long id, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    return ResponseEntity.ok(chartService.getById(id, userId));
  }

  @PutMapping("/{id}")
  @RequirePermission("analytics:write")
  public ResponseEntity<ChartResponse> updateChart(
      @PathVariable Long id,
      @Valid @RequestBody UpdateChartRequest request,
      Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    return ResponseEntity.ok(chartService.update(id, request, userId));
  }

  @DeleteMapping("/{id}")
  @RequirePermission("analytics:write")
  public ResponseEntity<Void> deleteChart(@PathVariable Long id, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    chartService.delete(id, userId);
    return ResponseEntity.noContent().build();
  }

  /**
   * 삭제 영향 범위(WD-86) — 이 차트를 지우면 CASCADE 로 함께 사라지는 대시보드 위젯. 삭제 확인 창이 연다. 삭제의 사전 조회라 삭제와 같은
   * 권한(analytics:write)·소유자 관문을 쓴다.
   */
  @GetMapping("/{id}/delete-impact")
  @RequirePermission("analytics:write")
  public ResponseEntity<DeleteImpactResponse> getDeleteImpact(
      @PathVariable Long id, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    return ResponseEntity.ok(chartService.getDeleteImpact(id, userId));
  }

  @GetMapping("/{id}/data")
  @RequirePermission("analytics:read")
  public ResponseEntity<ChartDataResponse> getChartData(
      @PathVariable Long id, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    return ResponseEntity.ok(chartService.getChartData(id, userId));
  }
}
