package com.smartfirehub.role.repository;

import com.smartfirehub.role.repository.jooq.JooqRoleRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** RoleRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-706). */
class JooqRoleRepositoryContractTest extends RoleRepositoryContractTest {

  @Autowired private JooqRoleRepository repository;

  @Override
  protected RoleRepository repository() {
    return repository;
  }
}
