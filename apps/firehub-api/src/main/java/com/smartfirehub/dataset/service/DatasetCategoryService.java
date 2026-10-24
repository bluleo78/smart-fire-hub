package com.smartfirehub.dataset.service;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.dataset.dto.CategoryResponse;
import com.smartfirehub.dataset.exception.CategoryInUseException;
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

  // RLS 가 걸린 dataset_category 를 읽는다 — 트랜잭션이 없으면 GUC 미설정으로 조용히 0행이 된다.
  @Transactional(readOnly = true)
  public List<CategoryResponse> getAllCategories() {
    return categoryRepository.findAll();
  }

  // RLS 가 걸린 dataset_category 를 읽는다 — 트랜잭션이 없으면 GUC 미설정으로 조용히 0행이 된다.
  @Transactional(readOnly = true)
  public CategoryResponse getCategoryById(Long id) {
    return categoryRepository.findById(id).orElseThrow(() -> new CategoryNotFoundException());
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
    categoryRepository.findById(id).orElseThrow(() -> new CategoryNotFoundException());

    // 자기 자신을 제외한 다른 카테고리에 동일 이름이 존재하는지 확인
    if (categoryRepository.existsByNameExcludingId(name, id)) {
      throw new DuplicateDatasetNameException("Category name already exists: " + name);
    }

    categoryRepository.update(id, name, description);
  }

  /** 카테고리 삭제 거부 문구 — 보이는 데이터셋이 있든 숨긴 데이터셋만 있든 같다(WD-155). */
  static final String CATEGORY_IN_USE_MESSAGE =
      "이 카테고리를 사용 중인 데이터셋이 있어 삭제할 수 없습니다. 목록에 보이지 않는 데이터셋이 연결되어 있을 수 있으니 관리자에게 문의하세요.";

  @Transactional
  public void deleteCategory(Long id) {
    categoryRepository.findById(id).orElseThrow(() -> new CategoryNotFoundException());

    // Check if any datasets reference this category
    var DATASET = table(name("dataset"));
    var DS_CATEGORY_ID = field(name("dataset", "category_id"), Long.class);

    boolean hasDatasets =
        dsl.fetchExists(dsl.selectOne().from(DATASET).where(DS_CATEGORY_ID.eq(id)));

    if (hasDatasets) {
      // 다른 "사용 중이라 삭제 불가" 케이스(DatasetInUseException)와 동일하게 한국어 메시지 +
      // 409 Conflict로 응답한다 (#518 — 영어 원문 노출 및 상태 코드 불일치 수정)
      // 연결 여부는 볼 수 없는 데이터셋까지 센다 — 숨긴 데이터셋만 든 카테고리는 목록에서 비어 보이는데도 막힌다(WD-155, 알려진
      // 한계). 그 경우에도 같은 문구로 답하고, 사용자가 「비어 보이는데 왜 막히나」를 이해하도록 안내를 붙인다.
      throw new CategoryInUseException(CATEGORY_IN_USE_MESSAGE);
    }

    categoryRepository.deleteById(id);
  }
}
