package com.smartfirehub.dataset.repository;

import com.smartfirehub.dataset.dto.CategoryResponse;
import java.util.List;
import java.util.Optional;

/**
 * 데이터셋 카테고리(dataset_category) 저장·조회 리포지토리.
 *
 * <p>구현은 {@code JooqDatasetCategoryRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface DatasetCategoryRepository {

  /** 카테고리 전체 목록을 조회한다. */
  List<CategoryResponse> findAll();

  /** id 로 카테고리 한 건을 조회한다. 없으면 빈 값. */
  Optional<CategoryResponse> findById(Long id);

  /** 카테고리를 새로 저장하고 저장된 행을 돌려준다. */
  CategoryResponse save(String name, String description);

  /** 카테고리의 이름·설명을 갱신한다. */
  void update(Long id, String name, String description);

  /** id 로 카테고리를 삭제한다. */
  void deleteById(Long id);

  /**
   * 주어진 이름과 앞뒤 공백·대소문자를 무시하고 같은 카테고리가 있는지 확인한다(WD-241).
   *
   * <p>예전에는 정확히 같은 문자열만 비교해 「 행정 」·「HAENG」/「haeng」이 별개 카테고리로 저장됐다. 저장된 값도 trim·lower 해 비교하므로, 수정
   * 전부터 남아 있던 공백 든 이름과도 겹침을 잡는다. 호출 쪽이 이미 trim 한 이름을 넘긴다.
   *
   * @param name 확인할 카테고리 이름(trim 된 값)
   * @return 같은 이름으로 볼 카테고리가 있으면 true
   */
  boolean existsByName(String name);

  /**
   * 주어진 이름이 다른 카테고리에 이미 존재하는지 확인한다. 수정 시 자기 자신을 제외하고 중복 여부를 판별하기 위해 사용한다. 비교 규칙은 {@link
   * #existsByName(String)} 과 같다(앞뒤 공백·대소문자 무시) — 자기 이름의 공백만 다르게 보내도 자기 자신이라 통과한다.
   *
   * @param name 확인할 카테고리 이름(trim 된 값)
   * @param excludeId 제외할 카테고리 ID (자기 자신)
   * @return 다른 카테고리에 동일 이름이 존재하면 true
   */
  boolean existsByNameExcludingId(String name, Long excludeId);

  /**
   * 이 카테고리를 참조하는 데이터셋이 하나라도 있는지 — 삭제 가능 여부 판정용.
   *
   * <p>보안 등급으로 가려진 데이터셋까지 센다(가시성 조건을 걸지 않는다) — 호출자가 보는 행만이 아니라 연결 자체를 막아야 FK 를 지킨다. RLS 는 걸리므로 같은
   * 테넌트 데이터셋만 보며, GUC 가 주입된 호출자(DatasetCategoryService.deleteCategory) 트랜잭션 안에서만 맞는 결과를 낸다 — 트랜잭션
   * 없이 부르면 0행이 되어 참조가 없다고 답한다.
   */
  boolean existsDatasetUsing(Long categoryId);
}
