package com.smartfirehub.tenant.repository;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import java.util.List;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 전역 `tenant` 테이블 조회. 이 테이블은 테넌트 경계 위에 있어 tenant_id·RLS 가 없다(V81). */
@Repository
public class TenantRepository {

  private final DSLContext dsl;

  public TenantRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /** ACTIVE 테넌트 id 를 오름차순으로 반환한다. 배경 작업의 테넌트 순회에 쓴다. */
  @Transactional(readOnly = true)
  public List<Long> findActiveTenantIds() {
    return dsl.select(field(name("tenant", "id"), Long.class))
        .from(table(name("tenant")))
        .where(field(name("tenant", "status"), String.class).eq("ACTIVE"))
        .orderBy(field(name("tenant", "id")))
        .fetchInto(Long.class);
  }
}
