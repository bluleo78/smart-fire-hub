package com.smartfirehub.pipeline.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** AiInferenceCacheRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-708) — 빈 이름으로 꺼낸다. */
class MyBatisAiInferenceCacheRepositoryContractTest extends AiInferenceCacheRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected AiInferenceCacheRepository repository() {
    return applicationContext.getBean(
        "myBatisAiInferenceCacheRepository", AiInferenceCacheRepository.class);
  }
}
