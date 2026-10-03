package com.smartfirehub.auth.dto;

import com.smartfirehub.tenant.dto.MembershipResponse;
import java.util.List;

/**
 * 토큰 응답.
 *
 * @param activeTenantId 이 토큰에 담긴 활성 테넌트. null 이면 테넌트 미선택 상태로, select-tenant 를 호출해야 일반 API 를 쓸 수 있다
 * @param memberships 로그인 응답에서 선택 가능한 테넌트 목록(그 외 경로에서는 빈 리스트)
 * @param mustChangePassword 비밀번호 변경이 강제된 계정이면 true(WD-2). 웹은 이 값으로 변경 화면에 보낸다. DB 값을 토큰 발급 시점에 읽은
 *     것이며 access token 의 pwc 클레임과 같다
 */
public record TokenResponse(
    String accessToken,
    String refreshToken,
    String tokenType,
    long expiresIn,
    Long activeTenantId,
    List<MembershipResponse> memberships,
    boolean mustChangePassword) {

  /** 테넌트 정보가 필요 없는 기존 호출처를 위한 보조 생성자. */
  public TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresIn) {
    this(accessToken, refreshToken, tokenType, expiresIn, null, List.of(), false);
  }

  /** 비밀번호 강제 표식이 없는 기존 6인자 호출처(테스트)용. */
  public TokenResponse(
      String accessToken,
      String refreshToken,
      String tokenType,
      long expiresIn,
      Long activeTenantId,
      List<MembershipResponse> memberships) {
    this(accessToken, refreshToken, tokenType, expiresIn, activeTenantId, memberships, false);
  }

  /**
   * 응답 본문용 사본 — refresh token 은 HttpOnly 쿠키로만 보내므로 본문에서 지운다. 컨트롤러가 필드를 하나씩 다시 조립하면 새 필드(예:
   * mustChangePassword)가 조용히 빠진다 — 그래서 여기 한 곳에서 만든다.
   */
  public TokenResponse withoutRefreshToken() {
    return new TokenResponse(
        accessToken, null, tokenType, expiresIn, activeTenantId, memberships, mustChangePassword);
  }
}
