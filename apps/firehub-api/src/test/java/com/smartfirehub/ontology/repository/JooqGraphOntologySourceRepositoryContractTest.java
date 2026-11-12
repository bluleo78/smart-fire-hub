package com.smartfirehub.ontology.repository;

import com.smartfirehub.ontology.repository.jooq.JooqGraphOntologySourceRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** GraphOntologySourceRepositoryContractTest 를 Jooq(PostgreSQL) 구현으로 실행한다(WD-485). */
class JooqGraphOntologySourceRepositoryContractTest
    extends GraphOntologySourceRepositoryContractTest {

  @Autowired private JooqGraphOntologySourceRepository repository;

  @Override
  protected GraphOntologySourceRepository repository() {
    return repository;
  }
}
