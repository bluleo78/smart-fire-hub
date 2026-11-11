package com.smartfirehub.permission.repository;

import com.smartfirehub.permission.repository.jooq.JooqPermissionRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** PermissionRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-706). */
class JooqPermissionRepositoryContractTest extends PermissionRepositoryContractTest {

  @Autowired private JooqPermissionRepository repository;

  @Override
  protected PermissionRepository repository() {
    return repository;
  }
}
