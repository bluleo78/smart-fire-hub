package com.smartfirehub.tenant.repository.jooq;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.global.platformdb.ConditionalOnPostgresPlatformDb;
import com.smartfirehub.tenant.repository.TenantRepository;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * TenantRepository 의 jOOQ(PostgreSQL) 구현 — WD-485.
 *
 * <p>전역 `tenant` 테이블 조회. 이 테이블은 테넌트 경계 위에 있어 tenant_id·RLS 가 없다(V81).
 */
@Repository
@ConditionalOnPostgresPlatformDb
public class JooqTenantRepository implements TenantRepository {

  private final DSLContext dsl;

  public JooqTenantRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  @Transactional(readOnly = true)
  public List<Long> findActiveTenantIds() {
    return dsl.select(field(name("tenant", "id"), Long.class))
        .from(table(name("tenant")))
        .where(field(name("tenant", "status"), String.class).eq("ACTIVE"))
        .orderBy(field(name("tenant", "id")))
        .fetchInto(Long.class);
  }

  @Override
  @Transactional(readOnly = true)
  public boolean isActive(long tenantId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(table(name("tenant")))
            .where(field(name("tenant", "id"), Long.class).eq(tenantId))
            .and(field(name("tenant", "status"), String.class).eq("ACTIVE")));
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void provisionDefaults(long tenantId) {
    dsl.execute("select provision_tenant_defaults(?)", tenantId);
  }
}
