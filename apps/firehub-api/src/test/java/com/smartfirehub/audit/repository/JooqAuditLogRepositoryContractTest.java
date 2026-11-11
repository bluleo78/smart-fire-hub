package com.smartfirehub.audit.repository;

import com.smartfirehub.audit.repository.jooq.JooqAuditLogRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** AuditLogRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-706). */
class JooqAuditLogRepositoryContractTest extends AuditLogRepositoryContractTest {

  @Autowired private JooqAuditLogRepository repository;

  @Override
  protected AuditLogRepository repository() {
    return repository;
  }
}
