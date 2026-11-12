package com.smartfirehub.securitylevel.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** SecurityLevelRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-711) — 빈 이름으로 꺼낸다. */
class MyBatisSecurityLevelRepositoryContractTest extends SecurityLevelRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected SecurityLevelRepository repository() {
    return applicationContext.getBean(
        "myBatisSecurityLevelRepository", SecurityLevelRepository.class);
  }
}
