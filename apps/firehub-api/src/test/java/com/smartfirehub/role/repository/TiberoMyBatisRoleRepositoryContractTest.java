package com.smartfirehub.role.repository;

import com.smartfirehub.role.repository.mybatis.MyBatisRoleRepository;
import com.smartfirehub.role.repository.mybatis.RoleMapper;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.AccessFixtures;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

/**
 * RoleRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-706). 리포지토리는 런타임 계정 + DBMS_RLS
 * 위(호출마다 테넌트 컨텍스트로 트랜잭션), 픽스처는 정책을 우회하는 소유자 계정으로 만들고 지운다. 잠금은 하네스 런타임 계정에 DBMS_LOCK 권한이 없어 가짜를 쓴다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisRoleRepositoryContractTest extends RoleRepositoryContractTest {

  private TiberoPlatformSchema poc;
  private RoleRepository repository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    repository =
        poc.transactional(
            RoleRepository.class, new MyBatisRoleRepository(poc.mapper(RoleMapper.class)));
  }

  @Override
  protected RoleRepository repository() {
    return repository;
  }

  @Override
  protected AccessFixtures newFixtures() {
    return new TiberoAccessFixtures(poc);
  }
}
