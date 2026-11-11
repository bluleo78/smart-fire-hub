package com.smartfirehub.platform.repository;

import com.smartfirehub.platform.repository.mybatis.MyBatisPlatformUserRepository;
import com.smartfirehub.platform.repository.mybatis.PlatformUserMapper;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.AccessFixtures;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

/**
 * PlatformUserRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-706). 리포지토리는 런타임 계정 +
 * DBMS_RLS 위(구현의 트랜잭션 애너테이션을 하네스 프록시가 그대로 읽는다), 픽스처는 정책을 우회하는 소유자 계정으로 만들고 지운다. 대상 테이블은 정책 없는 전역
 * 테이블이라 테넌트 컨텍스트 없는 런타임 계정도 전 테넌트 행을 본다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisPlatformUserRepositoryContractTest extends PlatformUserRepositoryContractTest {

  private TiberoPlatformSchema poc;
  private PlatformUserRepository repository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    repository =
        poc.transactional(
            PlatformUserRepository.class,
            new MyBatisPlatformUserRepository(poc.mapper(PlatformUserMapper.class)));
  }

  @Override
  protected PlatformUserRepository repository() {
    return repository;
  }

  @Override
  protected AccessFixtures newFixtures() {
    return new TiberoAccessFixtures(poc);
  }
}
