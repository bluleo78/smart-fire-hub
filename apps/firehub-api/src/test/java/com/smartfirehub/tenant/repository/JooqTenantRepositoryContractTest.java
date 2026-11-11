package com.smartfirehub.tenant.repository;

import com.smartfirehub.tenant.repository.jooq.JooqTenantRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** TenantRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-706). */
class JooqTenantRepositoryContractTest extends TenantRepositoryContractTest {

  @Autowired private JooqTenantRepository repository;

  @Override
  protected TenantRepository repository() {
    return repository;
  }
}
