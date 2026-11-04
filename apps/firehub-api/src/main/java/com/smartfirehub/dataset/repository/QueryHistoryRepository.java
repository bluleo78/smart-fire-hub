package com.smartfirehub.dataset.repository;

import com.smartfirehub.dataset.dto.QueryHistoryResponse;
import java.util.List;

/**
 * 쿼리 실행 이력(query_history) 저장·조회 리포지토리.
 *
 * <p>구현은 {@code JooqQueryHistoryRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface QueryHistoryRepository {

  /** 데이터셋 대상 쿼리 실행 한 건의 이력(SQL·종류·영향 행 수·실행 시간·성공 여부·오류)을 저장한다. */
  void save(
      Long datasetId,
      Long userId,
      String sql,
      String queryType,
      int affectedRows,
      long executionTimeMs,
      boolean success,
      String error);

  /** 데이터셋의 쿼리 이력을 최신순으로 페이지 단위 조회한다. */
  List<QueryHistoryResponse> findByDatasetId(Long datasetId, int page, int size);

  /** 데이터셋의 쿼리 이력 전체 건수를 센다. */
  long countByDatasetId(Long datasetId);
}
