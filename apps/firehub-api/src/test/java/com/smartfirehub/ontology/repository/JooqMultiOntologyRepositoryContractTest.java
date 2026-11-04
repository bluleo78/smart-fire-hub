package com.smartfirehub.ontology.repository;

import com.smartfirehub.ontology.repository.jooq.JooqOntologyRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** MultiOntologyRepositoryContractTest 를 Jooq(PostgreSQL) 구현으로 실행한다(WD-485). */
class JooqMultiOntologyRepositoryContractTest extends MultiOntologyRepositoryContractTest {

  @Autowired private JooqOntologyRepository repository;

  @Override
  protected OntologyRepository repository() {
    return repository;
  }
}
