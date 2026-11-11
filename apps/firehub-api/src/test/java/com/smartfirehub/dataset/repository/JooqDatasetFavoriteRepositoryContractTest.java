package com.smartfirehub.dataset.repository;

import com.smartfirehub.dataset.repository.jooq.JooqDatasetFavoriteRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** DatasetFavoriteRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-707). */
class JooqDatasetFavoriteRepositoryContractTest extends DatasetFavoriteRepositoryContractTest {

  @Autowired private JooqDatasetFavoriteRepository repository;

  @Override
  protected DatasetFavoriteRepository repository() {
    return repository;
  }
}
