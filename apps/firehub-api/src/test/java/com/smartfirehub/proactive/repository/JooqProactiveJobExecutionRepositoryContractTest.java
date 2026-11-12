package com.smartfirehub.proactive.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * ProactiveJobExecutionRepositoryContractTest 를 Jooq(PostgreSQL) 구현으로 실행한다(WD-485·WD-709) — 빈 이름으로
 * 꺼낸다.
 */
class JooqProactiveJobExecutionRepositoryContractTest
    extends ProactiveJobExecutionRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected ProactiveJobExecutionRepository repository() {
    return applicationContext.getBean(
        "jooqProactiveJobExecutionRepository", ProactiveJobExecutionRepository.class);
  }
}
