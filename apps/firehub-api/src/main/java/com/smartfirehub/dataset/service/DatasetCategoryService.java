package com.smartfirehub.dataset.service;

import com.smartfirehub.dataset.dto.CategoryResponse;
import com.smartfirehub.dataset.exception.CategoryNotFoundException;
import com.smartfirehub.dataset.exception.DuplicateDatasetNameException;
import com.smartfirehub.dataset.repository.DatasetCategoryRepository;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.jooq.impl.DSL.*;

@Service
public class DatasetCategoryService {

    private final DatasetCategoryRepository categoryRepository;
    private final DSLContext dsl;

    public DatasetCategoryService(DatasetCategoryRepository categoryRepository, DSLContext dsl) {
        this.categoryRepository = categoryRepository;
        this.dsl = dsl;
    }

    public List<CategoryResponse> getAllCategories() {
        return categoryRepository.findAll();
    }

    public CategoryResponse getCategoryById(Long id) {
        return categoryRepository.findById(id)
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
        categoryRepository.findById(id)
                .orElseThrow(() -> new CategoryNotFoundException("Category not found: " + id));
        categoryRepository.update(id, name, description);
    }

    @Transactional
    public void deleteCategory(Long id) {
        categoryRepository.findById(id)
                .orElseThrow(() -> new CategoryNotFoundException("Category not found: " + id));

        // Check if any datasets reference this category
        var DATASET = table(name("dataset"));
        var DS_CATEGORY_ID = field(name("dataset", "category_id"), Long.class);

        boolean hasDatasets = dsl.fetchExists(
                dsl.selectOne().from(DATASET).where(DS_CATEGORY_ID.eq(id))
        );

        if (hasDatasets) {
            throw new IllegalArgumentException("Cannot delete category with existing datasets");
        }

        categoryRepository.deleteById(id);
    }
}
