package com.smartfirehub.pipeline.repository;

import com.smartfirehub.global.platformdb.tibero.TiberoPlatformDbErrors;
import com.smartfirehub.pipeline.repository.mybatis.MyBatisPipelineExecutionRepository;
import com.smartfirehub.pipeline.repository.mybatis.PipelineExecutionMapper;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.AccessFixtures;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

/**
 * PipelineExecutionRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-708). 리포지토리는 런타임 계정 +
 * DBMS_RLS 위(하네스 프록시가 호출마다 테넌트 트랜잭션을 연다), 픽스처는 정책을 우회하는 소유자 계정으로 만들고 지운다. FK 위반 판정은 운영과 같은 {@link
 * TiberoPlatformDbErrors} 로 한다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisPipelineExecutionRepositoryContractTest
    extends PipelineExecutionRepositoryContractTest {

  private TiberoPlatformSchema poc;
  private PipelineExecutionRepository repository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    repository =
        poc.transactional(
            PipelineExecutionRepository.class,
            new MyBatisPipelineExecutionRepository(
                poc.mapper(PipelineExecutionMapper.class), new TiberoPlatformDbErrors()));
  }

  @Override
  protected PipelineExecutionRepository repository() {
    return repository;
  }

  @Override
  protected AccessFixtures newFixtures() {
    return new TiberoAccessFixtures(poc);
  }
}
