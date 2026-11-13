package com.smartfirehub.notification.repository;

import com.smartfirehub.global.platformdb.tibero.TiberoPlatformDbErrors;
import com.smartfirehub.global.platformdb.tibero.TiberoPlatformTenantSession;
import com.smartfirehub.notification.repository.mybatis.MyBatisSlackWorkspaceRepository;
import com.smartfirehub.notification.repository.mybatis.MyBatisSlackWorkspaceTenantResolver;
import com.smartfirehub.notification.repository.mybatis.SlackWorkspaceMapper;
import com.smartfirehub.notification.repository.mybatis.SlackWorkspaceTenantResolverMapper;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.AccessFixtures;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

/**
 * SlackWorkspaceTenantResolverContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-709, 스펙 D2). 해석기는
 * {@code @Transactional} 이 없어 프록시로 감싸지 않는다 — 하네스와 <b>같은</b> 트랜잭션 매니저·런타임 풀로 만든 테넌트 세션을 넘겨야 재조회의
 * REQUIRES_NEW 가 호출자 트랜잭션을 실제로 보류한다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisSlackWorkspaceTenantResolverContractTest
    extends SlackWorkspaceTenantResolverContractTest {

  private TiberoPlatformSchema poc;
  private SlackWorkspaceTenantResolver resolver;
  private SlackWorkspaceRepository workspaceRepository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    resolver =
        new MyBatisSlackWorkspaceTenantResolver(
            poc.mapper(SlackWorkspaceTenantResolverMapper.class),
            new TiberoPlatformTenantSession(poc.dataSource()),
            poc.tx().getTransactionManager());
    workspaceRepository =
        poc.transactional(
            SlackWorkspaceRepository.class,
            new MyBatisSlackWorkspaceRepository(
                poc.mapper(SlackWorkspaceMapper.class), new TiberoPlatformDbErrors()));
  }

  @Override
  protected SlackWorkspaceTenantResolver resolver() {
    return resolver;
  }

  @Override
  protected SlackWorkspaceRepository workspaceRepository() {
    return workspaceRepository;
  }

  @Override
  protected AccessFixtures newFixtures() {
    return new TiberoAccessFixtures(poc);
  }
}
