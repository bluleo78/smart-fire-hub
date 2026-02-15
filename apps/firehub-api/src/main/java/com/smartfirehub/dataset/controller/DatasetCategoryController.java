package com.smartfirehub.dataset.controller;

import com.smartfirehub.dataset.dto.CategoryRequest;
import com.smartfirehub.dataset.dto.CategoryResponse;
import com.smartfirehub.dataset.service.DatasetCategoryService;
import com.smartfirehub.global.security.RequirePermission;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/dataset-categories")
public class DatasetCategoryController {

    private final DatasetCategoryService categoryService;

    public DatasetCategoryController(DatasetCategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    @RequirePermission("dataset:read")
    public ResponseEntity<List<CategoryResponse>> getAllCategories() {
        List<CategoryResponse> categories = categoryService.getAllCategories();
        return ResponseEntity.ok(categories);
    }

    @PostMapping
    @RequirePermission("dataset:write")
    public ResponseEntity<CategoryResponse> createCategory(@RequestBody CategoryRequest request) {
        CategoryResponse category = categoryService.createCategory(request.name(), request.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(category);
    }

    @PutMapping("/{id}")
    @RequirePermission("dataset:write")
    public ResponseEntity<Void> updateCategory(@PathVariable Long id, @RequestBody CategoryRequest request) {
        categoryService.updateCategory(id, request.name(), request.description());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    @RequirePermission("dataset:delete")
    public ResponseEntity<Void> deleteCategory(@PathVariable Long id) {
        categoryService.deleteCategory(id);
        return ResponseEntity.noContent().build();
    }
}
