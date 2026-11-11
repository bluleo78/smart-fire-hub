package com.smartfirehub.dataset.repository;

import com.smartfirehub.dataset.repository.mybatis.MyBatisTenantDataStoreRepository;
import com.smartfirehub.dataset.repository.mybatis.TenantDataStoreMapper;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.AccessFixtures;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

/**
 * TenantDataStoreRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-707). 리포지토리는 런타임 계정
 * 위(하네스 프록시가 호출마다 트랜잭션을 연다), 픽스처는 소유자 계정으로 만들고 지운다. tenant_data_store* 는 정책이 없어 명시 tenant_id 조건이
 * 유일한 차단이다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisTenantDataStoreRepositoryContractTest
    extends TenantDataStoreRepositoryContractTest {

  private TiberoPlatformSchema poc;
  private TenantDataStoreRepository repository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    repository =
        poc.transactional(
            TenantDataStoreRepository.class,
            new MyBatisTenantDataStoreRepository(poc.mapper(TenantDataStoreMapper.class)));
  }

  @Override
  protected TenantDataStoreRepository repository() {
    return repository;
  }

  @Override
  protected AccessFixtures newFixtures() {
    return new TiberoAccessFixtures(poc);
  }
}
