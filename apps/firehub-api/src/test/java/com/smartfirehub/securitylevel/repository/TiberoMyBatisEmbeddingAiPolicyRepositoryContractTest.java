package com.smartfirehub.securitylevel.repository;

import com.smartfirehub.securitylevel.repository.mybatis.EmbeddingAiPolicyMapper;
import com.smartfirehub.securitylevel.repository.mybatis.MyBatisEmbeddingAiPolicyRepository;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.AccessFixtures;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

/**
 * EmbeddingAiPolicyRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-711). 리포지토리는 런타임 계정 +
 * DBMS_RLS 위(하네스 프록시가 호출마다 테넌트 트랜잭션을 연다), 픽스처는 정책을 우회하는 소유자 계정으로 만들고 지운다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisEmbeddingAiPolicyRepositoryContractTest
    extends EmbeddingAiPolicyRepositoryContractTest {

  private TiberoPlatformSchema poc;
  private EmbeddingAiPolicyRepository repository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    repository =
        poc.transactional(
            EmbeddingAiPolicyRepository.class,
            new MyBatisEmbeddingAiPolicyRepository(poc.mapper(EmbeddingAiPolicyMapper.class)));
  }

  @Override
  protected EmbeddingAiPolicyRepository repository() {
    return repository;
  }

  @Override
  protected AccessFixtures newFixtures() {
    return new TiberoAccessFixtures(poc);
  }

  /** 문자열 술어 렌더는 MyBatis 구현에 없다 — VECTOR_SEARCH 꺼진 기능(스펙 D3). */
  @Override
  protected boolean supportsRenderedSql() {
    return false;
  }
}
