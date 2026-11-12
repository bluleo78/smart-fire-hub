package com.smartfirehub.ontology.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** DatasetOntologyRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-711) — 빈 이름으로 꺼낸다. */
class MyBatisDatasetOntologyRepositoryContractTest extends DatasetOntologyRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected DatasetOntologyRepository repository() {
    return applicationContext.getBean(
        "myBatisDatasetOntologyRepository", DatasetOntologyRepository.class);
  }
}
