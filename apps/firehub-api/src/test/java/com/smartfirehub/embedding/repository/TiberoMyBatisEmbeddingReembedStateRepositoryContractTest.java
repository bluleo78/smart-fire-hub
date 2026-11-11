package com.smartfirehub.embedding.repository;

import com.smartfirehub.embedding.repository.mybatis.EmbeddingReembedStateMapper;
import com.smartfirehub.embedding.repository.mybatis.MyBatisEmbeddingReembedStateRepository;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.AccessFixtures;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

/**
 * EmbeddingReembedStateRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-712). 리포지토리는 런타임
 * 계정 + DBMS_RLS 위(하네스 프록시가 호출마다 테넌트 트랜잭션을 연다), 픽스처는 정책을 우회하는 소유자 계정으로 만들고 지운다. 동시 획득 시나리오는 런타임 풀(크기
 * 2)의 커넥션 둘을 모두 쓴다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisEmbeddingReembedStateRepositoryContractTest
    extends EmbeddingReembedStateRepositoryContractTest {

  private TiberoPlatformSchema poc;
  private EmbeddingReembedStateRepository repository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    repository =
        poc.transactional(
            EmbeddingReembedStateRepository.class,
            new MyBatisEmbeddingReembedStateRepository(
                poc.mapper(EmbeddingReembedStateMapper.class)));
  }

  @Override
  protected EmbeddingReembedStateRepository repository() {
    return repository;
  }

  @Override
  protected AccessFixtures newFixtures() {
    return new TiberoAccessFixtures(poc);
  }
}
