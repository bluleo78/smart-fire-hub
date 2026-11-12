package com.smartfirehub.apiconnection.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** ApiConnectionRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-708) — 빈 이름으로 꺼낸다. */
class MyBatisApiConnectionRepositoryContractTest extends ApiConnectionRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected ApiConnectionRepository repository() {
    return applicationContext.getBean(
        "myBatisApiConnectionRepository", ApiConnectionRepository.class);
  }
}
