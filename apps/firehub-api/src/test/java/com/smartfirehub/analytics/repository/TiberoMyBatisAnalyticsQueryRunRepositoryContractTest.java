package com.smartfirehub.analytics.repository;

import com.smartfirehub.analytics.repository.mybatis.AnalyticsQueryRunMapper;
import com.smartfirehub.analytics.repository.mybatis.MyBatisAnalyticsQueryRunRepository;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.AccessFixtures;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

/**
 * AnalyticsQueryRunRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-710). 리포지토리는 런타임 계정 +
 * DBMS_RLS 위(하네스 프록시가 호출마다 테넌트 트랜잭션을 연다), 픽스처는 정책을 우회하는 소유자 계정으로 만들고 지운다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisAnalyticsQueryRunRepositoryContractTest
    extends AnalyticsQueryRunRepositoryContractTest {

  private TiberoPlatformSchema poc;
  private AnalyticsQueryRunRepository repository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    repository =
        poc.transactional(
            AnalyticsQueryRunRepository.class,
            new MyBatisAnalyticsQueryRunRepository(poc.mapper(AnalyticsQueryRunMapper.class)));
  }

  @Override
  protected AnalyticsQueryRunRepository repository() {
    return repository;
  }

  @Override
  protected AccessFixtures newFixtures() {
    return new TiberoAccessFixtures(poc);
  }
}
