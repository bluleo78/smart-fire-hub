package com.smartfirehub.vectorstore.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.vectorstore.tenant.TenantVectorStore;
import com.smartfirehub.vectorstore.tenant.TenantVectorStoreRepository;
import java.util.List;
import java.util.Optional;

/**
 * TenantVectorStoreRepository 의 MyBatis 구현(WD-742) — {@code JooqTenantVectorStoreRepository} 와 같은
 * 의미. 정책 없는 전역 테이블이라 모든 문장이 tenant id 를 직접 건다.
 */
@PlatformMyBatisRepository
public class MyBatisTenantVectorStoreRepository implements TenantVectorStoreRepository {

  private final TenantVectorStoreMapper mapper;

  public MyBatisTenantVectorStoreRepository(TenantVectorStoreMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void insert(TenantVectorStore row) {
    mapper.insert(row.tenantId(), row.host(), row.grpcPort(), row.tls(), row.apiKeyEnc());
  }

  @Override
  public Optional<TenantVectorStore> find(long tenantId) {
    return Optional.ofNullable(mapper.select(tenantId))
        .map(
            r ->
                new TenantVectorStore(
                    r.tenantId(), r.host(), r.grpcPort(), r.useTls() != 0, r.apiKeyEnc()));
  }

  @Override
  public List<Long> findTenantIds() {
    return mapper.selectTenantIds();
  }
}
