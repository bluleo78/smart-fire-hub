package com.smartfirehub.dataset.repository;

import com.smartfirehub.dataset.repository.mybatis.DatasetFavoriteMapper;
import com.smartfirehub.dataset.repository.mybatis.DatasetMapper;
import com.smartfirehub.dataset.repository.mybatis.MyBatisDatasetRepository;
import com.smartfirehub.global.platformdb.PlatformLocks;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.AccessFixtures;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;

/**
 * DatasetRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-707). 리포지토리는 런타임 계정 + DBMS_RLS
 * 위(하네스 프록시가 호출마다 테넌트 트랜잭션을 연다 — MANDATORY 도 앱과 같게 거른다), 픽스처는 정책을 우회하는 소유자 계정으로 만들고 지운다. 잠금 창구는 하네스
 * 런타임 계정에 DBMS_LOCK 권한이 없어 가짜를 쓴다(키는 MyBatisDatasetRepositoryLockKeyTest 가 고정).
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisDatasetRepositoryContractTest extends DatasetRepositoryContractTest {

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
                Mockito.mock(PlatformLocks.class)));
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
