package com.smartfirehub.platform.repository;

import com.smartfirehub.platform.repository.jooq.JooqPlatformRoleRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** PlatformRoleRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-706). */
class JooqPlatformRoleRepositoryContractTest extends PlatformRoleRepositoryContractTest {

  @Autowired private JooqPlatformRoleRepository repository;

  @Override
  protected PlatformRoleRepository repository() {
    return repository;
  }
}
