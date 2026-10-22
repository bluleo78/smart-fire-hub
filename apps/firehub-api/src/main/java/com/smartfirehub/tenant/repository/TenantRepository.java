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

  /**
   * 이 테넌트가 지금 ACTIVE 인지(WD-174). 없는 테넌트도 false 다.
   *
   * <p>예약 트리거·스마트 작업처럼 사람이 없을 때 도는 일의 발화 시점 관문에 쓴다 — 운영자가 워크스페이스를 정지해도 이미 등록된 cron 이 남아 매 주기 실행·AI
   * 호출·메일 발송이 계속되던 결함의 수정. 기동 순회({@link #findActiveTenantIds})는 정지 테넌트를 건너뛰지만, 실행 중 정지는 등록된 예약을 그대로
   * 둔다. 발화마다 한 번 읽는 비용(매분 수준)은 허용한다.
   */
  @Transactional(readOnly = true)
  public boolean isActive(long tenantId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(table(name("tenant")))
            .where(field(name("tenant", "id"), Long.class).eq(tenantId))
            .and(field(name("tenant", "status"), String.class).eq("ACTIVE")));
  }
}
