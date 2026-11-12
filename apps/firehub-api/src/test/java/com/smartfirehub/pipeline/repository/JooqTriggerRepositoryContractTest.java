package com.smartfirehub.pipeline.repository;

import com.smartfirehub.pipeline.repository.jooq.JooqTriggerRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** TriggerRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-708). */
class JooqTriggerRepositoryContractTest extends TriggerRepositoryContractTest {

  @Autowired private JooqTriggerRepository repository;

  @Override
  protected TriggerRepository repository() {
    return repository;
  }
}
