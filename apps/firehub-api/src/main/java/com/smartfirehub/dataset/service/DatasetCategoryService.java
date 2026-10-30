package com.smartfirehub.dataset.service;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.dataset.dto.CategoryResponse;
import com.smartfirehub.dataset.exception.CategoryInUseException;
import com.smartfirehub.dataset.exception.CategoryNotFoundException;
import com.smartfirehub.dataset.exception.DuplicateDatasetNameException;
import com.smartfirehub.dataset.repository.DatasetCategoryRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DatasetCategoryService {

  private final DatasetCategoryRepository categoryRepository;
  private final DSLContext dsl;

  /** 카테고리 생성·수정·삭제 감사(WD-464). */
  private final DatasetCategoryAuditRecorder auditRecorder;

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

  /** 이름에 제어 문자(줄바꿈·탭 등)가 들었을 때의 400 문구(WD-241). */
  static final String CONTROL_CHAR_NAME_MESSAGE = "카테고리 이름에 줄바꿈·탭 같은 제어 문자를 쓸 수 없습니다.";

  /** 앞뒤 공백을 걷어 내니 빈 이름일 때의 400 문구 — CategoryRequest 의 @NotBlank 문구와 같다. */
  static final String BLANK_NAME_MESSAGE = "카테고리 이름은 필수입니다.";

  /** 이름 길이 초과 400 문구 — CategoryRequest 의 @Size 문구와 같다. */
  static final String NAME_TOO_LONG_MESSAGE = "카테고리 이름은 50자 이하여야 합니다.";

  /** 카테고리 이름 최대 길이(dataset_category.name VARCHAR(50)) — CategoryRequest 의 @Size 와 같다. */
  private static final int NAME_MAX_LENGTH = 50;

  /**
   * 카테고리를 만든다.
   *
   * @param userId 행위자 — 감사 기록용(WD-464)
   */
  @Transactional
  public CategoryResponse createCategory(String name, String description, Long userId) {
    String normalized = normalizeName(name);
    // 중복은 앞뒤 공백·대소문자를 무시하고 본다(WD-241) — 「 행정 」·「Admin」/「admin」이 별개로 쌓이지 않게.
    if (categoryRepository.existsByName(normalized)) {
      throw DuplicateDatasetNameException.categoryName();
    }
    CategoryResponse created =
        translateNameRace(() -> categoryRepository.save(normalized, description));
    // 감사(WD-464) — 저장이 끝난 뒤 같은 트랜잭션에서 남긴다(이름 충돌 409 면 남지 않는다).
    auditRecorder.created(userId, created.id(), created.name());
    return created;
  }

  /**
   * 카테고리 이름·설명을 고친다.
   *
   * @param userId 행위자 — 감사 기록용(WD-464)
   */
  @Transactional
  public void updateCategory(Long id, String name, String description, Long userId) {
    CategoryResponse before =
        categoryRepository.findById(id).orElseThrow(() -> new CategoryNotFoundException());
    String normalized = normalizeName(name);

    // 자기 자신을 제외한 다른 카테고리에 같은 이름(앞뒤 공백·대소문자 무시)이 있는지 확인한다. 자기 자신은 빼므로, 공백 든 옛 이름을
    // 가진 카테고리를 웹 편집창(이름을 trim 해 보냄)에서 설명만 고쳐 저장해도 통과하고, 이름은 trim 된 값으로 바뀐다(WD-241).
    if (categoryRepository.existsByNameExcludingId(normalized, id)) {
      throw DuplicateDatasetNameException.categoryName();
    }

    translateNameRace(
        () -> {
          categoryRepository.update(id, normalized, description);
          return null;
        });

    // 감사(WD-464) — 저장 전후 DB 값을 비교해 바뀐 항목 이름만 남긴다. 같은 값을 다시 저장하면 남기지 않는다(앞뒤 공백만 다른 옛 이름을 정리한 저장은
    // 이름이 실제로 바뀌었으므로 남긴다).
    CategoryResponse after =
        categoryRepository.findById(id).orElseThrow(() -> new CategoryNotFoundException());
    List<String> changed = new ArrayList<>();
    if (!Objects.equals(before.name(), after.name())) changed.add("name");
    if (!Objects.equals(before.description(), after.description())) changed.add("description");
    auditRecorder.updated(userId, id, after.name(), changed);
  }

  /**
   * 카테고리 이름을 저장할 모양으로 정규화한다(WD-241). API·AI 도구가 보낸 이름도 웹과 같은 규칙을 거치게 서비스에서 한다.
   *
   * <ul>
   *   <li>제어 문자(줄바꿈·탭·NUL 등)가 어디든 들면 400 — 앞뒤에 붙은 것도 조용히 잘라 내지 않고 거부한다(목록·선택 상자에서 보이지 않는 차이가 생기지
   *       않게).
   *   <li>앞뒤 공백(전각 공백 포함)을 걷어 낸다.
   *   <li>걷어 낸 뒤 비었거나 50자를 넘으면 요청 검증(@NotBlank·@Size)과 같은 문구로 400.
   * </ul>
   */
  static String normalizeName(String name) {
    if (name == null) {
      throw new IllegalArgumentException(BLANK_NAME_MESSAGE);
    }
    if (name.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(CONTROL_CHAR_NAME_MESSAGE);
    }
    String stripped = name.strip();
    if (stripped.isEmpty()) {
      throw new IllegalArgumentException(BLANK_NAME_MESSAGE);
    }
    if (stripped.length() > NAME_MAX_LENGTH) {
      throw new IllegalArgumentException(NAME_TOO_LONG_MESSAGE);
    }
    return stripped;
  }

  /**
   * 사전 중복 검사와 쓰기 사이에 같은 이름이 동시에 저장된 경우를 사전 검사와 같은 409 한국어 문구로 바꾼다(WD-241).
   *
   * <p>dataset_category 의 유일 제약은 PK(id, 시퀀스)와 (tenant_id, name) 인덱스(V87)뿐이라, 이 테이블 쓰기의 중복 키 위반은 이름
   * 충돌이다. DB 인덱스는 정확히 같은 문자열만 막으므로 대소문자·공백만 다른 동시 생성은 여기서 못 막는다(알려진 한계 — 유일 인덱스 변경은 기존 중복 데이터 때문에
   * 하지 않았다). 예외로 트랜잭션은 롤백된다.
   */
  private static <T> T translateNameRace(Supplier<T> write) {
    try {
      return write.get();
    } catch (DuplicateKeyException e) {
      throw DuplicateDatasetNameException.categoryName();
    }
  }

  /** 카테고리 삭제 거부 문구 — 보이는 데이터셋이 있든 숨긴 데이터셋만 있든 같다(WD-155). */
  static final String CATEGORY_IN_USE_MESSAGE =
      "이 카테고리를 사용 중인 데이터셋이 있어 삭제할 수 없습니다. 목록에 보이지 않는 데이터셋이 연결되어 있을 수 있으니 관리자에게 문의하세요.";

  /**
   * 카테고리를 지운다 — 데이터셋이 연결돼 있으면 409.
   *
   * @param userId 행위자 — 감사 기록용(WD-464)
   */
  @Transactional
  public void deleteCategory(Long id, Long userId) {
    CategoryResponse target =
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
    // 감사(WD-464) — 이름은 지우기 전에 읽은 값을 쓴다.
    auditRecorder.deleted(userId, id, target.name());
  }
}
