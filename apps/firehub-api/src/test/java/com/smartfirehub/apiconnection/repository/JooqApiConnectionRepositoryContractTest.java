package com.smartfirehub.apiconnection.repository;

import com.smartfirehub.apiconnection.repository.jooq.JooqApiConnectionRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** ApiConnectionRepositoryContractTest 를 Jooq(PostgreSQL) 구현으로 실행한다(WD-485). */
class JooqApiConnectionRepositoryContractTest extends ApiConnectionRepositoryContractTest {

  @Autowired private JooqApiConnectionRepository repository;

  @Override
  protected ApiConnectionRepository repository() {
    return repository;
  }
}
