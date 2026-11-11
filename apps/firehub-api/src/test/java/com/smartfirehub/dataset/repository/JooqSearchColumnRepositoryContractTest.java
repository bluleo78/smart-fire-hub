package com.smartfirehub.dataset.repository;

import com.smartfirehub.dataset.repository.jooq.JooqSearchColumnRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** SearchColumnRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-707). */
class JooqSearchColumnRepositoryContractTest extends SearchColumnRepositoryContractTest {

  @Autowired private JooqSearchColumnRepository repository;

  @Override
  protected SearchColumnRepository repository() {
    return repository;
  }
}
