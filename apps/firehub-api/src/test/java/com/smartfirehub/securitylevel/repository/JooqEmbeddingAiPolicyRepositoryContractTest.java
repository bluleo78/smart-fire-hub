package com.smartfirehub.securitylevel.repository;

import com.smartfirehub.securitylevel.repository.jooq.JooqEmbeddingAiPolicyRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** EmbeddingAiPolicyRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-711). */
class JooqEmbeddingAiPolicyRepositoryContractTest extends EmbeddingAiPolicyRepositoryContractTest {

  @Autowired private JooqEmbeddingAiPolicyRepository repository;

  @Override
  protected EmbeddingAiPolicyRepository repository() {
    return repository;
  }
}
