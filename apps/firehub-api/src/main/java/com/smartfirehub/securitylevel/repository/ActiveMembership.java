package com.smartfirehub.securitylevel.repository;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Table;

/**
 * 전역 membership 테이블(RLS 없음 — tenant_id 를 명시해야 한다)의 "이 테넌트 ACTIVE 멤버" 조건. 자격 원자료·허용 목록 후보·허용 항목 추가
 * 검증이 같은 기준을 쓰도록 한 곳에 둔다. 계정 활성(user.is_active) 조건은 호출자마다 조인 형태가 달라 각자 붙인다.
 */
public final class ActiveMembership {

  private ActiveMembership() {}

  /** membership 테이블(jOOQ 생성 코드 밖 — 전역 테이블). */
  public static final Table<?> TABLE = table(name("membership"));

  private static final Field<Long> USER_ID = field(name("membership", "user_id"), Long.class);
  private static final Field<Long> TENANT_ID = field(name("membership", "tenant_id"), Long.class);
  private static final Field<String> STATUS = field(name("membership", "status"), String.class);

  /** membership.user_id = userId AND membership.tenant_id = tenantId AND status = 'ACTIVE'. */
  public static Condition of(Field<Long> userId, Field<Long> tenantId) {
    return USER_ID.eq(userId).and(TENANT_ID.eq(tenantId)).and(STATUS.eq("ACTIVE"));
  }
}
