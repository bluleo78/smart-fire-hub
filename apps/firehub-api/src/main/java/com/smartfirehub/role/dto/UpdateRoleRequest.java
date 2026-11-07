package com.smartfirehub.role.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * 역할 이름·설명 수정 요청.
 *
 * <p>이름 규칙은 {@link CreateRoleRequest} 와 같은 {@link RoleNameRules} 다(WD-279) — 수정에서만 규칙이 빠지면 「ADMIN
 * 」(끝 공백)처럼 시스템 역할과 똑같이 보이는 이름이나 한글·공백 이름이 저장돼, 목록·역할 할당에서 진짜 ADMIN 과 구분할 수 없게 된다.
 *
 * @param expectedUpdatedAt 편집을 시작할 때 읽은 역할의 {@code updatedAt}(WD-575 낙관적 잠금). 지금 저장된 값과 다르면 409
 *     EDIT_CONFLICT 로 거부한다 — 이름·설명은 통째로 바꾸므로, 없으면 옛 화면의 저장이 다른 관리자의 이름 변경을 조용히 되돌린다. null 이면 비교하지
 *     않는다(기준을 보내지 않는 기존 호출부, 「내 변경으로 덮어쓰기」).
 */
public record UpdateRoleRequest(
    @NotBlank
        @Size(max = RoleNameRules.MAX_LENGTH, message = RoleNameRules.SIZE_MESSAGE)
        @Pattern(regexp = RoleNameRules.PATTERN, message = RoleNameRules.PATTERN_MESSAGE)
        String name,
    String description,
    LocalDateTime expectedUpdatedAt) {

  /** 기준 버전 없이 만드는 기존 호출부용 — 비교하지 않는다. */
  public UpdateRoleRequest(String name, String description) {
    this(name, description, null);
  }
}
