package com.smartfirehub.role.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** RoleRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-706) — 빈 이름으로 꺼낸다. */
class MyBatisRoleRepositoryContractTest extends RoleRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected RoleRepository repository() {
    return applicationContext.getBean("myBatisRoleRepository", RoleRepository.class);
  }
}
