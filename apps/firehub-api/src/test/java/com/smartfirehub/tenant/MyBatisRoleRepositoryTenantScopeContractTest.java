package com.smartfirehub.tenant;

import com.smartfirehub.role.repository.RoleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** RoleRepositoryTenantScopeContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-706) — 빈 이름으로 꺼낸다. */
class MyBatisRoleRepositoryTenantScopeContractTest extends RoleRepositoryTenantScopeContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected RoleRepository repository() {
    return applicationContext.getBean("myBatisRoleRepository", RoleRepository.class);
  }
}
