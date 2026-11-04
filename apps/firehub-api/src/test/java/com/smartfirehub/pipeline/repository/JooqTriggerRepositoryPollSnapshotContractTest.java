package com.smartfirehub.pipeline.repository;

import com.smartfirehub.pipeline.repository.jooq.JooqTriggerRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** TriggerRepositoryPollSnapshotContractTest 를 Jooq(PostgreSQL) 구현으로 실행한다(WD-485). */
class JooqTriggerRepositoryPollSnapshotContractTest
    extends TriggerRepositoryPollSnapshotContractTest {

  @Autowired private JooqTriggerRepository repository;

  @Override
  protected TriggerRepository repository() {
    return repository;
  }
}
