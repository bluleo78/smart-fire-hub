package com.smartfirehub.dataset.rowsearch;

import com.smartfirehub.dataset.repository.SearchIndexStateRepository;
import com.smartfirehub.dataset.repository.mybatis.MyBatisSearchIndexStateRepository;
import com.smartfirehub.dataset.repository.mybatis.SearchIndexStateMapper;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.AccessFixtures;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

/**
 * SearchIndexStateRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-707). 리포지토리는 런타임 계정 +
 * DBMS_RLS 위(하네스 프록시가 호출마다 테넌트 트랜잭션을 연다), 픽스처는 정책을 우회하는 소유자 계정으로 만들고 지운다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisSearchIndexStateRepositoryContractTest
    extends SearchIndexStateRepositoryContractTest {

  private TiberoPlatformSchema poc;
  private SearchIndexStateRepository repository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    repository =
        poc.transactional(
            SearchIndexStateRepository.class,
            new MyBatisSearchIndexStateRepository(poc.mapper(SearchIndexStateMapper.class)));
  }

  @Override
  protected SearchIndexStateRepository repository() {
    return repository;
  }

  @Override
  protected AccessFixtures newFixtures() {
    return new TiberoAccessFixtures(poc);
  }
}
