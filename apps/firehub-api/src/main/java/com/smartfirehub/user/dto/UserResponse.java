package com.smartfirehub.user.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;

/**
 * 사용자 기본 정보.
 *
 * @param mustChangePassword 관리자가 임시 비밀번호로 만든 계정이 아직 비밀번호를 바꾸지 않았으면 true ({@code /auth/me} 로 웹에
 *     전달되고, 토큰 발급 시 클레임 {@code pwc} 의 원천이 된다)
 */
public record UserResponse(
    Long id,
    String username,
    String email,
    String name,
    boolean isActive,
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime createdAt,
    boolean mustChangePassword) {

  /** 표식이 필요 없는 기존 호출처(테스트 6곳 등)용 보조 생성자 — 표식은 false. */
  public UserResponse(
      Long id,
      String username,
      String email,
      String name,
      boolean isActive,
      LocalDateTime createdAt) {
    this(id, username, email, name, isActive, createdAt, false);
  }

  /** 같은 사용자를 활성 표시만 바꿔 복제한다(관리 목록에서 멤버십 상태로 덮어쓸 때). */
  public UserResponse withActive(boolean active) {
    return new UserResponse(id, username, email, name, active, createdAt, mustChangePassword);
  }
}
