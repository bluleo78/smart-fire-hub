package com.smartfirehub.dataset.repository;

import com.smartfirehub.dataset.repository.jooq.JooqDatasetCategoryRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** DatasetCategoryRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-707). */
class JooqDatasetCategoryRepositoryContractTest extends DatasetCategoryRepositoryContractTest {

  @Autowired private JooqDatasetCategoryRepository repository;

  @Override
  protected DatasetCategoryRepository repository() {
    return repository;
  }
}
