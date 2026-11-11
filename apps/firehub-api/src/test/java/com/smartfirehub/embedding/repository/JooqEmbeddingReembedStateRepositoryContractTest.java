package com.smartfirehub.embedding.repository;

import com.smartfirehub.embedding.repository.jooq.JooqEmbeddingReembedStateRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** EmbeddingReembedStateRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-712). */
class JooqEmbeddingReembedStateRepositoryContractTest
    extends EmbeddingReembedStateRepositoryContractTest {

  @Autowired private JooqEmbeddingReembedStateRepository repository;

  @Override
  protected EmbeddingReembedStateRepository repository() {
    return repository;
  }
}
