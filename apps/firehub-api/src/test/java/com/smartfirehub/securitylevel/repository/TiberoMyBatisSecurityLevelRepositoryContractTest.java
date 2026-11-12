package com.smartfirehub.securitylevel.repository;

import com.smartfirehub.global.platformdb.PlatformLocks;
import com.smartfirehub.securitylevel.repository.mybatis.MyBatisSecurityLevelRepository;
import com.smartfirehub.securitylevel.repository.mybatis.SecurityLevelMapper;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.AccessFixtures;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;

/**
 * SecurityLevelRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-711). 리포지토리는 런타임 계정 +
 * DBMS_RLS 위(하네스 프록시가 호출마다 테넌트 트랜잭션을 연다), 픽스처는 정책을 우회하는 소유자 계정으로 만들고 지운다. 잠금 창구는 하네스 런타임 계정에
 * DBMS_LOCK 권한이 없어 가짜를 쓴다(키는 MyBatisSecurityLevelRepositoryLockKeyTest 가 고정).
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisSecurityLevelRepositoryContractTest extends SecurityLevelRepositoryContractTest {

  private TiberoPlatformSchema poc;
  private SecurityLevelRepository repository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    repository =
        poc.transactional(
            SecurityLevelRepository.class,
            new MyBatisSecurityLevelRepository(
                poc.mapper(SecurityLevelMapper.class), Mockito.mock(PlatformLocks.class)));
  }

  @Override
  protected SecurityLevelRepository repository() {
    return repository;
  }

  @Override
  protected AccessFixtures newFixtures() {
    return new TiberoAccessFixtures(poc);
  }
}
