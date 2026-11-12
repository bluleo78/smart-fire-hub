package com.smartfirehub.mapping.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** MappingRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-708) — 빈 이름으로 꺼낸다. */
class MyBatisMappingRepositoryContractTest extends MappingRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected MappingRepository repository() {
    return applicationContext.getBean("myBatisMappingRepository", MappingRepository.class);
  }
}
