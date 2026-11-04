package com.smartfirehub.dataset.repository;

import com.smartfirehub.dataset.repository.jooq.JooqDatasetColumnRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** DatasetColumnSnapshotRepositoryContractTest 를 Jooq(PostgreSQL) 구현으로 실행한다(WD-485). */
class JooqDatasetColumnSnapshotRepositoryContractTest
    extends DatasetColumnSnapshotRepositoryContractTest {

  @Autowired private JooqDatasetColumnRepository repository;

  @Override
  protected DatasetColumnRepository repository() {
    return repository;
  }
}
