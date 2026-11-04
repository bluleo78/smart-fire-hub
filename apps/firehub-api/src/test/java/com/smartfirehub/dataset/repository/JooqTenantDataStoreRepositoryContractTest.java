package com.smartfirehub.dataset.repository;

import com.smartfirehub.dataset.repository.jooq.JooqTenantDataStoreRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** TenantDataStoreRepositoryContractTest 를 Jooq(PostgreSQL) 구현으로 실행한다(WD-485). */
class JooqTenantDataStoreRepositoryContractTest extends TenantDataStoreRepositoryContractTest {

  @Autowired private JooqTenantDataStoreRepository repository;

  @Override
  protected TenantDataStoreRepository repository() {
    return repository;
  }
}
