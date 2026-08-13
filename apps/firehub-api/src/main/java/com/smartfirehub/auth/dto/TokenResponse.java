package com.smartfirehub.auth.dto;

import com.smartfirehub.tenant.dto.MembershipResponse;
import java.util.List;

/**
 * 토큰 응답.
 *
 * @param activeTenantId 이 토큰에 담긴 활성 테넌트. null 이면 테넌트 미선택 상태로, select-tenant 를
 *     호출해야 일반 API 를 쓸 수 있다
 * @param memberships 로그인 응답에서 선택 가능한 테넌트 목록(그 외 경로에서는 빈 리스트)
 */
public record TokenResponse(
    String accessToken,
    String refreshToken,
    String tokenType,
    long expiresIn,
    Long activeTenantId,
    List<MembershipResponse> memberships) {

  /** 테넌트 정보가 필요 없는 기존 호출처를 위한 보조 생성자. */
  public TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresIn) {
    this(accessToken, refreshToken, tokenType, expiresIn, null, List.of());
  }
}
