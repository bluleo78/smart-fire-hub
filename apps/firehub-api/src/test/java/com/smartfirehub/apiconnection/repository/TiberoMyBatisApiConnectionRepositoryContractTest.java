package com.smartfirehub.apiconnection.repository;

import com.smartfirehub.apiconnection.repository.mybatis.ApiConnectionMapper;
import com.smartfirehub.apiconnection.repository.mybatis.MyBatisApiConnectionRepository;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.AccessFixtures;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

/**
 * ApiConnectionRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-708). 리포지토리는 런타임 계정 +
 * DBMS_RLS 위(하네스 프록시가 호출마다 테넌트 트랜잭션을 연다), 픽스처는 정책을 우회하는 소유자 계정으로 만들고 지운다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisApiConnectionRepositoryContractTest extends ApiConnectionRepositoryContractTest {

  private TiberoPlatformSchema poc;
  private ApiConnectionRepository repository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    repository =
        poc.transactional(
            ApiConnectionRepository.class,
            new MyBatisApiConnectionRepository(poc.mapper(ApiConnectionMapper.class)));
  }

  @Override
  protected ApiConnectionRepository repository() {
    return repository;
  }

  @Override
  protected AccessFixtures newFixtures() {
    return new TiberoAccessFixtures(poc);
  }
}
