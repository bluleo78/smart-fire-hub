package com.smartfirehub.proactive.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * ProactiveMessageRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-487 PoC).
 *
 * <p>MyBatis 구현 빈은 {@code autowireCandidate=false} 라 타입 주입에 끼지 않아 빈 이름으로 꺼낸다.
 */
class MyBatisProactiveMessageRepositoryContractTest extends ProactiveMessageRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected ProactiveMessageRepository repository() {
    return applicationContext.getBean(
        "myBatisProactiveMessageRepository", ProactiveMessageRepository.class);
  }
}
