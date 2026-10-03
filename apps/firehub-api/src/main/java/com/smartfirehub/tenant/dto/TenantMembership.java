package com.smartfirehub.tenant.dto;

/**
 * 한 테넌트 안에서의 멤버십 한 행(상태 무관).
 *
 * <p>{@link MembershipResponse} 는 "로그인 가능한 ACTIVE 테넌트 목록" 용이라 tenant 이름·slug 를 담고 정지 행을 거른다. 관리 화면은
 * 반대로 정지 행까지 봐야 하므로 별도 타입을 둔다.
 *
 * @param role 표시용 라벨 OWNER|ADMIN|MEMBER — 인가에 쓰지 않는다(OWNER 보호 규칙만 예외)
 * @param status ACTIVE|SUSPENDED
 */
public record TenantMembership(Long userId, String role, String status) {

  public boolean isActive() {
    return "ACTIVE".equals(status);
  }

  public boolean isOwner() {
    return "OWNER".equals(role);
  }
}
