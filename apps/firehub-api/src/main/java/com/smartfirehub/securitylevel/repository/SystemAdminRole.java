package com.smartfirehub.securitylevel.repository;

import static com.smartfirehub.jooq.Tables.ROLE;

import org.jooq.Condition;

/**
 * "시스템 ADMIN 역할" 술어(이름 'ADMIN' 이고 is_system). 자격 계산·최상위 동기화·순서 미리보기·역할 자격 고정이 같은 정의를 쓰도록 한 곳에 둔다 —
 * 보안 판정에 쓰이는 규칙이 복사본마다 어긋나지 않게.
 */
public final class SystemAdminRole {

  private SystemAdminRole() {}

  /** SQL 조건 — ROLE 테이블 행이 시스템 ADMIN 인가. */
  public static final Condition CONDITION = ROLE.NAME.eq("ADMIN").and(ROLE.IS_SYSTEM.isTrue());

  /** 이미 읽은 역할 행(이름·is_system)이 시스템 ADMIN 인가. */
  public static boolean matches(String name, Boolean isSystem) {
    return "ADMIN".equals(name) && Boolean.TRUE.equals(isSystem);
  }
}
