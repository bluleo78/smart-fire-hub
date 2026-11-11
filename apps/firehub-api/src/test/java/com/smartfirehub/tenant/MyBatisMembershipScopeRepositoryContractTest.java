package com.smartfirehub.tenant;

import com.smartfirehub.permission.repository.PermissionRepository;
import com.smartfirehub.tenant.repository.MembershipRepository;
import com.smartfirehub.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * MembershipScopeRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-706) — 세 리포지토리 모두
 * MyBatis 빈을 이름으로 꺼낸다.
 */
class MyBatisMembershipScopeRepositoryContractTest extends MembershipScopeRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected MembershipRepository membershipRepository() {
    return applicationContext.getBean("myBatisMembershipRepository", MembershipRepository.class);
  }

  @Override
  protected UserRepository userRepository() {
    return applicationContext.getBean("myBatisUserRepository", UserRepository.class);
  }

  @Override
  protected PermissionRepository permissionRepository() {
    return applicationContext.getBean("myBatisPermissionRepository", PermissionRepository.class);
  }
}
