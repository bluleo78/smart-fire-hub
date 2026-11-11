package com.smartfirehub.dataset.repository;

import com.smartfirehub.dataset.repository.jooq.JooqDatasetTagRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** DatasetTagRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-707). */
class JooqDatasetTagRepositoryContractTest extends DatasetTagRepositoryContractTest {

  @Autowired private JooqDatasetTagRepository repository;

  @Override
  protected DatasetTagRepository repository() {
    return repository;
  }
}
