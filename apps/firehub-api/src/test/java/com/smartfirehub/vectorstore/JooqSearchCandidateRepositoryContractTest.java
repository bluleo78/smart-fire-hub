package com.smartfirehub.vectorstore;

import com.smartfirehub.vectorstore.repository.jooq.JooqSearchCandidateRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** SearchCandidateRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-720). */
class JooqSearchCandidateRepositoryContractTest extends SearchCandidateRepositoryContractTest {

  @Autowired private JooqSearchCandidateRepository repository;

  @Override
  protected SearchCandidateRepository repository() {
    return repository;
  }
}
