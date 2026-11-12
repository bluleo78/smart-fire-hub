package com.smartfirehub.mapping.repository;

import com.smartfirehub.mapping.repository.mybatis.MappingMapper;
import com.smartfirehub.mapping.repository.mybatis.MyBatisMappingRepository;
import com.smartfirehub.ontology.repository.mybatis.GraphOntologySourceMapper;
import com.smartfirehub.ontology.repository.mybatis.MyBatisGraphOntologySourceRepository;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.AccessFixtures;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

/**
 * MappingRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-708). 리포지토리는 런타임 계정 + DBMS_RLS
 * 위(하네스 프록시가 호출마다 테넌트 트랜잭션을 연다), 픽스처는 정책을 우회하는 소유자 계정으로 만들고 지운다. 출처 기록 의존은 실제 MyBatis 구현이다(목 없음).
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisMappingRepositoryContractTest extends MappingRepositoryContractTest {

  private TiberoPlatformSchema poc;
  private MappingRepository repository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    repository =
        poc.transactional(
            MappingRepository.class,
            new MyBatisMappingRepository(
                poc.mapper(MappingMapper.class),
                new MyBatisGraphOntologySourceRepository(
                    poc.mapper(GraphOntologySourceMapper.class))));
  }

  @Override
  protected MappingRepository repository() {
    return repository;
  }

  @Override
  protected AccessFixtures newFixtures() {
    return new TiberoAccessFixtures(poc);
  }
}
