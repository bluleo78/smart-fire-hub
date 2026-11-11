package com.smartfirehub.platform.repository;

import com.smartfirehub.platform.repository.jooq.JooqPlatformUserRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** PlatformUserRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-706). */
class JooqPlatformUserRepositoryContractTest extends PlatformUserRepositoryContractTest {

  @Autowired private JooqPlatformUserRepository repository;

  @Override
  protected PlatformUserRepository repository() {
    return repository;
  }
}
