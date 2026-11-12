package com.smartfirehub.ontology.repository;

import com.smartfirehub.ontology.repository.mybatis.MyBatisOntologyElementRepository;
import com.smartfirehub.ontology.repository.mybatis.MyBatisOntologyRepository;
import com.smartfirehub.ontology.repository.mybatis.OntologyElementMapper;
import com.smartfirehub.ontology.repository.mybatis.OntologyMapper;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.AccessFixtures;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

/**
 * OntologyElementRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-711). 리포지토리는 런타임 계정 +
 * DBMS_RLS 위(하네스 프록시가 호출마다 테넌트 트랜잭션을 연다), 픽스처는 소유자 계정으로 만들고 지운다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisOntologyElementRepositoryContractTest
    extends OntologyElementRepositoryContractTest {

  private TiberoPlatformSchema poc;
  private OntologyElementRepository repository;
  private OntologyRepository ontologyRepository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    repository =
        poc.transactional(
            OntologyElementRepository.class,
            new MyBatisOntologyElementRepository(
                poc.mapper(OntologyElementMapper.class), poc.mapper(OntologyMapper.class)));
    ontologyRepository =
        poc.transactional(
            OntologyRepository.class,
            new MyBatisOntologyRepository(poc.mapper(OntologyMapper.class)));
  }

  @Override
  protected OntologyElementRepository repository() {
    return repository;
  }

  @Override
  protected OntologyRepository ontologyRepository() {
    return ontologyRepository;
  }

  @Override
  protected AccessFixtures newFixtures() {
    return new TiberoAccessFixtures(poc);
  }
}
