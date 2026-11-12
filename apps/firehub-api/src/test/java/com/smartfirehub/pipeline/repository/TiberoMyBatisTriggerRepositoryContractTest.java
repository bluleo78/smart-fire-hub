package com.smartfirehub.pipeline.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.platformdb.PlatformLocks;
import com.smartfirehub.pipeline.repository.mybatis.MyBatisTriggerRepository;
import com.smartfirehub.pipeline.repository.mybatis.TriggerMapper;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.AccessFixtures;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;

/**
 * TriggerRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-708). 리포지토리는 런타임 계정 + DBMS_RLS
 * 위(하네스 프록시가 구현 클래스의 @Transactional 속성대로 테넌트 트랜잭션을 연다), 픽스처는 정책을 우회하는 소유자 계정으로 만들고 지운다. 잠금 창구는 하네스
 * 런타임 계정에 DBMS_LOCK 권한이 없어 가짜를 쓴다(키는 MyBatisTriggerRepositoryUnitTest 가 고정).
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisTriggerRepositoryContractTest extends TriggerRepositoryContractTest {

  private TiberoPlatformSchema poc;
  private TriggerRepository repository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    repository = tiberoTriggerRepository(poc);
  }

  /** Tibero 하네스 위 MyBatis 트리거 리포지토리(트랜잭션 프록시 포함) — 폴 스냅숏·해석기 계약 시험도 같이 쓴다. */
  static TriggerRepository tiberoTriggerRepository(TiberoPlatformSchema poc) {
    return poc.transactional(
        TriggerRepository.class,
        new MyBatisTriggerRepository(
            poc.mapper(TriggerMapper.class),
            new ObjectMapper(),
            Mockito.mock(PlatformLocks.class)));
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
