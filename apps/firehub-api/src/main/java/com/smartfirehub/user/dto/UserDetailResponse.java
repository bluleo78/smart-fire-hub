package com.smartfirehub.user.dto;

import com.smartfirehub.role.dto.RoleResponse;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 사용자 상세.
 *
 * @param isActive 관리 경로(GET /users/{id})에서는 <b>현재 테넌트 멤버십</b> ACTIVE 여부, 자기 프로필 (GET /users/me)에서는
 *     전역 계정 활성 여부
 * @param membershipRole 현재 테넌트 멤버십 라벨(OWNER|ADMIN|MEMBER). 자기 프로필 경로에서는 null
 * @param lastActiveAdmin 이 사용자가 현재 테넌트의 마지막 활성 ADMIN 인가 — 웹이 정지·제거 버튼을 비활성+툴팁으로 그린다(최종 판정은 서버)
 * @param accountActive 전역 계정 활성 여부({@code user.is_active}, WD-3). 관리 경로에서는 isActive(멤버십)와 다를 수 있다 —
 *     운영자가 비활성화한 계정. 자기 프로필 경로에서는 isActive 와 같다
 */
public record UserDetailResponse(
    Long id,
    String username,
    String email,
    String name,
    boolean isActive,
    LocalDateTime createdAt,
    List<RoleResponse> roles,
    String membershipRole,
    boolean lastActiveAdmin,
    boolean accountActive) {

  /** 멤버십 정보가 필요 없는 기존 호출처(테스트 등)용 보조 생성자 — isActive 가 곧 전역 활성이다. */
  public UserDetailResponse(
      Long id,
      String username,
      String email,
      String name,
      boolean isActive,
      LocalDateTime createdAt,
      List<RoleResponse> roles) {
    this(id, username, email, name, isActive, createdAt, roles, null, false, isActive);
  }
}
