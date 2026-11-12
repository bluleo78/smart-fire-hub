package com.smartfirehub.ontology.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * OntologyElementRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-711) — 빈 이름으로 꺼낸다. 읽기
 * 확인도 MyBatis 온톨로지 리포지토리로 한다.
 */
class MyBatisOntologyElementRepositoryContractTest extends OntologyElementRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected OntologyElementRepository repository() {
    return applicationContext.getBean(
        "myBatisOntologyElementRepository", OntologyElementRepository.class);
  }

  @Override
  protected OntologyRepository ontologyRepository() {
    return applicationContext.getBean("myBatisOntologyRepository", OntologyRepository.class);
  }
}
