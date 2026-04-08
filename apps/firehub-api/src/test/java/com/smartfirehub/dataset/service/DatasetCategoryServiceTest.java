package com.smartfirehub.dataset.service;

import static com.smartfirehub.jooq.Tables.DATASET_CATEGORY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.CategoryResponse;
import com.smartfirehub.dataset.exception.CategoryNotFoundException;
import com.smartfirehub.dataset.exception.DuplicateDatasetNameException;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * DatasetCategoryService 통합 테스트.
 * CRUD 전체 흐름 및 참조 무결성, 이름 중복 검증 케이스를 커버한다.
 */
@Transactional
class DatasetCategoryServiceTest extends IntegrationTestBase {

  @Autowired private DatasetCategoryService categoryService;
  @Autowired private DSLContext dsl;

  /** 각 테스트 전 기존 카테고리 데이터를 초기화하여 격리된 환경을 보장한다. */
  @BeforeEach
  void setUp() {
    dsl.deleteFrom(DATASET_CATEGORY).execute();
  }

  // =========================================================================
  // getAllCategories
  // =========================================================================

  @Test
  void getAllCategories_empty_returnsEmptyList() {
    // When
    List<CategoryResponse> result = categoryService.getAllCategories();

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void getAllCategories_withData_returnsAllSortedById() {
    // Given: ID 순 정렬 확인을 위해 두 개 생성
    categoryService.createCategory("Beta Category", "두 번째 카테고리");
    categoryService.createCategory("Alpha Category", "첫 번째 카테고리");

    // When
    List<CategoryResponse> result = categoryService.getAllCategories();

    // Then: ID 오름차순 정렬 확인
    assertThat(result).hasSize(2);
    assertThat(result.get(0).id()).isLessThan(result.get(1).id());
  }

  // =========================================================================
  // getCategoryById
  // =========================================================================

  @Test
  void getCategoryById_existing_returnsCategory() {
    // Given
    CategoryResponse created = categoryService.createCategory("Test Cat", "설명");

    // When
    CategoryResponse found = categoryService.getCategoryById(created.id());

    // Then
    assertThat(found.id()).isEqualTo(created.id());
    assertThat(found.name()).isEqualTo("Test Cat");
    assertThat(found.description()).isEqualTo("설명");
  }

  @Test
  void getCategoryById_notFound_throwsCategoryNotFoundException() {
    // When/Then
    assertThatThrownBy(() -> categoryService.getCategoryById(999999L))
        .isInstanceOf(CategoryNotFoundException.class)
        .hasMessageContaining("999999");
  }

  // =========================================================================
  // createCategory
  // =========================================================================

  @Test
  void createCategory_success_persisted() {
    // When
    CategoryResponse created = categoryService.createCategory("New Category", "카테고리 설명");

    // Then: 반환값 검증
    assertThat(created.id()).isNotNull();
    assertThat(created.name()).isEqualTo("New Category");
    assertThat(created.description()).isEqualTo("카테고리 설명");

    // Then: DB에 실제로 저장되었는지 검증
    Long count =
        dsl.selectCount()
            .from(DATASET_CATEGORY)
            .where(DATASET_CATEGORY.NAME.eq("New Category"))
            .fetchOne(0, Long.class);
    assertThat(count).isEqualTo(1);
  }

  @Test
  void createCategory_duplicateName_throwsDuplicateDatasetNameException() {
    // Given
    categoryService.createCategory("Duplicate Name", "첫 번째 생성");

    // When/Then: 동일 이름으로 다시 생성 시 예외 발생
    assertThatThrownBy(() -> categoryService.createCategory("Duplicate Name", "두 번째 생성"))
        .isInstanceOf(DuplicateDatasetNameException.class)
        .hasMessageContaining("Duplicate Name");
  }

  // =========================================================================
  // updateCategory
  // =========================================================================

  @Test
  void updateCategory_success_nameAndDescriptionChanged() {
    // Given
    CategoryResponse created = categoryService.createCategory("Original", "원래 설명");

    // When
    categoryService.updateCategory(created.id(), "Updated", "수정된 설명");

    // Then
    CategoryResponse updated = categoryService.getCategoryById(created.id());
    assertThat(updated.name()).isEqualTo("Updated");
    assertThat(updated.description()).isEqualTo("수정된 설명");
  }

  @Test
  void updateCategory_sameNameSelf_doesNotThrow() {
    // Given: 자기 자신의 이름으로 업데이트하는 경우 예외가 발생하지 않아야 한다
    CategoryResponse created = categoryService.createCategory("Same Name", "설명");

    // When/Then: 자신의 이름 그대로 업데이트 → 정상 처리
    categoryService.updateCategory(created.id(), "Same Name", "수정된 설명만 변경");

    CategoryResponse updated = categoryService.getCategoryById(created.id());
    assertThat(updated.description()).isEqualTo("수정된 설명만 변경");
  }

  @Test
  void updateCategory_duplicateNameOtherCategory_throwsDuplicateDatasetNameException() {
    // Given: 서로 다른 두 카테고리 생성
    categoryService.createCategory("Category A", "A 설명");
    CategoryResponse catB = categoryService.createCategory("Category B", "B 설명");

    // When/Then: B를 A의 이름으로 수정하면 중복 예외 발생
    assertThatThrownBy(
            () -> categoryService.updateCategory(catB.id(), "Category A", "새 설명"))
        .isInstanceOf(DuplicateDatasetNameException.class)
        .hasMessageContaining("Category A");
  }

  @Test
  void updateCategory_notFound_throwsCategoryNotFoundException() {
    // When/Then
    assertThatThrownBy(() -> categoryService.updateCategory(999999L, "이름", "설명"))
        .isInstanceOf(CategoryNotFoundException.class)
        .hasMessageContaining("999999");
  }

  // =========================================================================
  // deleteCategory
  // =========================================================================

  @Test
  void deleteCategory_existing_removedFromDb() {
    // Given
    CategoryResponse created = categoryService.createCategory("To Delete", "삭제 대상");

    // When
    categoryService.deleteCategory(created.id());

    // Then: DB에서 삭제 확인
    Long count =
        dsl.selectCount()
            .from(DATASET_CATEGORY)
            .where(DATASET_CATEGORY.ID.eq(created.id()))
            .fetchOne(0, Long.class);
    assertThat(count).isEqualTo(0);
  }

  @Test
  void deleteCategory_notFound_throwsCategoryNotFoundException() {
    // When/Then
    assertThatThrownBy(() -> categoryService.deleteCategory(999999L))
        .isInstanceOf(CategoryNotFoundException.class)
        .hasMessageContaining("999999");
  }
}
