package com.smartfirehub.document.repository;

import com.smartfirehub.document.repository.mybatis.DocumentChunkMapper;
import com.smartfirehub.document.repository.mybatis.MyBatisDocumentChunkRepository;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.AccessFixtures;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

/**
 * DocumentChunkRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-712). 리포지토리는 런타임 계정 +
 * DBMS_RLS 위(하네스 프록시가 호출마다 테넌트 트랜잭션을 연다), 픽스처는 정책을 우회하는 소유자 계정으로 만들고 지운다. Tibero 기준선에는 벡터 테이블·트라이그램
 * 색인이 없어 꺼진 기능 메서드는 예외를 단언한다(스펙 D1).
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisDocumentChunkRepositoryContractTest extends DocumentChunkRepositoryContractTest {

  private TiberoPlatformSchema poc;
  private DocumentChunkRepository repository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    repository =
        poc.transactional(
            DocumentChunkRepository.class,
            new MyBatisDocumentChunkRepository(poc.mapper(DocumentChunkMapper.class)));
  }

  @Override
  protected DocumentChunkRepository repository() {
    return repository;
  }

  @Override
  protected boolean supportsVectorSearch() {
    return false;
  }

  @Override
  protected boolean supportsTrigramSearch() {
    return false;
  }

  @Override
  protected AccessFixtures newFixtures() {
    return new TiberoAccessFixtures(poc);
  }
}
