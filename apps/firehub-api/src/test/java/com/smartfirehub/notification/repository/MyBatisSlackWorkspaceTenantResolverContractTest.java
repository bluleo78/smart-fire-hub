package com.smartfirehub.notification.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * SlackWorkspaceTenantResolverContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-709) — 빈 이름으로 꺼낸다.
 */
class MyBatisSlackWorkspaceTenantResolverContractTest
    extends SlackWorkspaceTenantResolverContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected SlackWorkspaceTenantResolver resolver() {
    return applicationContext.getBean(
        "myBatisSlackWorkspaceTenantResolver", SlackWorkspaceTenantResolver.class);
  }

  @Override
  protected SlackWorkspaceRepository workspaceRepository() {
    return applicationContext.getBean(
        "myBatisSlackWorkspaceRepository", SlackWorkspaceRepository.class);
  }
}
