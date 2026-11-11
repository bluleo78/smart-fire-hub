package com.smartfirehub.auth.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** LoginAttemptRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-706) — 빈 이름으로 꺼낸다. */
class MyBatisLoginAttemptRepositoryContractTest extends LoginAttemptRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected LoginAttemptRepository repository() {
    return applicationContext.getBean(
        "myBatisLoginAttemptRepository", LoginAttemptRepository.class);
  }
}
