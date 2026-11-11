package com.smartfirehub.settings.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** SettingsRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-706) — 빈 이름으로 꺼낸다. */
class MyBatisSettingsRepositoryContractTest extends SettingsRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected SettingsRepository repository() {
    return applicationContext.getBean("myBatisSettingsRepository", SettingsRepository.class);
  }
}
