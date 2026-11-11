package com.smartfirehub.user.repository;

import com.smartfirehub.user.repository.jooq.JooqUserRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** UserRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-706). */
class JooqUserRepositoryContractTest extends UserRepositoryContractTest {

  @Autowired private JooqUserRepository repository;

  @Override
  protected UserRepository repository() {
    return repository;
  }
}
