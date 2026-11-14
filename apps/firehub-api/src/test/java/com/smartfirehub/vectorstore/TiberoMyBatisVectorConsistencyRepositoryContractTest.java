package com.smartfirehub.vectorstore;

import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.AccessFixtures;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import com.smartfirehub.vectorstore.repository.mybatis.MyBatisVectorMarkerRepository;
import com.smartfirehub.vectorstore.repository.mybatis.MyBatisVectorOutboxRepository;
import com.smartfirehub.vectorstore.repository.mybatis.VectorMarkerMapper;
import com.smartfirehub.vectorstore.repository.mybatis.VectorOutboxMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

/**
 * VectorConsistencyRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-721). 리포지토리는 런타임 계정 +
 * DBMS_RLS 위, 픽스처는 소유자 계정. 시퀀스 문법(NEXTVAL FROM DUAL)과 V162 정책·DEFAULT 를 실서버에서 확인한다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisVectorConsistencyRepositoryContractTest
    extends VectorConsistencyRepositoryContractTest {

  private TiberoPlatformSchema poc;
  private VectorOutboxRepository outbox;
  private VectorMarkerRepository markers;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    outbox =
        poc.transactional(
            VectorOutboxRepository.class,
            new MyBatisVectorOutboxRepository(poc.mapper(VectorOutboxMapper.class)));
    markers =
        poc.transactional(
            VectorMarkerRepository.class,
            new MyBatisVectorMarkerRepository(poc.mapper(VectorMarkerMapper.class)));
  }

  @Override
  protected VectorOutboxRepository outbox() {
    return outbox;
  }

  @Override
  protected VectorMarkerRepository markers() {
    return markers;
  }

  @Override
  protected AccessFixtures newFixtures() {
    return new TiberoAccessFixtures(poc);
  }
}
