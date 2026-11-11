package com.smartfirehub.dataset.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** TenantDataStoreRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-707) — 빈 이름으로 꺼낸다. */
class MyBatisTenantDataStoreRepositoryContractTest extends TenantDataStoreRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected TenantDataStoreRepository repository() {
    return applicationContext.getBean(
        "myBatisTenantDataStoreRepository", TenantDataStoreRepository.class);
  }
}
