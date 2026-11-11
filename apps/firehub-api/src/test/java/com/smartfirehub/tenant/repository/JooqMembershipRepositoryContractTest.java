package com.smartfirehub.tenant.repository;

import com.smartfirehub.tenant.repository.jooq.JooqMembershipRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** MembershipRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-706). */
class JooqMembershipRepositoryContractTest extends MembershipRepositoryContractTest {

  @Autowired private JooqMembershipRepository repository;

  @Override
  protected MembershipRepository repository() {
    return repository;
  }
}
