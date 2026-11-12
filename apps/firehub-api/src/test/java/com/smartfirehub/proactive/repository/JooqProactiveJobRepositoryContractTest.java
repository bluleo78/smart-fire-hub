package com.smartfirehub.proactive.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** ProactiveJobRepositoryContractTest 를 Jooq(PostgreSQL) 구현으로 실행한다(WD-709) — 빈 이름으로 꺼낸다. */
class JooqProactiveJobRepositoryContractTest extends ProactiveJobRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected ProactiveJobRepository repository() {
    return applicationContext.getBean("jooqProactiveJobRepository", ProactiveJobRepository.class);
  }
}
