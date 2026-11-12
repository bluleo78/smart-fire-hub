package com.smartfirehub.dataset.repository;

import com.smartfirehub.dataset.repository.mybatis.DatasetFavoriteMapper;
import com.smartfirehub.dataset.repository.mybatis.DatasetMapper;
import com.smartfirehub.dataset.repository.mybatis.MyBatisDatasetRepository;
import com.smartfirehub.global.platformdb.PlatformLocks;
import com.smartfirehub.securitylevel.repository.mybatis.MyBatisSecurityLevelRepository;
import com.smartfirehub.securitylevel.repository.mybatis.SecurityLevelMapper;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.AccessFixtures;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;

/**
 * DatasetRepositoryPlatformMetaContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-707) — id 목록 1001 개
 * 시나리오가 Tibero IN 1000 개 상한을 실제로 넘는다(Review Focus 3).
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisDatasetRepositoryPlatformMetaContractTest
    extends DatasetRepositoryPlatformMetaContractTest {

  private TiberoPlatformSchema poc;
  private DatasetRepository repository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    repository =
        poc.transactional(
            DatasetRepository.class,
            new MyBatisDatasetRepository(
                poc.mapper(DatasetMapper.class),
                poc.mapper(DatasetFavoriteMapper.class),
                Mockito.mock(PlatformLocks.class),
                new MyBatisSecurityLevelRepository(
                    poc.mapper(SecurityLevelMapper.class), Mockito.mock(PlatformLocks.class))));
  }

  @Override
  protected DatasetRepository repository() {
    return repository;
  }

  @Override
  protected AccessFixtures newFixtures() {
    return new TiberoAccessFixtures(poc);
  }
}
