package com.smartfirehub.ontology.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** MultiOntologyRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-711) — 빈 이름으로 꺼낸다. */
class MyBatisMultiOntologyRepositoryContractTest extends MultiOntologyRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected OntologyRepository repository() {
    return applicationContext.getBean("myBatisOntologyRepository", OntologyRepository.class);
  }
}
