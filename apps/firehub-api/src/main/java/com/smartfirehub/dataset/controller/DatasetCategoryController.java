package com.smartfirehub.dataset.controller;

import com.smartfirehub.dataset.dto.CategoryRequest;
import com.smartfirehub.dataset.dto.CategoryResponse;
import com.smartfirehub.dataset.service.DatasetCategoryService;
import com.smartfirehub.global.security.RequirePermission;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/dataset-categories")
@RequiredArgsConstructor
public class DatasetCategoryController {

  private final DatasetCategoryService categoryService;

  @GetMapping
  @RequirePermission("dataset:read")
  public ResponseEntity<List<CategoryResponse>> getAllCategories() {
    List<CategoryResponse> categories = categoryService.getAllCategories();
    return ResponseEntity.ok(categories);
  }

  @PostMapping
  @RequirePermission("dataset:write")
  public ResponseEntity<CategoryResponse> createCategory(
      @Valid @RequestBody CategoryRequest request, Authentication authentication) {
    // 행위자는 감사 기록용(WD-464)
    Long userId = (Long) authentication.getPrincipal();
    CategoryResponse category =
        categoryService.createCategory(request.name(), request.description(), userId);
    return ResponseEntity.status(HttpStatus.CREATED).body(category);
  }

  @PutMapping("/{id}")
  @RequirePermission("dataset:write")
  public ResponseEntity<Void> updateCategory(
      @PathVariable Long id,
      @Valid @RequestBody CategoryRequest request,
      Authentication authentication) {
    categoryService.updateCategory(
        id, request.name(), request.description(), (Long) authentication.getPrincipal());
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{id}")
  @RequirePermission("dataset:delete")
  public ResponseEntity<Void> deleteCategory(@PathVariable Long id, Authentication authentication) {
    categoryService.deleteCategory(id, (Long) authentication.getPrincipal());
    return ResponseEntity.noContent().build();
  }
}
