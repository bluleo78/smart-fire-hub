package com.smartfirehub.pipeline.repository;

import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.AccessFixtures;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

/**
 * TriggerRepositoryPollSnapshotContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-708) — 감시 목록 원소
 * 제거·스냅숏 정리를 잠근 뒤 자바에서 고쳐 쓰는 경로(스펙 D5)가 jOOQ 한 문장과 같은 결과인지 실서버 RLS 아래에서 본다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisTriggerRepositoryPollSnapshotContractTest
    extends TriggerRepositoryPollSnapshotContractTest {

  private TiberoPlatformSchema poc;
  private TriggerRepository repository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    repository = TiberoMyBatisTriggerRepositoryContractTest.tiberoTriggerRepository(poc);
  }

  @Override
  protected TriggerRepository repository() {
    return repository;
  }

  @Override
  protected AccessFixtures newFixtures() {
    return new TiberoAccessFixtures(poc);
  }
}
