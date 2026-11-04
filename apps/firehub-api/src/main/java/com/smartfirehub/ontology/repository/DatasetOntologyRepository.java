package com.smartfirehub.ontology.repository;

import java.util.Optional;

/**
 * 데이터셋↔온톨로지 바인딩(N:1) 저장/조회. OntologyRepository와 동일한 plain-SQL DSL 패턴(codegen 비의존).
 *
 * <p>구현은 {@code JooqDatasetOntologyRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface DatasetOntologyRepository {

  /** 데이터셋을 온톨로지에 바인딩(UPSERT) — dataset_id UNIQUE라 재바인딩 시 ontology_id/bound_by 갱신. */
  void bind(long datasetId, long ontologyId, Long userId);

  /** 데이터셋에 바인딩된 온톨로지 id 조회(없으면 empty). */
  Optional<Long> findOntologyIdByDataset(long datasetId);
}
