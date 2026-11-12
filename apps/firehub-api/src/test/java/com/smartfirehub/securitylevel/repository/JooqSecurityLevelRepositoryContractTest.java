package com.smartfirehub.securitylevel.repository;

import com.smartfirehub.securitylevel.repository.jooq.JooqSecurityLevelRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** SecurityLevelRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-711). */
class JooqSecurityLevelRepositoryContractTest extends SecurityLevelRepositoryContractTest {

  @Autowired private JooqSecurityLevelRepository repository;

  @Override
  protected SecurityLevelRepository repository() {
    return repository;
  }
}
