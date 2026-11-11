package com.smartfirehub.settings.repository;

import com.smartfirehub.settings.repository.jooq.JooqSettingsRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** SettingsRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-706). */
class JooqSettingsRepositoryContractTest extends SettingsRepositoryContractTest {

  @Autowired private JooqSettingsRepository repository;

  @Override
  protected SettingsRepository repository() {
    return repository;
  }
}
