package com.smartfirehub.settings.repository;

import com.smartfirehub.settings.repository.jooq.JooqTenantSettingsRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** TenantSettingsRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-706). */
class JooqTenantSettingsRepositoryContractTest extends TenantSettingsRepositoryContractTest {

  @Autowired private JooqTenantSettingsRepository repository;

  @Override
  protected TenantSettingsRepository repository() {
    return repository;
  }
}
