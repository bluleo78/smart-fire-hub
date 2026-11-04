package com.smartfirehub.dataset.repository;

import com.smartfirehub.dataset.repository.jooq.JooqDatasetSearchRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** DatasetSearchRepositoryContractTest 를 Jooq(PostgreSQL) 구현으로 실행한다(WD-485). */
class JooqDatasetSearchRepositoryContractTest extends DatasetSearchRepositoryContractTest {

  @Autowired private JooqDatasetSearchRepository repository;

  @Override
  protected DatasetSearchRepository repository() {
    return repository;
  }
}
