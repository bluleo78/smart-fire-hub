package com.smartfirehub.vectorstore.repository.jooq;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.global.platformdb.ConditionalOnPostgresPlatformDb;
import com.smartfirehub.vectorstore.tenant.TenantVectorStore;
import com.smartfirehub.vectorstore.tenant.TenantVectorStoreRepository;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

/**
 * TenantVectorStoreRepository 의 jOOQ(PostgreSQL) 구현(WD-742). 코드젠 대상이 아닌 전역 테이블이라 {@code
 * JooqTenantDataStoreRepository} 처럼 이름 기반 jOOQ 를 쓴다.
 */
@Repository
@ConditionalOnPostgresPlatformDb
public class JooqTenantVectorStoreRepository implements TenantVectorStoreRepository {

  // public 스키마를 명시한다: 테넌트 search_path 의 같은 이름 테이블에 가려지지 않게.
  private static final Table<Record> STORE = table(name("public", "tenant_vector_store"));
  private static final Field<Long> TENANT_ID = field(name("tenant_id"), Long.class);
  private static final Field<String> HOST = field(name("host"), String.class);
  private static final Field<Integer> GRPC_PORT = field(name("grpc_port"), Integer.class);
  private static final Field<Boolean> USE_TLS = field(name("use_tls"), Boolean.class);
  private static final Field<String> API_KEY_ENC = field(name("api_key_enc"), String.class);

  private final DSLContext dsl;

  public JooqTenantVectorStoreRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public void insert(TenantVectorStore row) {
    dsl.insertInto(STORE)
        .set(TENANT_ID, row.tenantId())
        .set(HOST, row.host())
        .set(GRPC_PORT, row.grpcPort())
        .set(USE_TLS, row.tls())
        .set(API_KEY_ENC, row.apiKeyEnc())
        .execute();
  }

  @Override
  public Optional<TenantVectorStore> find(long tenantId) {
    return dsl.select(TENANT_ID, HOST, GRPC_PORT, USE_TLS, API_KEY_ENC)
        .from(STORE)
        .where(TENANT_ID.eq(tenantId))
        .fetchOptional(
            r ->
                new TenantVectorStore(
                    r.get(TENANT_ID),
                    r.get(HOST),
                    r.get(GRPC_PORT),
                    r.get(USE_TLS),
                    r.get(API_KEY_ENC)));
  }

  @Override
  public List<Long> findTenantIds() {
    return dsl.select(TENANT_ID).from(STORE).orderBy(TENANT_ID).fetch(TENANT_ID);
  }
}
