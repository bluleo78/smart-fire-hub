package com.smartfirehub.settings.repository;

import com.smartfirehub.settings.repository.mybatis.MyBatisTenantSettingsRepository;
import com.smartfirehub.settings.repository.mybatis.TenantSettingsMapper;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.AccessFixtures;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

/**
 * TenantSettingsRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-706). 리포지토리는 런타임 계정 +
 * DBMS_RLS 위(구현의 트랜잭션 애너테이션을 하네스 프록시가 그대로 읽는다), 픽스처는 정책을 우회하는 소유자 계정으로 만들고 지운다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisTenantSettingsRepositoryContractTest
    extends TenantSettingsRepositoryContractTest {

  private TiberoPlatformSchema poc;
  private TenantSettingsRepository repository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    repository =
        poc.transactional(
            TenantSettingsRepository.class,
            new MyBatisTenantSettingsRepository(poc.mapper(TenantSettingsMapper.class)));
  }

  @Override
  protected TenantSettingsRepository repository() {
    return repository;
  }

  @Override
  protected AccessFixtures newFixtures() {
    return new TiberoAccessFixtures(poc);
  }
}
