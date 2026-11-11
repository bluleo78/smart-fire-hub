package com.smartfirehub.dataset.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** QueryHistoryRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-707) — 빈 이름으로 꺼낸다. */
class MyBatisQueryHistoryRepositoryContractTest extends QueryHistoryRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected QueryHistoryRepository repository() {
    return applicationContext.getBean(
        "myBatisQueryHistoryRepository", QueryHistoryRepository.class);
  }
}
