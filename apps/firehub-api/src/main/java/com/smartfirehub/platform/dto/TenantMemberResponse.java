package com.smartfirehub.platform.dto;

/**
 * 테넌트 멤버 한 줄. 운영자가 "누가 이 워크스페이스에 있는가"를 확인하는 용도다.
 *
 * <p>{@code role} 은 {@code membership.role}(OWNER/ADMIN/MEMBER)로 <b>표시용</b>이다 — 실제 권한은 테넌트 평면의
 * {@code role}/{@code role_permission} 이 결정하며, 운영자 평면은 그것을 읽지 않는다.
 *
 * <p>{@code status} 는 이 워크스페이스의 멤버십 상태이고, {@code accountActive} 는 운영자가 정하는 전역 계정 활성 ({@code
 * user.is_active})이다(WD-313). 둘을 따로 실어야 운영자가 비활성 계정을 「활성」 멤버로 오해하지 않는다.
 */
public record TenantMemberResponse(
    Long userId,
    String username,
    String email,
    String role,
    String status,
    boolean accountActive) {}
