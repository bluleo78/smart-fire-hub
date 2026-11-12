package com.smartfirehub.pipeline.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * TriggerRepositoryPollSnapshotContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-708) — 빈 이름으로 꺼낸다.
 */
class MyBatisTriggerRepositoryPollSnapshotContractTest
    extends TriggerRepositoryPollSnapshotContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected TriggerRepository repository() {
    return applicationContext.getBean("myBatisTriggerRepository", TriggerRepository.class);
  }
}
