package com.smartfirehub.ontology.repository;

import com.smartfirehub.ontology.repository.jooq.JooqDatasetOntologyRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** DatasetOntologyRepositoryContractTest 를 Jooq(PostgreSQL) 구현으로 실행한다(WD-485). */
class JooqDatasetOntologyRepositoryContractTest extends DatasetOntologyRepositoryContractTest {

  @Autowired private JooqDatasetOntologyRepository repository;

  @Override
  protected DatasetOntologyRepository repository() {
    return repository;
  }
}
