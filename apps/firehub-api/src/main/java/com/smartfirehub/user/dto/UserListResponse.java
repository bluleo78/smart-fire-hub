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
 */
public record UserListResponse(
    Long id,
    String username,
    String email,
    String name,
    boolean isActive,
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime createdAt,
    List<RoleResponse> roles) {

  /** {@link UserResponse} 한 건과 그 사용자의 역할 목록을 합쳐 목록 응답 항목을 만든다. */
  public static UserListResponse of(UserResponse user, List<RoleResponse> roles) {
    return new UserListResponse(
        user.id(),
        user.username(),
        user.email(),
        user.name(),
        user.isActive(),
        user.createdAt(),
        roles);
  }
}
