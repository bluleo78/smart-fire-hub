package com.smartfirehub.proactive.repository;

import com.smartfirehub.proactive.repository.jooq.JooqMetricSnapshotRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** MetricSnapshotRepositoryContractTest 를 Jooq(PostgreSQL) 구현으로 실행한다(WD-485). */
class JooqMetricSnapshotRepositoryContractTest extends MetricSnapshotRepositoryContractTest {

  @Autowired private JooqMetricSnapshotRepository repository;

  @Override
  protected MetricSnapshotRepository repository() {
    return repository;
  }
}
