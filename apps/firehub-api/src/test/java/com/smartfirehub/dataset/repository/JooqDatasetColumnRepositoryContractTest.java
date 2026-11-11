package com.smartfirehub.dataset.repository;

import com.smartfirehub.dataset.repository.jooq.JooqDatasetColumnRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** DatasetColumnRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-707). */
class JooqDatasetColumnRepositoryContractTest extends DatasetColumnRepositoryContractTest {

  @Autowired private JooqDatasetColumnRepository repository;

  @Override
  protected DatasetColumnRepository repository() {
    return repository;
  }
}
