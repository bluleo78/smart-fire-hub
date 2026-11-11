package com.smartfirehub.auth.repository;

import com.smartfirehub.auth.repository.jooq.JooqLoginIpFailureRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** LoginIpFailureRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-706). */
class JooqLoginIpFailureRepositoryContractTest extends LoginIpFailureRepositoryContractTest {

  @Autowired private JooqLoginIpFailureRepository repository;

  @Override
  protected LoginIpFailureRepository repository() {
    return repository;
  }
}
