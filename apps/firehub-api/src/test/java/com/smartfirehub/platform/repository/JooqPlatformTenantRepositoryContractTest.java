package com.smartfirehub.platform.repository;

import com.smartfirehub.platform.repository.jooq.JooqPlatformTenantRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** PlatformTenantRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-706). */
class JooqPlatformTenantRepositoryContractTest extends PlatformTenantRepositoryContractTest {

  @Autowired private JooqPlatformTenantRepository repository;

  @Override
  protected PlatformTenantRepository repository() {
    return repository;
  }
}
