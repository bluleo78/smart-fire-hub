package com.smartfirehub.notification.repository;

import com.smartfirehub.notification.repository.mybatis.MyBatisUserChannelBindingRepository;
import com.smartfirehub.notification.repository.mybatis.UserChannelBindingMapper;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.AccessFixtures;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

/**
 * UserChannelBindingRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-709). 리포지토리는 런타임 계정 +
 * DBMS_RLS 위(하네스 프록시가 호출마다 테넌트 트랜잭션을 연다), 픽스처는 정책을 우회하는 소유자 계정으로 만들고 지운다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisUserChannelBindingRepositoryContractTest
    extends UserChannelBindingRepositoryContractTest {

  private TiberoPlatformSchema poc;
  private UserChannelBindingRepository repository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    repository =
        poc.transactional(
            UserChannelBindingRepository.class,
            new MyBatisUserChannelBindingRepository(poc.mapper(UserChannelBindingMapper.class)));
  }

  @Override
  protected UserChannelBindingRepository repository() {
    return repository;
  }

  @Override
  protected AccessFixtures newFixtures() {
    return new TiberoAccessFixtures(poc);
  }
}
