package com.smartfirehub.mapping.repository;

import com.smartfirehub.mapping.repository.jooq.JooqMappingRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** MappingRepositoryContractTest 를 Jooq(PostgreSQL) 구현으로 실행한다(WD-485). */
class JooqMappingRepositoryContractTest extends MappingRepositoryContractTest {

  @Autowired private JooqMappingRepository repository;

  @Override
  protected MappingRepository repository() {
    return repository;
  }
}
