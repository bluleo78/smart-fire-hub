package com.smartfirehub.dataset.service;

import static com.smartfirehub.jooq.Tables.DATASET_CATEGORY;
import static com.smartfirehub.jooq.Tables.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.CategoryResponse;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.exception.CategoryInUseException;
import com.smartfirehub.dataset.exception.CategoryNotFoundException;
import com.smartfirehub.dataset.exception.DuplicateDatasetNameException;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** DatasetCategoryService 통합 테스트. CRUD 전체 흐름 및 참조 무결성, 이름 중복 검증 케이스를 커버한다. */
@Transactional
class DatasetCategoryServiceTest extends IntegrationTestBase {

  @Autowired private DatasetCategoryService categoryService;
  @Autowired private DatasetService datasetService;
  @Autowired private DSLContext dsl;

  private Long testUserId;

  /** 각 테스트 전 기존 카테고리 데이터를 초기화하여 격리된 환경을 보장한다. */
  @BeforeEach
  void setUp() {
    dsl.deleteFrom(DATASET_CATEGORY).execute();

    testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "cattestuser")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Cat Test User")
            .set(USER.EMAIL, "cattest@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
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
        // WD-240: 영어 「Category not found: N」 대신 한국어·번호 없는 문구
        .hasMessage("카테고리를 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.");
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
        // WD-241: 영어 「Category name already exists: …」 대신 웹 화면과 같은 한국어 문구
        .hasMessage(DuplicateDatasetNameException.CATEGORY_NAME_MESSAGE);
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
    assertThatThrownBy(() -> categoryService.updateCategory(catB.id(), "Category A", "새 설명"))
        .isInstanceOf(DuplicateDatasetNameException.class)
        .hasMessage(DuplicateDatasetNameException.CATEGORY_NAME_MESSAGE);
  }

  @Test
  void updateCategory_notFound_throwsCategoryNotFoundException() {
    // When/Then
    assertThatThrownBy(() -> categoryService.updateCategory(999999L, "이름", "설명"))
        .isInstanceOf(CategoryNotFoundException.class)
        // WD-240: 영어 「Category not found: N」 대신 한국어·번호 없는 문구
        .hasMessage("카테고리를 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.");
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
        // WD-240: 영어 「Category not found: N」 대신 한국어·번호 없는 문구
        .hasMessage("카테고리를 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.");
  }

  /**
   * 카테고리에 데이터셋이 연결된 상태로 삭제를 시도하면 CategoryInUseException(한국어 메시지)을 던지고, 카테고리가 실제로 삭제되지 않아야 한다 (#518
   * — 영어 원문 노출 회귀 방지).
   */
  @Test
  void deleteCategory_withExistingDataset_throwsCategoryInUseExceptionWithKoreanMessage() {
    // Given: 카테고리를 참조하는 데이터셋 생성
    CategoryResponse category = categoryService.createCategory("In Use Category", "설명");
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("name", "Name", "TEXT", null, false, true, "Name column"));
    CreateDatasetRequest request =
        new CreateDatasetRequest(
            "Category Ref Dataset",
            "category_ref_dataset",
            "설명",
            category.id(),
            "TABLE",
            "SOURCE",
            columns,
            null);
    datasetService.createDataset(request, testUserId);

    // When/Then: 삭제 시 한국어 메시지의 CategoryInUseException 발생
    assertThatThrownBy(() -> categoryService.deleteCategory(category.id()))
        .isInstanceOf(CategoryInUseException.class)
        .hasMessage(DatasetCategoryService.CATEGORY_IN_USE_MESSAGE);

