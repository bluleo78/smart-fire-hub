package com.smartfirehub.ontology.repository;

import com.smartfirehub.ontology.repository.mybatis.DatasetOntologyMapper;
import com.smartfirehub.ontology.repository.mybatis.GraphOntologySourceMapper;
import com.smartfirehub.ontology.repository.mybatis.MyBatisDatasetOntologyRepository;
import com.smartfirehub.ontology.repository.mybatis.MyBatisGraphOntologySourceRepository;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.AccessFixtures;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

/**
 * DatasetOntologyRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-711). bind 가 부르는 출처 기록은
 * 실제 MyBatisGraphOntologySourceRepository 를 쓴다(같은 트랜잭션에 합류).
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisDatasetOntologyRepositoryContractTest
    extends DatasetOntologyRepositoryContractTest {

  private TiberoPlatformSchema poc;
  private DatasetOntologyRepository repository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    repository =
        poc.transactional(
            DatasetOntologyRepository.class,
            new MyBatisDatasetOntologyRepository(
                poc.mapper(DatasetOntologyMapper.class),
                new MyBatisGraphOntologySourceRepository(
                    poc.mapper(GraphOntologySourceMapper.class))));
  }

  @Override
  protected DatasetOntologyRepository repository() {
    return repository;
  }

  @Override
  protected AccessFixtures newFixtures() {
    return new TiberoAccessFixtures(poc);
  }
}
