package com.smartfirehub.tenant;

import com.smartfirehub.permission.repository.PermissionRepository;
import com.smartfirehub.permission.repository.jooq.JooqPermissionRepository;
import com.smartfirehub.tenant.repository.MembershipRepository;
import com.smartfirehub.tenant.repository.jooq.JooqMembershipRepository;
import com.smartfirehub.user.repository.UserRepository;
import com.smartfirehub.user.repository.jooq.JooqUserRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** MembershipScopeRepositoryContractTest 를 Jooq(PostgreSQL) 구현으로 실행한다(WD-485). */
class JooqMembershipScopeRepositoryContractTest extends MembershipScopeRepositoryContractTest {

  @Autowired private JooqMembershipRepository membershipRepository;
  @Autowired private JooqUserRepository userRepository;
  @Autowired private JooqPermissionRepository permissionRepository;

  @Override
  protected MembershipRepository membershipRepository() {
    return membershipRepository;
  }

  @Override
  protected UserRepository userRepository() {
    return userRepository;
  }

  @Override
  protected PermissionRepository permissionRepository() {
    return permissionRepository;
  }
}
