package com.smartfirehub.auth.repository;

import com.smartfirehub.auth.repository.jooq.JooqRefreshTokenRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** RefreshTokenRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-706). */
class JooqRefreshTokenRepositoryContractTest extends RefreshTokenRepositoryContractTest {

  @Autowired private JooqRefreshTokenRepository repository;

  @Override
  protected RefreshTokenRepository repository() {
    return repository;
  }
}
