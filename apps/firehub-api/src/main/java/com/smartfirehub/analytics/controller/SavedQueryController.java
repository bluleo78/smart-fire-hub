package com.smartfirehub.analytics.controller;

import com.smartfirehub.analytics.dto.AnalyticsQueryRequest;
import com.smartfirehub.analytics.dto.AnalyticsQueryResponse;
import com.smartfirehub.analytics.dto.CreateSavedQueryRequest;
import com.smartfirehub.analytics.dto.SavedQueryListResponse;
import com.smartfirehub.analytics.dto.SavedQueryResponse;
import com.smartfirehub.analytics.dto.SchemaInfoResponse;
import com.smartfirehub.analytics.dto.UpdateSavedQueryRequest;
import com.smartfirehub.analytics.service.AnalyticsQueryExecutionService;
import com.smartfirehub.analytics.service.SavedQueryService;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.security.RequirePermission;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/analytics/queries")
public class SavedQueryController {

  private final SavedQueryService savedQueryService;
  private final AnalyticsQueryExecutionService executionService;

  public SavedQueryController(
      SavedQueryService savedQueryService, AnalyticsQueryExecutionService executionService) {
    this.savedQueryService = savedQueryService;
    this.executionService = executionService;
  }

  @GetMapping
  @RequirePermission("analytics:read")
  public ResponseEntity<PageResponse<SavedQueryListResponse>> listQueries(
      @RequestParam(required = false) String search,
      @RequestParam(required = false) String folder,
      @RequestParam(required = false) Boolean sharedOnly,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      Authentication authentication) {
    page = Math.max(0, page);
    size = Math.max(1, Math.min(size, 100));
    Long userId = (Long) authentication.getPrincipal();
    return ResponseEntity.ok(
        savedQueryService.list(search, folder, sharedOnly, userId, page, size));
  }

  @PostMapping
  @RequirePermission("analytics:write")
  public ResponseEntity<SavedQueryResponse> createQuery(
      @Valid @RequestBody CreateSavedQueryRequest request, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    SavedQueryResponse created = savedQueryService.create(request, userId);
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  @GetMapping("/schema")
  @RequirePermission("analytics:read")
  public ResponseEntity<SchemaInfoResponse> getSchema() {
    return ResponseEntity.ok(executionService.getSchemaInfo());
  }

  @GetMapping("/folders")
  @RequirePermission("analytics:read")
  public ResponseEntity<List<String>> getFolders(Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    return ResponseEntity.ok(savedQueryService.getFolders(userId));
  }

  @PostMapping("/execute")
  @RequirePermission("analytics:read")
  public ResponseEntity<AnalyticsQueryResponse> executeAdHoc(
      @Valid @RequestBody AnalyticsQueryRequest request) {
    int maxRows = request.maxRows() != null ? request.maxRows() : 1000;
    boolean readOnly = Boolean.TRUE.equals(request.readOnly());
    return ResponseEntity.ok(executionService.execute(request.sql(), maxRows, readOnly));
  }

  @GetMapping("/{id}")
  @RequirePermission("analytics:read")
  public ResponseEntity<SavedQueryResponse> getQuery(
      @PathVariable Long id, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    return ResponseEntity.ok(savedQueryService.getById(id, userId));
  }

  @PutMapping("/{id}")
  @RequirePermission("analytics:write")
  public ResponseEntity<SavedQueryResponse> updateQuery(
      @PathVariable Long id,
      @Valid @RequestBody UpdateSavedQueryRequest request,
      Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    return ResponseEntity.ok(savedQueryService.update(id, request, userId));
  }

  @DeleteMapping("/{id}")
  @RequirePermission("analytics:write")
  public ResponseEntity<Void> deleteQuery(@PathVariable Long id, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    savedQueryService.delete(id, userId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{id}/execute")
  @RequirePermission("analytics:read")
  public ResponseEntity<AnalyticsQueryResponse> executeSavedQuery(
      @PathVariable Long id,
      @RequestBody(required = false) AnalyticsQueryRequest request,
      Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    int maxRows = request != null && request.maxRows() != null ? request.maxRows() : 1000;
    boolean readOnly = request != null && Boolean.TRUE.equals(request.readOnly());
    return ResponseEntity.ok(savedQueryService.executeById(id, maxRows, readOnly, userId));
  }

  @PostMapping("/{id}/clone")
  @RequirePermission("analytics:write")
  public ResponseEntity<SavedQueryResponse> cloneQuery(
      @PathVariable Long id, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    SavedQueryResponse cloned = savedQueryService.clone(id, userId);
    return ResponseEntity.status(HttpStatus.CREATED).body(cloned);
  }
}
