package com.smartfirehub.notification.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * SlackWorkspaceRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-709) — 구현 클래스가 패키지 전용이라 빈
 * 이름으로 꺼낸다.
 */
class JooqSlackWorkspaceRepositoryContractTest extends SlackWorkspaceRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected SlackWorkspaceRepository repository() {
    return applicationContext.getBean(
        "jooqSlackWorkspaceRepository", SlackWorkspaceRepository.class);
  }
}
