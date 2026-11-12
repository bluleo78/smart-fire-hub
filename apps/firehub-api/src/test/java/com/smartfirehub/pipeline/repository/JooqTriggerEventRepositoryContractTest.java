package com.smartfirehub.pipeline.repository;

import com.smartfirehub.pipeline.repository.jooq.JooqTriggerEventRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** TriggerEventRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-708). */
class JooqTriggerEventRepositoryContractTest extends TriggerEventRepositoryContractTest {

  @Autowired private JooqTriggerEventRepository repository;

  @Override
  protected TriggerEventRepository repository() {
    return repository;
  }
}
