package com.smartfirehub.dataset.repository;

import java.util.Set;

/**
 * 사용자별 데이터셋 즐겨찾기(dataset_favorite) 저장·조회 리포지토리.
 *
 * <p>구현은 {@code JooqDatasetFavoriteRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface DatasetFavoriteRepository {

  /** 사용자가 데이터셋을 즐겨찾기했는지 확인한다. */
  boolean existsByUserIdAndDatasetId(Long userId, Long datasetId);

  /** 즐겨찾기를 추가한다. 이미 있으면 아무 일도 하지 않는다. */
  void insert(Long userId, Long datasetId);

  /** 즐겨찾기를 해제한다. */
  void delete(Long userId, Long datasetId);

  /** 사용자가 즐겨찾기한 데이터셋 id 집합을 조회한다. */
  Set<Long> findDatasetIdsByUserId(Long userId);
}
