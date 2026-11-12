package com.smartfirehub.securitylevel.repository;

import com.smartfirehub.securitylevel.repository.jooq.JooqDatasetAccessGrantRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** DatasetAccessGrantRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-711). */
class JooqDatasetAccessGrantRepositoryContractTest
    extends DatasetAccessGrantRepositoryContractTest {

  @Autowired private JooqDatasetAccessGrantRepository repository;

  @Override
  protected DatasetAccessGrantRepository repository() {
    return repository;
  }
}
