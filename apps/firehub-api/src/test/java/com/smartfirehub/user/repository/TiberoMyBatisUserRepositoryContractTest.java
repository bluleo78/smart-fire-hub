package com.smartfirehub.user.repository;

import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.mybatis.MyBatisUserRepository;
import com.smartfirehub.user.repository.mybatis.UserMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

/**
 * UserRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-706). 리포지토리는 런타임 계정 + DBMS_RLS
 * 위(호출마다 테넌트 컨텍스트로 트랜잭션), 픽스처는 정책을 우회하는 소유자 계정으로 만들고 지운다. 잠금은 하네스 런타임 계정에 DBMS_LOCK 권한이 없어 가짜를 쓴다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisUserRepositoryContractTest extends UserRepositoryContractTest {

  private TiberoPlatformSchema poc;
  private UserRepository repository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    repository =
        poc.transactional(
            UserRepository.class,
            new MyBatisUserRepository(
                poc.mapper(UserMapper.class),
                org.mockito.Mockito.mock(com.smartfirehub.global.platformdb.PlatformLocks.class)));
  }

  @Override
  protected UserRepository repository() {
    return repository;
  }

  @Override
  protected AccessFixtures newFixtures() {
    return new TiberoAccessFixtures(poc);
  }
}
