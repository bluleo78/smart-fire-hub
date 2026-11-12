package com.smartfirehub.securitylevel.repository;

import com.smartfirehub.securitylevel.repository.jooq.JooqDatasetAccessRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** DatasetAccessRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-711). */
class JooqDatasetAccessRepositoryContractTest extends DatasetAccessRepositoryContractTest {

  @Autowired private JooqDatasetAccessRepository repository;

  @Override
  protected DatasetAccessRepository repository() {
    return repository;
  }
}
