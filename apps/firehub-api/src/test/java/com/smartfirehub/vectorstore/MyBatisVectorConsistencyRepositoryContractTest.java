package com.smartfirehub.vectorstore;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * VectorConsistencyRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-721) — 빈 이름으로 꺼낸다.
 * Tibero 방언은 {@code TiberoMyBatisVectorConsistencyRepositoryContractTest} 가 실서버로 본다.
 */
class MyBatisVectorConsistencyRepositoryContractTest
    extends VectorConsistencyRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected VectorOutboxRepository outbox() {
    return applicationContext.getBean(
        "myBatisVectorOutboxRepository", VectorOutboxRepository.class);
  }

  @Override
  protected VectorMarkerRepository markers() {
    return applicationContext.getBean(
        "myBatisVectorMarkerRepository", VectorMarkerRepository.class);
  }
}
