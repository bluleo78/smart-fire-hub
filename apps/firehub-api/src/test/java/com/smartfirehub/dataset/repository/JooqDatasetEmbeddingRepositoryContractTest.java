package com.smartfirehub.dataset.repository;

import com.smartfirehub.dataset.repository.jooq.JooqDatasetEmbeddingRepository;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * DatasetEmbeddingRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-707) — 벡터 메서드 시나리오까지 돈다.
 */
class JooqDatasetEmbeddingRepositoryContractTest extends DatasetEmbeddingRepositoryContractTest {

  @Autowired private JooqDatasetEmbeddingRepository repository;

  @Override
  protected DatasetEmbeddingRepository repository() {
    return repository;
  }
}
