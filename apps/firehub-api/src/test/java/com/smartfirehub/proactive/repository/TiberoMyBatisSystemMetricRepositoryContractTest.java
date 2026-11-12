package com.smartfirehub.proactive.repository;

import com.smartfirehub.proactive.repository.mybatis.MyBatisSystemMetricRepository;
import com.smartfirehub.proactive.repository.mybatis.SystemMetricMapper;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.AccessFixtures;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

/**
 * SystemMetricRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-709). 이 저장소는 클래스 트랜잭션이 없으나
 * 하네스 프록시({@code transactional(...)})가 호출마다 테넌트 트랜잭션을 연다 — 운영에서는 호출자(MetricPollerService)의 {@code
 * TransactionTemplate} 이 그 역할이다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisSystemMetricRepositoryContractTest extends SystemMetricRepositoryContractTest {

  private TiberoPlatformSchema poc;
  private SystemMetricRepository repository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    repository =
        poc.transactional(
            SystemMetricRepository.class,
            new MyBatisSystemMetricRepository(poc.mapper(SystemMetricMapper.class)));
  }

  @Override
  protected SystemMetricRepository repository() {
    return repository;
  }

  @Override
  protected AccessFixtures newFixtures() {
    return new TiberoAccessFixtures(poc);
  }
}
