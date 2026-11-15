package com.smartfirehub.vectorstore.tenant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** TenantVectorStoreRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-742) — 빈 이름으로 꺼낸다. */
class MyBatisTenantVectorStoreRepositoryContractTest
    extends TenantVectorStoreRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected TenantVectorStoreRepository repository() {
    return applicationContext.getBean(
        "myBatisTenantVectorStoreRepository", TenantVectorStoreRepository.class);
  }
}
