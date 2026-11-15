package com.smartfirehub.vectorstore.tenant;

import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.AccessFixtures;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import com.smartfirehub.vectorstore.repository.mybatis.MyBatisTenantVectorStoreRepository;
import com.smartfirehub.vectorstore.repository.mybatis.TenantVectorStoreMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

/**
 * TenantVectorStoreRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-742).
 * tenant_vector_store 는 정책이 없어 명시 tenant_id 조건이 유일한 차단이다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisTenantVectorStoreRepositoryContractTest
    extends TenantVectorStoreRepositoryContractTest {

  private TiberoPlatformSchema poc;
  private TenantVectorStoreRepository repository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    repository =
        poc.transactional(
            TenantVectorStoreRepository.class,
            new MyBatisTenantVectorStoreRepository(poc.mapper(TenantVectorStoreMapper.class)));
  }

  @Override
  protected TenantVectorStoreRepository repository() {
    return repository;
  }

  @Override
  protected AccessFixtures newFixtures() {
    return new TiberoAccessFixtures(poc);
  }
}