    // Then: 카테고리는 삭제되지 않고 그대로 남아있어야 한다
    CategoryResponse stillExists = categoryService.getCategoryById(category.id());
    assertThat(stillExists.id()).isEqualTo(category.id());
  }

  // =========================================================================
  // 이름 정규화·중복 판정 (WD-241)
  // =========================================================================

  /** 앞뒤 공백(전각 공백 포함)이 든 이름은 걷어 낸 값으로 저장된다. */
  @Test
  void createCategory_paddedName_storedTrimmed() {
    CategoryResponse created = categoryService.createCategory("  행정 \u3000", "설명");

    assertThat(created.name()).isEqualTo("행정");
    assertThat(
            dsl.select(DATASET_CATEGORY.NAME).from(DATASET_CATEGORY).fetch(DATASET_CATEGORY.NAME))
        .containsExactly("행정");
  }

  /** 대소문자·앞뒤 공백만 다른 이름은 같은 이름으로 보고 생성을 거부한다. */
  @Test
  void createCategory_caseOrSpaceVariant_rejectedAsDuplicate() {
    categoryService.createCategory("Admin", "첫 번째");

    assertThatThrownBy(() -> categoryService.createCategory("admin", "대소문자만 다름"))
        .isInstanceOf(DuplicateDatasetNameException.class)
        .hasMessage("이미 사용 중인 카테고리 이름입니다.");
    assertThatThrownBy(() -> categoryService.createCategory("  ADMIN  ", "공백·대소문자"))
        .isInstanceOf(DuplicateDatasetNameException.class)
        .hasMessage("이미 사용 중인 카테고리 이름입니다.");
    assertThat(dsl.fetchCount(DATASET_CATEGORY)).isEqualTo(1);
  }

  /** 수정 전부터 DB 에 공백 든 이름으로 남아 있던 카테고리와도 겹침을 잡는다(저장된 값도 trim 비교). */
  @Test
  void createCategory_matchesLegacyPaddedName_rejected() {
    insertRawCategory(" 행정 ");

    assertThatThrownBy(() -> categoryService.createCategory("행정", "설명"))
        .isInstanceOf(DuplicateDatasetNameException.class)
        .hasMessage(DuplicateDatasetNameException.CATEGORY_NAME_MESSAGE);
  }

  /** 다른 카테고리 이름과 대소문자·공백만 다르게 수정하면 거부한다. */
  @Test
  void updateCategory_caseOrSpaceVariantOfOther_rejected() {
    categoryService.createCategory("Category A", "A");
    CategoryResponse catB = categoryService.createCategory("Category B", "B");

    assertThatThrownBy(() -> categoryService.updateCategory(catB.id(), " category a ", "새 설명"))
        .isInstanceOf(DuplicateDatasetNameException.class)
        .hasMessage(DuplicateDatasetNameException.CATEGORY_NAME_MESSAGE);
    assertThat(categoryService.getCategoryById(catB.id()).name()).isEqualTo("Category B");
  }

  /**
   * 공백 든 옛 이름의 카테고리를 웹 편집창처럼 trim 한 이름으로 설명만 고쳐 보내도 자기 자신이라 통과하고, 이름은 trim 된 값으로 바뀐다(예전엔 자기 자신과
   * 겹친다며 409).
   */
  @Test
  void updateCategory_selfWithSpaceDifference_allowedAndTrimmed() {
    Long id = insertRawCategory(" 행정 ");

    categoryService.updateCategory(id, "행정", "설명만 수정");

    CategoryResponse updated = categoryService.getCategoryById(id);
    assertThat(updated.name()).isEqualTo("행정");
    assertThat(updated.description()).isEqualTo("설명만 수정");
  }

  /** 자기 이름의 대소문자만 바꾸는 수정은 허용한다. */
  @Test
  void updateCategory_selfCaseChange_allowed() {
    CategoryResponse created = categoryService.createCategory("admin", "설명");

    categoryService.updateCategory(created.id(), "Admin", "설명");

    assertThat(categoryService.getCategoryById(created.id()).name()).isEqualTo("Admin");
  }

  /** 줄바꿈·탭 등 제어 문자가 든 이름은 생성·수정 모두 400(IllegalArgumentException) 한국어 문구로 거부한다. */
  @Test
  void createAndUpdateCategory_controlCharacters_rejected() {
    String message = "카테고리 이름에 줄바꿈·탭 같은 제어 문자를 쓸 수 없습니다.";
    assertThatThrownBy(() -> categoryService.createCategory("행\n정", "설명"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(message);
    assertThatThrownBy(() -> categoryService.createCategory("행정\t", "설명"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(message);
    assertThat(dsl.fetchCount(DATASET_CATEGORY)).isZero();

    CategoryResponse created = categoryService.createCategory("행정", "설명");
    assertThatThrownBy(() -> categoryService.updateCategory(created.id(), "행정\r\n", "설명"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(message);
    assertThat(categoryService.getCategoryById(created.id()).name()).isEqualTo("행정");
  }

  /** 공백(전각 공백 포함)만 있는 이름은 요청 검증과 같은 「필수」 문구로 거부한다 — @NotBlank 는 전각 공백을 공백으로 보지 않는다. */
  @Test
  void createCategory_onlyWideSpaces_rejectedAsBlank() {
    assertThatThrownBy(() -> categoryService.createCategory("\u3000 \u3000", "설명"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("카테고리 이름은 필수입니다.");
  }

  /** 서비스 검사를 거치지 않은 옛 데이터를 흉내 내려고 이름을 그대로 넣는다(tenant_id 는 GUC 기본값). */
  private Long insertRawCategory(String rawName) {
    return dsl.insertInto(DATASET_CATEGORY)
        .set(DATASET_CATEGORY.NAME, rawName)
        .returning(DATASET_CATEGORY.ID)
        .fetchOne()
        .getId();
  }
}
