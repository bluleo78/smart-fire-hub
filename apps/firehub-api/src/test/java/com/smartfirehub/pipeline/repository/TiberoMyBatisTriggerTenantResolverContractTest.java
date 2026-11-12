package com.smartfirehub.pipeline.repository;

import com.smartfirehub.global.platformdb.tibero.TiberoPlatformTenantSession;
import com.smartfirehub.pipeline.repository.mybatis.MyBatisTriggerTenantResolver;
import com.smartfirehub.pipeline.repository.mybatis.TriggerTenantResolverMapper;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.AccessFixtures;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

/**
 * TriggerTenantResolverContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-708, 스펙 D2). 해석기는
 * {@code @Transactional} 이 없어 트랜잭션 프록시로 감싸지 않는다 — 운영처럼 하네스와 <b>같은</b> 트랜잭션 매니저·런타임 풀로 만든 테넌트 세션을
 * 넘겨야 재조회의 REQUIRES_NEW 가 호출자 트랜잭션을 실제로 보류하고, {@code applyToCurrentTransaction} 의 풀 묶임 검사를 통과한다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisTriggerTenantResolverContractTest extends TriggerTenantResolverContractTest {

  private TiberoPlatformSchema poc;
  private TriggerTenantResolver resolver;
  private TriggerRepository triggerRepository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    resolver =
        new MyBatisTriggerTenantResolver(
            poc.mapper(TriggerTenantResolverMapper.class),
            new TiberoPlatformTenantSession(poc.dataSource()),
            poc.tx().getTransactionManager());
    triggerRepository = TiberoMyBatisTriggerRepositoryContractTest.tiberoTriggerRepository(poc);
  }

  @Override
  protected TriggerTenantResolver resolver() {
    return resolver;
  }

  @Override
  protected TriggerRepository triggerRepository() {
    return triggerRepository;
  }

  @Override
  protected AccessFixtures newFixtures() {
    return new TiberoAccessFixtures(poc);
  }
}
