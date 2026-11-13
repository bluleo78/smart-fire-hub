package com.smartfirehub.analytics.repository;

import com.smartfirehub.analytics.repository.jooq.JooqSavedQueryRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** SavedQueryRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-710). */
class JooqSavedQueryRepositoryContractTest extends SavedQueryRepositoryContractTest {

  @Autowired private JooqSavedQueryRepository repository;

  @Override
  protected SavedQueryRepository repository() {
    return repository;
  }
}
