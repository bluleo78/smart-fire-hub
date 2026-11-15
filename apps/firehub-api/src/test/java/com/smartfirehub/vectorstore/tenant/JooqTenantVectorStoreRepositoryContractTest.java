package com.smartfirehub.vectorstore.tenant;

import com.smartfirehub.vectorstore.repository.jooq.JooqTenantVectorStoreRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** TenantVectorStoreRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-742). */
class JooqTenantVectorStoreRepositoryContractTest extends TenantVectorStoreRepositoryContractTest {

  @Autowired private JooqTenantVectorStoreRepository repository;

  @Override
  protected TenantVectorStoreRepository repository() {
    return repository;
  }
}
