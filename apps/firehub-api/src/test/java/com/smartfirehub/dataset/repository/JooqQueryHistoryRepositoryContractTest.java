package com.smartfirehub.dataset.repository;

import com.smartfirehub.dataset.repository.jooq.JooqQueryHistoryRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** QueryHistoryRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-707). */
class JooqQueryHistoryRepositoryContractTest extends QueryHistoryRepositoryContractTest {

  @Autowired private JooqQueryHistoryRepository repository;

  @Override
  protected QueryHistoryRepository repository() {
    return repository;
  }
}
