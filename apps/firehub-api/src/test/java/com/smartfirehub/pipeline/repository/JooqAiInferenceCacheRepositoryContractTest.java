package com.smartfirehub.pipeline.repository;

import com.smartfirehub.pipeline.repository.jooq.JooqAiInferenceCacheRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** AiInferenceCacheRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-708). */
class JooqAiInferenceCacheRepositoryContractTest extends AiInferenceCacheRepositoryContractTest {

  @Autowired private JooqAiInferenceCacheRepository repository;

  @Override
  protected AiInferenceCacheRepository repository() {
    return repository;
  }
}
