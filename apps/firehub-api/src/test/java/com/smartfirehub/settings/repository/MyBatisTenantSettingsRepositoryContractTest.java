package com.smartfirehub.settings.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** TenantSettingsRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-706) — 빈 이름으로 꺼낸다. */
class MyBatisTenantSettingsRepositoryContractTest extends TenantSettingsRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected TenantSettingsRepository repository() {
    return applicationContext.getBean(
        "myBatisTenantSettingsRepository", TenantSettingsRepository.class);
  }
}
