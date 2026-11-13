package com.smartfirehub.dataset.repository;

import com.smartfirehub.dataset.repository.mybatis.DatasetEmbeddingMapper;
import com.smartfirehub.dataset.repository.mybatis.MyBatisDatasetEmbeddingRepository;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.AccessFixtures;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import com.smartfirehub.vectorstore.off.OffDatasetVectorIndex;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

/**
 * DatasetEmbeddingRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-707). 리포지토리는 런타임 계정 +
 * DBMS_RLS 위, 픽스처는 소유자 계정. Tibero 기준선에는 벡터 테이블이 없어 벡터 메서드는 예외를 단언한다(스펙 D1).
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisDatasetEmbeddingRepositoryContractTest
    extends DatasetEmbeddingRepositoryContractTest {

  private TiberoPlatformSchema poc;
  private DatasetEmbeddingRepository repository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    repository =
        poc.transactional(
            DatasetEmbeddingRepository.class,
            new MyBatisDatasetEmbeddingRepository(
                poc.mapper(DatasetEmbeddingMapper.class), new OffDatasetVectorIndex()));
  }

  @Override
  protected DatasetEmbeddingRepository repository() {
    return repository;
  }

  @Override
  protected boolean supportsVectorSearch() {
    return false;
  }

  @Override
  protected AccessFixtures newFixtures() {
    return new TiberoAccessFixtures(poc);
  }
}
