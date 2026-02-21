package com.smartfirehub.dataset.controller;

import com.smartfirehub.dataset.dto.*;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.security.RequirePermission;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/datasets")
public class DatasetController {

    private final DatasetService datasetService;

    public DatasetController(DatasetService datasetService) {
        this.datasetService = datasetService;
    }

    private Long currentUserId() {
        return Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
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
            @RequestParam(defaultValue = "false") boolean favoriteOnly) {
        page = Math.max(0, page);
        size = Math.max(1, Math.min(size, 100));
        Long userId = currentUserId();
        PageResponse<DatasetResponse> response = datasetService.getDatasets(
                categoryId, datasetType, search, page, size, userId, status, favoriteOnly);
        return ResponseEntity.ok(response);
    }

    @PostMapping
    @RequirePermission("dataset:write")
    public ResponseEntity<DatasetDetailResponse> createDataset(@RequestBody CreateDatasetRequest request) {
        Long userId = currentUserId();
        DatasetDetailResponse dataset = datasetService.createDataset(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(dataset);
    }

    @GetMapping("/{id}")
    @RequirePermission("dataset:read")
    public ResponseEntity<DatasetDetailResponse> getDatasetById(@PathVariable Long id) {
        Long userId = currentUserId();
        DatasetDetailResponse dataset = datasetService.getDatasetById(id, userId);
        return ResponseEntity.ok(dataset);
    }

    @PutMapping("/{id}")
    @RequirePermission("dataset:write")
    public ResponseEntity<Void> updateDataset(@PathVariable Long id, @RequestBody UpdateDatasetRequest request) {
        Long userId = currentUserId();
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
    public ResponseEntity<DatasetColumnResponse> addColumn(@PathVariable Long id, @RequestBody AddColumnRequest request) {
        DatasetColumnResponse column = datasetService.addColumn(id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(column);
    }

    @PutMapping("/{id}/columns/{columnId}")
    @RequirePermission("dataset:write")
    public ResponseEntity<Void> updateColumn(@PathVariable Long id, @PathVariable Long columnId, @RequestBody UpdateColumnRequest request) {
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
    public ResponseEntity<Void> reorderColumns(@PathVariable Long id, @RequestBody ReorderColumnsRequest request) {
        datasetService.reorderColumns(id, request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/stats")
    @RequirePermission("data:read")
    public ResponseEntity<List<ColumnStatsResponse>> getDatasetStats(@PathVariable Long id) {
        List<ColumnStatsResponse> stats = datasetService.getDatasetStats(id);
        return ResponseEntity.ok(stats);
    }

    @PostMapping("/{id}/data/delete")
    @RequirePermission("data:delete")
    public ResponseEntity<DataDeleteResponse> deleteDataRows(@PathVariable Long id, @RequestBody DataDeleteRequest request) {
        if (request.rowIds() == null || request.rowIds().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        if (request.rowIds().size() > 1000) {
            return ResponseEntity.badRequest().build();
        }
        DataDeleteResponse response = datasetService.deleteDataRows(id, request.rowIds());
        return ResponseEntity.ok(response);
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
        String sanitizedSearch = search != null && search.length() > 200 ? search.substring(0, 200) : search;
        if (!"ASC".equalsIgnoreCase(sortDir) && !"DESC".equalsIgnoreCase(sortDir)) {
            sortDir = "ASC";
        }
        DataQueryResponse response = datasetService.getDatasetData(id, sanitizedSearch, page, size, sortBy, sortDir.toUpperCase(), includeTotalCount);
        return ResponseEntity.ok(response);
    }

    // --- Phase 3-3: Favorites ---

    @PostMapping("/{id}/favorite")
    @RequirePermission("dataset:read")
    public ResponseEntity<FavoriteToggleResponse> toggleFavorite(@PathVariable Long id) {
        Long userId = currentUserId();
        FavoriteToggleResponse response = datasetService.toggleFavorite(id, userId);
        return ResponseEntity.ok(response);
    }

    // --- Phase 4-3: Tags ---

    @GetMapping("/tags")
    @RequirePermission("dataset:read")
    public ResponseEntity<List<String>> getAllTags() {
        return ResponseEntity.ok(datasetService.getAllDistinctTags());
    }

    @PostMapping("/{id}/tags")
    @RequirePermission("dataset:write")
    public ResponseEntity<Void> addTag(@PathVariable Long id, @RequestBody AddTagRequest request) {
        if (request.tagName() == null || request.tagName().isBlank()
                || request.tagName().length() > 50
                || !request.tagName().matches("[a-zA-Z0-9가-힣_\\-]+")) {
            return ResponseEntity.badRequest().build();
        }
        Long userId = currentUserId();
        try {
            datasetService.addTag(id, request.tagName(), userId);
            return ResponseEntity.status(HttpStatus.CREATED).build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    @DeleteMapping("/{id}/tags/{tagName}")
    @RequirePermission("dataset:write")
    public ResponseEntity<Void> deleteTag(@PathVariable Long id, @PathVariable String tagName) {
        datasetService.deleteTag(id, tagName);
        return ResponseEntity.noContent().build();
    }

    // --- Phase 6-1: Status ---

    @PutMapping("/{id}/status")
    @RequirePermission("dataset:write")
    public ResponseEntity<DatasetDetailResponse> updateStatus(@PathVariable Long id, @RequestBody UpdateStatusRequest request) {
        Long userId = currentUserId();
        DatasetDetailResponse response = datasetService.updateStatus(id, request, userId);
        return ResponseEntity.ok(response);
    }

    // --- Phase 6-4: Description Propagation ---

    @PostMapping("/{id}/propagate-descriptions")
    @RequirePermission("dataset:write")
    public ResponseEntity<Void> propagateDescriptions(@PathVariable Long id) {
        datasetService.propagateDescriptions(id);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // SQL Query (Ad-hoc)
    // =========================================================================

    @PostMapping("/{id}/query")
    @RequirePermission("data:import")
    public ResponseEntity<SqlQueryResponse> executeQuery(@PathVariable Long id,
                                                          @Valid @RequestBody SqlQueryRequest request) {
        Long userId = currentUserId();
        SqlQueryResponse response = datasetService.executeQuery(id, request, userId);
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
        PageResponse<QueryHistoryResponse> response = datasetService.getQueryHistory(id, page, size);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Manual Row Entry
    // =========================================================================

    @PostMapping("/{id}/data/rows")
    @RequirePermission("data:import")
    public ResponseEntity<RowDataResponse> addRow(@PathVariable Long id,
                                                   @Valid @RequestBody RowDataRequest request) {
        RowDataResponse response = datasetService.addRow(id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{id}/data/rows/batch")
    @RequirePermission("data:import")
    public ResponseEntity<BatchRowDataResponse> addRowsBatch(@PathVariable Long id,
                                                              @Valid @RequestBody BatchRowDataRequest request) {
        BatchRowDataResponse response = datasetService.addRowsBatch(id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}/data/rows/{rowId}")
    @RequirePermission("data:import")
    public ResponseEntity<Void> updateRow(@PathVariable Long id,
                                           @PathVariable Long rowId,
                                           @Valid @RequestBody RowDataRequest request) {
        datasetService.updateRow(id, rowId, request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/data/rows/{rowId}")
    @RequirePermission("data:read")
    public ResponseEntity<RowDataResponse> getRow(@PathVariable Long id,
                                                    @PathVariable Long rowId) {
        RowDataResponse response = datasetService.getRow(id, rowId);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Clone/Copy Dataset
    // =========================================================================

    @PostMapping("/{id}/clone")
    @RequirePermission("dataset:write")
    public ResponseEntity<DatasetDetailResponse> cloneDataset(@PathVariable Long id,
                                                                @Valid @RequestBody CloneDatasetRequest request) {
        Long userId = currentUserId();
        DatasetDetailResponse response = datasetService.cloneDataset(id, request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
