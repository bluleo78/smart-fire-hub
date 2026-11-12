package com.smartfirehub.proactive.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** AnomalyEventRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-709) — 빈 이름으로 꺼낸다. */
class MyBatisAnomalyEventRepositoryContractTest extends AnomalyEventRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected AnomalyEventRepository repository() {
    return applicationContext.getBean(
        "myBatisAnomalyEventRepository", AnomalyEventRepository.class);
  }
}
