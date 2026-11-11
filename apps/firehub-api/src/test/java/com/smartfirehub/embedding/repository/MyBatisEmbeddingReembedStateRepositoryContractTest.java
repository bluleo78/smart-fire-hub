package com.smartfirehub.embedding.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * EmbeddingReembedStateRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-712) — 빈 이름으로 꺼낸다.
 */
class MyBatisEmbeddingReembedStateRepositoryContractTest
    extends EmbeddingReembedStateRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected EmbeddingReembedStateRepository repository() {
    return applicationContext.getBean(
        "myBatisEmbeddingReembedStateRepository", EmbeddingReembedStateRepository.class);
  }
}
