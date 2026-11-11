package com.smartfirehub.settings.repository;

import com.smartfirehub.settings.repository.mybatis.MyBatisSettingsRepository;
import com.smartfirehub.settings.repository.mybatis.SettingsMapper;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.AccessFixtures;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

/**
 * SettingsRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-706). 리포지토리는 런타임 계정 + DBMS_RLS
 * 위(구현의 트랜잭션 애너테이션을 하네스 프록시가 그대로 읽는다), 픽스처는 정책을 우회하는 소유자 계정으로 만들고 지운다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisSettingsRepositoryContractTest extends SettingsRepositoryContractTest {

  private TiberoPlatformSchema poc;
  private SettingsRepository repository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    repository =
        poc.transactional(
            SettingsRepository.class,
            new MyBatisSettingsRepository(poc.mapper(SettingsMapper.class)));
  }

  @Override
  protected SettingsRepository repository() {
    return repository;
  }

  @Override
  protected AccessFixtures newFixtures() {
    return new TiberoAccessFixtures(poc);
  }
}
