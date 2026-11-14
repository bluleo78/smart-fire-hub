package com.smartfirehub.vectorstore;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * SearchCandidateRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-720) — 빈 이름으로 꺼낸다.
 * Tibero 방언은 {@code TiberoMyBatisSearchCandidateRepositoryContractTest} 가 실서버로 본다.
 */
class MyBatisSearchCandidateRepositoryContractTest extends SearchCandidateRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected SearchCandidateRepository repository() {
    return applicationContext.getBean(
        "myBatisSearchCandidateRepository", SearchCandidateRepository.class);
  }
}
