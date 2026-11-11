package com.smartfirehub.dataset.repository;

import com.smartfirehub.dataset.repository.jooq.JooqDatasetRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** DatasetRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-707). */
class JooqDatasetRepositoryContractTest extends DatasetRepositoryContractTest {

  @Autowired private JooqDatasetRepository repository;

  @Override
  protected DatasetRepository repository() {
    return repository;
  }
}
