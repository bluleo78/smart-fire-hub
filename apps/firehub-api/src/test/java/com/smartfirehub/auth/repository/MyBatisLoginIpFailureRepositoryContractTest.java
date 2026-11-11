package com.smartfirehub.auth.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** LoginIpFailureRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-706) — 빈 이름으로 꺼낸다. */
class MyBatisLoginIpFailureRepositoryContractTest extends LoginIpFailureRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected LoginIpFailureRepository repository() {
    return applicationContext.getBean(
        "myBatisLoginIpFailureRepository", LoginIpFailureRepository.class);
  }
}
