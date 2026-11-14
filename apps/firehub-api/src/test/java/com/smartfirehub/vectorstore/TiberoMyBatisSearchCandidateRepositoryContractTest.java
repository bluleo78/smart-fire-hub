package com.smartfirehub.vectorstore;

import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.AccessFixtures;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import com.smartfirehub.vectorstore.repository.mybatis.MyBatisSearchCandidateRepository;
import com.smartfirehub.vectorstore.repository.mybatis.SearchCandidateMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

/**
 * SearchCandidateRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-720). 리포지토리는 런타임 계정 +
 * DBMS_RLS 위, 픽스처는 소유자 계정.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisSearchCandidateRepositoryContractTest
    extends SearchCandidateRepositoryContractTest {

  private TiberoPlatformSchema poc;
  private SearchCandidateRepository repository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    repository =
        poc.transactional(
            SearchCandidateRepository.class,
            new MyBatisSearchCandidateRepository(poc.mapper(SearchCandidateMapper.class)));
  }

  @Override
  protected SearchCandidateRepository repository() {
    return repository;
  }

  @Override
  protected AccessFixtures newFixtures() {
    return new TiberoAccessFixtures(poc);
  }
}
