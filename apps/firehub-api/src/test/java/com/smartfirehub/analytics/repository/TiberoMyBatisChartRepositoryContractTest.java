package com.smartfirehub.analytics.repository;

import com.smartfirehub.analytics.repository.mybatis.ChartMapper;
import com.smartfirehub.analytics.repository.mybatis.MyBatisChartRepository;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.AccessFixtures;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.json.JsonMapper;

/**
 * ChartRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-710). 리포지토리는 런타임 계정 + DBMS_RLS
 * 위(하네스 프록시가 호출마다 테넌트 트랜잭션을 연다), 픽스처는 정책을 우회하는 소유자 계정으로 만들고 지운다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisChartRepositoryContractTest extends ChartRepositoryContractTest {

  private TiberoPlatformSchema poc;
  private ChartRepository repository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    repository =
        poc.transactional(
            ChartRepository.class,
            new MyBatisChartRepository(
                poc.mapper(ChartMapper.class),
                JsonMapper.builderWithJackson2Defaults().build(),
                poc.sqlSessionFactory()));
  }

  @Override
  protected ChartRepository repository() {
    return repository;
  }

  @Override
  protected AccessFixtures newFixtures() {
    return new TiberoAccessFixtures(poc);
  }
}
