package com.smartfirehub.tenant.dto;

/**
 * 사용자가 선택할 수 있는 테넌트 한 건.
 *
 * @param role 표시용 라벨(OWNER/ADMIN/MEMBER). 인가 판단에는 쓰지 않는다
 */
public record MembershipResponse(
    Long tenantId, String tenantSlug, String tenantName, String role) {}
