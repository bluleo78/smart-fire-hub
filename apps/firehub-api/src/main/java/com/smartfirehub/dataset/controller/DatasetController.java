package com.smartfirehub.dataset.controller;

import com.smartfirehub.dataset.dto.*;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.security.RequirePermission;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/datasets")
public class DatasetController {

    private final DatasetService datasetService;

    public DatasetController(DatasetService datasetService) {
        this.datasetService = datasetService;
    }

    @GetMapping
    @RequirePermission("dataset:read")
    public ResponseEntity<PageResponse<DatasetResponse>> getDatasets(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String datasetType,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        page = Math.max(0, page);
        size = Math.max(1, Math.min(size, 100));
        PageResponse<DatasetResponse> response = datasetService.getDatasets(categoryId, datasetType, search, page, size);
        return ResponseEntity.ok(response);
    }

    @PostMapping
    @RequirePermission("dataset:write")
    public ResponseEntity<DatasetDetailResponse> createDataset(@RequestBody CreateDatasetRequest request) {
        Long userId = Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
        DatasetDetailResponse dataset = datasetService.createDataset(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(dataset);
    }

    @GetMapping("/{id}")
    @RequirePermission("dataset:read")
    public ResponseEntity<DatasetDetailResponse> getDatasetById(@PathVariable Long id) {
        DatasetDetailResponse dataset = datasetService.getDatasetById(id);
        return ResponseEntity.ok(dataset);
    }

    @PutMapping("/{id}")
    @RequirePermission("dataset:write")
    public ResponseEntity<Void> updateDataset(@PathVariable Long id, @RequestBody UpdateDatasetRequest request) {
        datasetService.updateDataset(id, request);
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

    @GetMapping("/{id}/data")
    @RequirePermission("data:read")
    public ResponseEntity<DataQueryResponse> getDatasetData(
            @PathVariable Long id,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        page = Math.max(0, page);
        size = Math.max(1, Math.min(size, 200));
        String sanitizedSearch = search != null && search.length() > 200 ? search.substring(0, 200) : search;
        DataQueryResponse response = datasetService.getDatasetData(id, sanitizedSearch, page, size);
        return ResponseEntity.ok(response);
    }
}
