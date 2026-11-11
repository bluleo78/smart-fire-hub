package com.smartfirehub.dataset.repository;

import com.smartfirehub.dataset.repository.jooq.JooqDatasetReferenceRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** DatasetReferenceRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-707). */
class JooqDatasetReferenceRepositoryContractTest extends DatasetReferenceRepositoryContractTest {

  @Autowired private JooqDatasetReferenceRepository repository;

  @Override
  protected DatasetReferenceRepository repository() {
    return repository;
  }
}
