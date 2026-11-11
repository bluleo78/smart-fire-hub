package com.smartfirehub.dataset.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * DatasetColumnSnapshotRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-707). 이 계약은
 * DatasetService· information_schema 를 써서 PG 전용이라 Tibero 하위는 없다 — Tibero 의 보관·복원은
 * DatasetColumnRepositoryContractTest 가 덮는다.
 */
class MyBatisDatasetColumnSnapshotRepositoryContractTest
    extends DatasetColumnSnapshotRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected DatasetColumnRepository repository() {
    return applicationContext.getBean(
        "myBatisDatasetColumnRepository", DatasetColumnRepository.class);
  }
}
