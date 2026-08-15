package com.smartfirehub.tenant.dto;

import java.util.List;
import java.util.Optional;

/**
 * 사용자가 선택할 수 있는 테넌트 한 건.
 *
 * @param role 표시용 라벨(OWNER/ADMIN/MEMBER). 인가 판단에는 쓰지 않는다
 */
public record MembershipResponse(
    Long tenantId, String tenantSlug, String tenantName, String role) {

  /**
   * ACTIVE 멤버십 목록에서 실행 테넌트를 해석하는 공통 규칙.
   *
   * <p>멤버십이 정확히 하나일 때만 그 테넌트가 모호하지 않다. 0개(멤버십 없음)이거나 2개 이상(어느
   * 워크스페이스인지 판별 불가)이면 빈 값을 돌려준다 — 모호할 때 어떻게 반응할지(로그 레벨, 기본값
   * 사용 여부, 실행 계속/중단)는 호출부마다 다른 정책이라 이 메서드가 정하지 않는다.
   */
  public static Optional<Long> soleActiveTenant(List<MembershipResponse> memberships) {
    return memberships.size() == 1 ? Optional.of(memberships.get(0).tenantId()) : Optional.empty();
  }
}
