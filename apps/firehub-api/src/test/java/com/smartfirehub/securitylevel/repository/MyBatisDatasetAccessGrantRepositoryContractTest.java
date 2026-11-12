package com.smartfirehub.securitylevel.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * DatasetAccessGrantRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-711) — 빈 이름으로 꺼낸다.
 */
class MyBatisDatasetAccessGrantRepositoryContractTest
    extends DatasetAccessGrantRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected DatasetAccessGrantRepository repository() {
    return applicationContext.getBean(
        "myBatisDatasetAccessGrantRepository", DatasetAccessGrantRepository.class);
  }
}
