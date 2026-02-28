package com.smartfirehub.dataset.controller;

import com.smartfirehub.dataset.dto.*;
import com.smartfirehub.dataset.service.ApiImportService;
import com.smartfirehub.dataset.service.DatasetDataService;
import com.smartfirehub.dataset.service.DatasetFavoriteService;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.dataset.service.DatasetTagService;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.security.RequirePermission;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/datasets")
public class DatasetController {

  private final DatasetService datasetService;
  private final DatasetDataService datasetDataService;
  private final DatasetFavoriteService datasetFavoriteService;
  private final DatasetTagService datasetTagService;
  private final ApiImportService apiImportService;

  public DatasetController(
      DatasetService datasetService,
      DatasetDataService datasetDataService,
      DatasetFavoriteService datasetFavoriteService,
      DatasetTagService datasetTagService,
      ApiImportService apiImportService) {
    this.datasetService = datasetService;
    this.datasetDataService = datasetDataService;
    this.datasetFavoriteService = datasetFavoriteService;
    this.datasetTagService = datasetTagService;
    this.apiImportService = apiImportService;
  }

  @GetMapping
  @RequirePermission("dataset:read")
  public ResponseEntity<PageResponse<DatasetResponse>> getDatasets(
      @RequestParam(required = false) Long categoryId,
      @RequestParam(required = false) String datasetType,
      @RequestParam(required = false) String search,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "false") boolean favoriteOnly,
      Authentication authentication) {
    page = Math.max(0, page);
    size = Math.max(1, Math.min(size, 100));
    Long userId = (Long) authentication.getPrincipal();
    PageResponse<DatasetResponse> response =
        datasetService.getDatasets(
            categoryId, datasetType, search, page, size, userId, status, favoriteOnly);
    return ResponseEntity.ok(response);
  }

  @PostMapping
  @RequirePermission("dataset:write")
  public ResponseEntity<DatasetDetailResponse> createDataset(
      @Valid @RequestBody CreateDatasetRequest request, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    DatasetDetailResponse dataset = datasetService.createDataset(request, userId);
    return ResponseEntity.status(HttpStatus.CREATED).body(dataset);
  }

  @GetMapping("/{id}")
  @RequirePermission("dataset:read")
  public ResponseEntity<DatasetDetailResponse> getDatasetById(
      @PathVariable Long id, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    DatasetDetailResponse dataset = datasetService.getDatasetById(id, userId);
    return ResponseEntity.ok(dataset);
  }

  @PutMapping("/{id}")
  @RequirePermission("dataset:write")
  public ResponseEntity<Void> updateDataset(
      @PathVariable Long id,
      @Valid @RequestBody UpdateDatasetRequest request,
      Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    datasetService.updateDataset(id, request, userId);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{id}")
  @RequirePermission("dataset:delete")
  public ResponseEntity<Void> deleteDataset(@PathVariable Long id) {
    datasetService.deleteDataset(id);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{id}/columns")
  @RequirePermission("dataset:write")
  public ResponseEntity<DatasetColumnResponse> addColumn(
      @PathVariable Long id, @RequestBody AddColumnRequest request) {
    DatasetColumnResponse column = datasetService.addColumn(id, request);
    return ResponseEntity.status(HttpStatus.CREATED).body(column);
  }

  @PutMapping("/{id}/columns/{columnId}")
  @RequirePermission("dataset:write")
  public ResponseEntity<Void> updateColumn(
      @PathVariable Long id,
      @PathVariable Long columnId,
      @RequestBody UpdateColumnRequest request) {
    datasetService.updateColumn(id, columnId, request);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{id}/columns/{columnId}")
  @RequirePermission("dataset:write")
  public ResponseEntity<Void> deleteColumn(@PathVariable Long id, @PathVariable Long columnId) {
    datasetService.deleteColumn(id, columnId);
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/{id}/columns/reorder")
  @RequirePermission("dataset:write")
  public ResponseEntity<Void> reorderColumns(
      @PathVariable Long id, @RequestBody ReorderColumnsRequest request) {
    datasetService.reorderColumns(id, request);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{id}/stats")
  @RequirePermission("data:read")
  public ResponseEntity<List<ColumnStatsResponse>> getDatasetStats(@PathVariable Long id) {
    List<ColumnStatsResponse> stats = datasetDataService.getDatasetStats(id);
    return ResponseEntity.ok(stats);
  }

  @PostMapping("/{id}/data/delete")
  @RequirePermission("data:delete")
  public ResponseEntity<DataDeleteResponse> deleteDataRows(
      @PathVariable Long id, @RequestBody DataDeleteRequest request) {
    if (request.rowIds() == null || request.rowIds().isEmpty()) {
      return ResponseEntity.badRequest().build();
    }
    if (request.rowIds().size() > 1000) {
      return ResponseEntity.badRequest().build();
    }
    DataDeleteResponse response = datasetDataService.deleteDataRows(id, request.rowIds());
    return ResponseEntity.ok(response);
  }

  @PostMapping("/{id}/data/truncate")
  @RequirePermission("data:delete")
  public ResponseEntity<DataDeleteResponse> truncateDatasetData(@PathVariable Long id) {
    DataDeleteResponse response = datasetDataService.truncateDatasetData(id);
    return ResponseEntity.ok(response);
  }

  @GetMapping("/{id}/data/count")
  @RequirePermission("data:read")
  public ResponseEntity<RowCountResponse> getRowCount(@PathVariable Long id) {
    RowCountResponse response = datasetDataService.getRowCount(id);
    return ResponseEntity.ok(response);
  }

  @PostMapping("/{id}/data/replace")
  @RequirePermission("data:import")
  public ResponseEntity<BatchRowDataResponse> replaceDatasetData(
      @PathVariable Long id, @Valid @RequestBody BatchRowDataRequest request) {
    BatchRowDataResponse response = datasetDataService.replaceDatasetData(id, request);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @GetMapping("/{id}/data")
  @RequirePermission("data:read")
  public ResponseEntity<DataQueryResponse> getDatasetData(
      @PathVariable Long id,
      @RequestParam(required = false) String search,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @RequestParam(required = false) String sortBy,
      @RequestParam(defaultValue = "ASC") String sortDir,
      @RequestParam(defaultValue = "true") boolean includeTotalCount) {
    page = Math.max(0, page);
    size = Math.max(1, Math.min(size, 200));
    String sanitizedSearch =
        search != null && search.length() > 200 ? search.substring(0, 200) : search;
    if (!"ASC".equalsIgnoreCase(sortDir) && !"DESC".equalsIgnoreCase(sortDir)) {
      sortDir = "ASC";
    }
    DataQueryResponse response =
        datasetDataService.getDatasetData(
            id, sanitizedSearch, page, size, sortBy, sortDir.toUpperCase(), includeTotalCount);
    return ResponseEntity.ok(response);
  }

  // --- Phase 3-3: Favorites ---

  @PostMapping("/{id}/favorite")
  @RequirePermission("dataset:read")
  public ResponseEntity<FavoriteToggleResponse> toggleFavorite(
      @PathVariable Long id, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    FavoriteToggleResponse response = datasetFavoriteService.toggleFavorite(id, userId);
    return ResponseEntity.ok(response);
  }

  // --- Phase 4-3: Tags ---

  @GetMapping("/tags")
  @RequirePermission("dataset:read")
  public ResponseEntity<List<String>> getAllTags() {
    return ResponseEntity.ok(datasetTagService.getAllDistinctTags());
  }

  @PostMapping("/{id}/tags")
  @RequirePermission("dataset:write")
  public ResponseEntity<Void> addTag(
      @PathVariable Long id, @RequestBody AddTagRequest request, Authentication authentication) {
    if (request.tagName() == null
        || request.tagName().isBlank()
        || request.tagName().length() > 50
        || !request.tagName().matches("[a-zA-Z0-9가-힣_\\-]+")) {
      return ResponseEntity.badRequest().build();
    }
    Long userId = (Long) authentication.getPrincipal();
    try {
      datasetTagService.addTag(id, request.tagName(), userId);
      return ResponseEntity.status(HttpStatus.CREATED).build();
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
  }

  @DeleteMapping("/{id}/tags/{tagName}")
  @RequirePermission("dataset:write")
  public ResponseEntity<Void> deleteTag(@PathVariable Long id, @PathVariable String tagName) {
    datasetTagService.deleteTag(id, tagName);
    return ResponseEntity.noContent().build();
  }

  // --- Phase 6-1: Status ---

  @PutMapping("/{id}/status")
  @RequirePermission("dataset:write")
  public ResponseEntity<DatasetDetailResponse> updateStatus(
      @PathVariable Long id,
      @RequestBody UpdateStatusRequest request,
      Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    DatasetDetailResponse response = datasetService.updateStatus(id, request, userId);
    return ResponseEntity.ok(response);
  }

  // --- Phase 6-4: Description Propagation ---

  @PostMapping("/{id}/propagate-descriptions")
  @RequirePermission("dataset:write")
  public ResponseEntity<Void> propagateDescriptions(@PathVariable Long id) {
    datasetDataService.propagateDescriptions(id);
    return ResponseEntity.noContent().build();
  }

  // =========================================================================
  // SQL Query (Ad-hoc)
  // =========================================================================

  @PostMapping("/{id}/query")
  @RequirePermission("data:import")
  public ResponseEntity<SqlQueryResponse> executeQuery(
      @PathVariable Long id,
      @Valid @RequestBody SqlQueryRequest request,
      Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    SqlQueryResponse response = datasetDataService.executeQuery(id, request, userId);
    return ResponseEntity.ok(response);
  }

  @GetMapping("/{id}/queries")
  @RequirePermission("data:read")
  public ResponseEntity<PageResponse<QueryHistoryResponse>> getQueryHistory(
      @PathVariable Long id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    page = Math.max(0, page);
    size = Math.max(1, Math.min(size, 100));
    PageResponse<QueryHistoryResponse> response =
        datasetDataService.getQueryHistory(id, page, size);
    return ResponseEntity.ok(response);
  }

  // =========================================================================
  // Manual Row Entry
  // =========================================================================

  @PostMapping("/{id}/data/rows")
  @RequirePermission("data:import")
  public ResponseEntity<RowDataResponse> addRow(
      @PathVariable Long id, @Valid @RequestBody RowDataRequest request) {
    RowDataResponse response = datasetDataService.addRow(id, request);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @PostMapping("/{id}/data/rows/batch")
  @RequirePermission("data:import")
  public ResponseEntity<BatchRowDataResponse> addRowsBatch(
      @PathVariable Long id, @Valid @RequestBody BatchRowDataRequest request) {
    BatchRowDataResponse response = datasetDataService.addRowsBatch(id, request);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @PutMapping("/{id}/data/rows/{rowId}")
  @RequirePermission("data:import")
  public ResponseEntity<Void> updateRow(
      @PathVariable Long id, @PathVariable Long rowId, @Valid @RequestBody RowDataRequest request) {
    datasetDataService.updateRow(id, rowId, request);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{id}/data/rows/{rowId}")
  @RequirePermission("data:read")
  public ResponseEntity<RowDataResponse> getRow(@PathVariable Long id, @PathVariable Long rowId) {
    RowDataResponse response = datasetDataService.getRow(id, rowId);
    return ResponseEntity.ok(response);
  }

  // --- API Import ---

  @PostMapping("/{id}/api-import")
  @RequirePermission("pipeline:write")
  public ResponseEntity<ApiImportResponse> createApiImport(
      @PathVariable Long id, @RequestBody ApiImportRequest request, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    ApiImportResponse response = apiImportService.createApiImport(id, request, userId);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  // =========================================================================
  // Clone/Copy Dataset
  // =========================================================================

  @PostMapping("/{id}/clone")
  @RequirePermission("dataset:write")
  public ResponseEntity<DatasetDetailResponse> cloneDataset(
      @PathVariable Long id,
      @Valid @RequestBody CloneDatasetRequest request,
      Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    DatasetDetailResponse response = datasetService.cloneDataset(id, request, userId);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }
}
