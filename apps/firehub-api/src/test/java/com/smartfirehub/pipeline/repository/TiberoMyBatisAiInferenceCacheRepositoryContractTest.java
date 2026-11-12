package com.smartfirehub.pipeline.repository;

import com.smartfirehub.pipeline.repository.mybatis.AiInferenceCacheMapper;
import com.smartfirehub.pipeline.repository.mybatis.MyBatisAiInferenceCacheRepository;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.AccessFixtures;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

/**
 * AiInferenceCacheRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-708). 리포지토리는 런타임 계정 +
 * DBMS_RLS 위, 픽스처는 정책을 우회하는 소유자 계정으로 만들고 지운다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisAiInferenceCacheRepositoryContractTest
    extends AiInferenceCacheRepositoryContractTest {

  private TiberoPlatformSchema poc;
  private AiInferenceCacheRepository repository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    repository =
        poc.transactional(
            AiInferenceCacheRepository.class,
            new MyBatisAiInferenceCacheRepository(poc.mapper(AiInferenceCacheMapper.class)));
  }

  @Override
  protected AiInferenceCacheRepository repository() {
    return repository;
  }

  @Override
  protected AccessFixtures newFixtures() {
    return new TiberoAccessFixtures(poc);
  }
}
