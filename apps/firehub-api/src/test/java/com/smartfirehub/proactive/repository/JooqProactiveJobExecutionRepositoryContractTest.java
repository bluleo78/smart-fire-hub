package com.smartfirehub.proactive.repository;

import com.smartfirehub.proactive.repository.jooq.JooqProactiveJobExecutionRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** ProactiveJobExecutionRepositoryContractTest 를 Jooq(PostgreSQL) 구현으로 실행한다(WD-485). */
class JooqProactiveJobExecutionRepositoryContractTest
    extends ProactiveJobExecutionRepositoryContractTest {

  @Autowired private JooqProactiveJobExecutionRepository repository;

  @Override
  protected ProactiveJobExecutionRepository repository() {
    return repository;
  }
}
