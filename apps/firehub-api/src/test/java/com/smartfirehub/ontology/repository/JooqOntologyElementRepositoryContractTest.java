package com.smartfirehub.ontology.repository;

import com.smartfirehub.ontology.repository.jooq.JooqOntologyElementRepository;
import com.smartfirehub.ontology.repository.jooq.JooqOntologyRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** OntologyElementRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-711). */
class JooqOntologyElementRepositoryContractTest extends OntologyElementRepositoryContractTest {

  @Autowired private JooqOntologyElementRepository repository;
  @Autowired private JooqOntologyRepository ontologyRepository;

  @Override
  protected OntologyElementRepository repository() {
    return repository;
  }

  @Override
  protected OntologyRepository ontologyRepository() {
    return ontologyRepository;
  }
}
