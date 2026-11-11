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
 * DocumentChunkReembedRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-712). 리포지토리는 런타임 계정
 * + DBMS_RLS 위, 픽스처는 소유자 계정. 1000개 넘는 id 목록(Review Focus 3)이 Tibero 에서 반드시 도는 곳이다. 벡터 메서드는 예외를
 * 단언한다(스펙 D1).
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisDocumentChunkReembedRepositoryContractTest
    extends DocumentChunkReembedRepositoryContractTest {

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
  protected AccessFixtures newFixtures() {
    return new TiberoAccessFixtures(poc);
  }
}
