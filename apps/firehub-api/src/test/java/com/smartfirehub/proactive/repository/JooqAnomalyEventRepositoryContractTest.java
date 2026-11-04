package com.smartfirehub.proactive.repository;

import com.smartfirehub.proactive.repository.jooq.JooqAnomalyEventRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** AnomalyEventRepositoryContractTest 를 Jooq(PostgreSQL) 구현으로 실행한다(WD-485). */
class JooqAnomalyEventRepositoryContractTest extends AnomalyEventRepositoryContractTest {

  @Autowired private JooqAnomalyEventRepository repository;

  @Override
  protected AnomalyEventRepository repository() {
    return repository;
  }
}
