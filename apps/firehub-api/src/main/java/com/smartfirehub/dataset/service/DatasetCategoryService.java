package com.smartfirehub.dataset.service;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.dataset.dto.CategoryResponse;
import com.smartfirehub.dataset.exception.CategoryNotFoundException;
import com.smartfirehub.dataset.exception.DuplicateDatasetNameException;
import com.smartfirehub.dataset.repository.DatasetCategoryRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DatasetCategoryService {

  private final DatasetCategoryRepository categoryRepository;
  private final DSLContext dsl;

  public List<CategoryResponse> getAllCategories() {
    return categoryRepository.findAll();
  }

  public CategoryResponse getCategoryById(Long id) {
    return categoryRepository
        .findById(id)
        .orElseThrow(() -> new CategoryNotFoundException("Category not found: " + id));
  }

  @Transactional
  public CategoryResponse createCategory(String name, String description) {
    if (categoryRepository.existsByName(name)) {
      throw new DuplicateDatasetNameException("Category name already exists: " + name);
    }
    return categoryRepository.save(name, description);
  }

  @Transactional
  public void updateCategory(Long id, String name, String description) {
    categoryRepository
        .findById(id)
        .orElseThrow(() -> new CategoryNotFoundException("Category not found: " + id));

    // 자기 자신을 제외한 다른 카테고리에 동일 이름이 존재하는지 확인
    if (categoryRepository.existsByNameExcludingId(name, id)) {
      throw new DuplicateDatasetNameException("Category name already exists: " + name);
    }

    categoryRepository.update(id, name, description);
  }

  @Transactional
  public void deleteCategory(Long id) {
    categoryRepository
        .findById(id)
        .orElseThrow(() -> new CategoryNotFoundException("Category not found: " + id));

    // Check if any datasets reference this category
    var DATASET = table(name("dataset"));
    var DS_CATEGORY_ID = field(name("dataset", "category_id"), Long.class);

    boolean hasDatasets =
        dsl.fetchExists(dsl.selectOne().from(DATASET).where(DS_CATEGORY_ID.eq(id)));

    if (hasDatasets) {
      throw new IllegalArgumentException("Cannot delete category with existing datasets");
    }

    categoryRepository.deleteById(id);
  }
}
