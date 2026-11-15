package com.smartfirehub.vectorstore.repository.jooq;

import static com.smartfirehub.jooq.Tables.TENANT_VECTOR_STORE;

import com.smartfirehub.global.platformdb.ConditionalOnPostgresPlatformDb;
import com.smartfirehub.vectorstore.tenant.TenantVectorStore;
import com.smartfirehub.vectorstore.tenant.TenantVectorStoreRepository;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/**
 * TenantVectorStoreRepository 의 jOOQ(PostgreSQL) 구현(WD-742). 전역 테이블이라 RLS 없이 코드젠 클래스를 그대로 쓴다. 코드젠
 * 테이블은 스키마({@code public})를 붙여 SQL 을 만들므로 테넌트 search_path 의 같은 이름 테이블에 가려지지 않는다.
 */
@Repository
@ConditionalOnPostgresPlatformDb
public class JooqTenantVectorStoreRepository implements TenantVectorStoreRepository {

  private final DSLContext dsl;

  public JooqTenantVectorStoreRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public void insert(TenantVectorStore row) {
    dsl.insertInto(TENANT_VECTOR_STORE)
        .set(TENANT_VECTOR_STORE.TENANT_ID, row.tenantId())
        .set(TENANT_VECTOR_STORE.HOST, row.host())
        .set(TENANT_VECTOR_STORE.GRPC_PORT, row.grpcPort())
        .set(TENANT_VECTOR_STORE.USE_TLS, row.tls())
        .set(TENANT_VECTOR_STORE.API_KEY_ENC, row.apiKeyEnc())
        .execute();
  }

  @Override
  public Optional<TenantVectorStore> find(long tenantId) {
    return dsl.select(
            TENANT_VECTOR_STORE.TENANT_ID,
            TENANT_VECTOR_STORE.HOST,
            TENANT_VECTOR_STORE.GRPC_PORT,
            TENANT_VECTOR_STORE.USE_TLS,
            TENANT_VECTOR_STORE.API_KEY_ENC)
        .from(TENANT_VECTOR_STORE)
        .where(TENANT_VECTOR_STORE.TENANT_ID.eq(tenantId))
        .fetchOptional(
            r -> new TenantVectorStore(r.value1(), r.value2(), r.value3(), r.value4(), r.value5()));
  }

  @Override
  public List<Long> findTenantIds() {
    return dsl.select(TENANT_VECTOR_STORE.TENANT_ID)
        .from(TENANT_VECTOR_STORE)
        .orderBy(TENANT_VECTOR_STORE.TENANT_ID)
        .fetch(TENANT_VECTOR_STORE.TENANT_ID);
  }
}
