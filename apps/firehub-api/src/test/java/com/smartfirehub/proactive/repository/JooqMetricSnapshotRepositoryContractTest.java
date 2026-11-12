package com.smartfirehub.proactive.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** MetricSnapshotRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-709) — 빈 이름으로 꺼낸다. */
class JooqMetricSnapshotRepositoryContractTest extends MetricSnapshotRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected MetricSnapshotRepository repository() {
    return applicationContext.getBean(
        "jooqMetricSnapshotRepository", MetricSnapshotRepository.class);
  }
}
