package com.smartfirehub.user.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** UserRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-706) — 빈 이름으로 꺼낸다. */
class MyBatisUserRepositoryContractTest extends UserRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected UserRepository repository() {
    return applicationContext.getBean("myBatisUserRepository", UserRepository.class);
  }
}
