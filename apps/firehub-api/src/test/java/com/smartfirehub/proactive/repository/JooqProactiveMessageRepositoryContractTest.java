package com.smartfirehub.proactive.repository;

import com.smartfirehub.proactive.repository.jooq.JooqProactiveMessageRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** ProactiveMessageRepositoryContractTest 를 Jooq(PostgreSQL) 구현으로 실행한다(WD-485). */
class JooqProactiveMessageRepositoryContractTest extends ProactiveMessageRepositoryContractTest {

  @Autowired private JooqProactiveMessageRepository repository;

  @Override
  protected ProactiveMessageRepository repository() {
    return repository;
  }
}
