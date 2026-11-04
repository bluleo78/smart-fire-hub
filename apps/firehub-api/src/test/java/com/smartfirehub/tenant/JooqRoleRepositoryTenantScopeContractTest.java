package com.smartfirehub.tenant;

import com.smartfirehub.role.repository.RoleRepository;
import com.smartfirehub.role.repository.jooq.JooqRoleRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** RoleRepositoryTenantScopeContractTest 를 Jooq(PostgreSQL) 구현으로 실행한다(WD-485). */
class JooqRoleRepositoryTenantScopeContractTest extends RoleRepositoryTenantScopeContractTest {

  @Autowired private JooqRoleRepository repository;

  @Override
  protected RoleRepository repository() {
    return repository;
  }
}
