package com.smartfirehub.user.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.smartfirehub.role.dto.RoleResponse;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 사용자 목록(GET /users) 응답 항목.
 *
 * <p>{@link UserResponse}에 {@code roles}를 더한 것 — 목록 조회에서도 역할을 함께 내려주기 위해
 * 별도 타입으로 분리했다(#586). {@code UserResponse} 자체에 역할을 추가하지 않은 이유: 그 레코드는
 * 로그인·비밀번호 확인 등 역할이 전혀 필요 없는 내부 조회 경로에서도 재사용되는데, 거기까지 역할
 * 배치 조회를 끌고 가면 불필요한 조인이 늘어난다.
 *
 * @param isActive 이 워크스페이스 멤버십 상태(관리 목록에서는 덮어쓴 값)
 * @param accountActive 전역 계정 활성 여부(운영자 비활성화, WD-3). isActive 는 이 워크스페이스 멤버십 상태
 */
public record UserListResponse(
    Long id,
    String username,
    String email,
    String name,
    boolean isActive,
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime createdAt,
    List<RoleResponse> roles,
    String membershipRole,
    boolean accountActive) {

  /** {@link UserResponse} 한 건과 그 사용자의 역할 목록을 합쳐 목록 응답 항목을 만든다(멤버십 라벨 없음). */
  public static UserListResponse of(UserResponse user, List<RoleResponse> roles) {
    return of(user, roles, null);
  }

  /**
   * 멤버십 라벨까지 담는 버전 — 관리 목록(GET /users)이 쓴다. membershipRole 은 현재 테넌트 멤버십
   * 라벨(OWNER|ADMIN|MEMBER)이라 목록에서 OWNER 를 표시할 수 있다.
   */
  public static UserListResponse of(
      UserResponse user, List<RoleResponse> roles, String membershipRole) {
    // 활성 표시를 덮어쓰지 않은 호출처라 전역 활성 = isActive 다.
    return of(user, roles, membershipRole, user.isActive());
  }

  /**
   * 관리 목록(GET /users)용. {@code shown.isActive()} 는 멤버십 상태로 덮어쓴 값이고, {@code accountActive} 는 덮어쓰기 전
   * 전역 계정 활성({@code user.is_active})이다(WD-3) — 운영자가 비활성화한 계정이 멤버십 "활성" 으로만 보이지 않게 한다.
   */
  public static UserListResponse of(
      UserResponse shown, List<RoleResponse> roles, String membershipRole, boolean accountActive) {
    return new UserListResponse(
        shown.id(),
        shown.username(),
        shown.email(),
        shown.name(),
        shown.isActive(),
        shown.createdAt(),
        roles,
        membershipRole,
        accountActive);
  }
}
