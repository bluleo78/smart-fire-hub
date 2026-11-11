package com.smartfirehub.tenant.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** TenantRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-706) — 빈 이름으로 꺼낸다. */
class MyBatisTenantRepositoryContractTest extends TenantRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected TenantRepository repository() {
    return applicationContext.getBean("myBatisTenantRepository", TenantRepository.class);
  }
}
