package com.smartfirehub.audit.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** AuditLogRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-706) — 빈 이름으로 꺼낸다. */
class MyBatisAuditLogRepositoryContractTest extends AuditLogRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected AuditLogRepository repository() {
    return applicationContext.getBean("myBatisAuditLogRepository", AuditLogRepository.class);
  }
}
