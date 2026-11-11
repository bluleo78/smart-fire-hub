package com.smartfirehub.auth.repository;

import com.smartfirehub.auth.repository.jooq.JooqLoginAttemptRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** LoginAttemptRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-706). */
class JooqLoginAttemptRepositoryContractTest extends LoginAttemptRepositoryContractTest {

  @Autowired private JooqLoginAttemptRepository repository;

  @Override
  protected LoginAttemptRepository repository() {
    return repository;
  }
}
