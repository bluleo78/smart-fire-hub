package com.smartfirehub.dataset.rowsearch;

import com.smartfirehub.dataset.repository.SearchIndexStateRepository;
import com.smartfirehub.dataset.repository.jooq.JooqSearchIndexStateRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** SearchIndexStateRepositoryContractTest 를 Jooq(PostgreSQL) 구현으로 실행한다(WD-485). */
class JooqSearchIndexStateRepositoryContractTest extends SearchIndexStateRepositoryContractTest {

  @Autowired private JooqSearchIndexStateRepository repository;

  @Override
  protected SearchIndexStateRepository repository() {
    return repository;
  }
}
