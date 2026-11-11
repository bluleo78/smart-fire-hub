package com.smartfirehub.tenant;

import com.smartfirehub.global.platformdb.PlatformLocks;
import com.smartfirehub.permission.repository.PermissionRepository;
import com.smartfirehub.permission.repository.mybatis.MyBatisPermissionRepository;
import com.smartfirehub.permission.repository.mybatis.PermissionMapper;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.tenant.repository.MembershipRepository;
import com.smartfirehub.tenant.repository.mybatis.MembershipMapper;
import com.smartfirehub.tenant.repository.mybatis.MyBatisMembershipRepository;
import com.smartfirehub.user.repository.AccessFixtures;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import com.smartfirehub.user.repository.UserRepository;
import com.smartfirehub.user.repository.mybatis.MyBatisUserRepository;
import com.smartfirehub.user.repository.mybatis.UserMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;

/**
 * MembershipScopeRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-706). 리포지토리는 런타임 계정 +
 * DBMS_RLS 위, 픽스처는 정책을 우회하는 소유자 계정으로 만들고 지운다. 사용자 리포지토리의 잠금은 하네스 런타임 계정에 DBMS_LOCK 권한이 없어 가짜를 쓴다(이
 * 시나리오는 잠금을 쓰지 않는다).
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisMembershipScopeRepositoryContractTest
    extends MembershipScopeRepositoryContractTest {

  private TiberoPlatformSchema poc;
  private MembershipRepository membershipRepository;
  private UserRepository userRepository;
  private PermissionRepository permissionRepository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    membershipRepository =
        poc.transactional(
            MembershipRepository.class,
            new MyBatisMembershipRepository(poc.mapper(MembershipMapper.class)));
    userRepository =
        poc.transactional(
            UserRepository.class,
            new MyBatisUserRepository(
                poc.mapper(UserMapper.class), Mockito.mock(PlatformLocks.class)));
    permissionRepository =
        poc.transactional(
            PermissionRepository.class,
            new MyBatisPermissionRepository(poc.mapper(PermissionMapper.class)));
  }

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

  @Override
  protected AccessFixtures newFixtures() {
    return new TiberoAccessFixtures(poc);
  }
}
