package com.smartfirehub.pipeline.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** TriggerEventRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-708) — 빈 이름으로 꺼낸다. */
class MyBatisTriggerEventRepositoryContractTest extends TriggerEventRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected TriggerEventRepository repository() {
    return applicationContext.getBean(
        "myBatisTriggerEventRepository", TriggerEventRepository.class);
  }
}
