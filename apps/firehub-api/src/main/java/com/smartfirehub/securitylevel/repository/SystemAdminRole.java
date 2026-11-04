package com.smartfirehub.securitylevel.repository;

/**
 * "시스템 ADMIN 역할" 판정 규칙(이름 'ADMIN' 이고 is_system). 자격 계산·최상위 동기화·순서 미리보기·역할 자격 고정이 같은 정의를 쓰도록 한 곳에 둔다
 * — 보안 판정에 쓰이는 규칙이 복사본마다 어긋나지 않게.
 *
 * <p>jOOQ 에 의존하지 않는다(WD-485) — 서비스({@code RoleClearanceService})도 {@link #matches} 를 쓰므로 리포지토리
 * 인터페이스 패키지에 두고, SQL 조건은 jOOQ 구현({@code JooqSecurityLevelRepository})이 {@link #NAME} 으로 만든다.
 */
public final class SystemAdminRole {

  private SystemAdminRole() {}

  /** 시스템 ADMIN 역할의 이름. jOOQ 구현의 SQL 조건도 이 값으로 만든다. */
  public static final String NAME = "ADMIN";

  /** 이미 읽은 역할 행(이름·is_system)이 시스템 ADMIN 인가. */
  public static boolean matches(String name, Boolean isSystem) {
    return NAME.equals(name) && Boolean.TRUE.equals(isSystem);
  }
}
