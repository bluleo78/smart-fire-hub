package com.smartfirehub.platform.dto;

import java.util.List;

/**
 * 운영자 평면 토큰 응답.
 *
 * <p>테넌트 평면의 {@code TokenResponse} 와 별도 타입인 이유: 운영자 응답에는 {@code activeTenantId}
 * 와 {@code memberships} 가 존재할 수 없고(플랫폼 토큰은 테넌트를 갖지 않는다), 대신 화면 구성을 위해
 * 보유 권한이 필요하다. 같은 타입을 재사용하면 항상 null 인 필드 두 개를 운영자 클라이언트에
 * 노출하게 된다.
 *
 * @param refreshToken 컨트롤러가 쿠키로 옮기고 본문에서는 null 로 비운다
 * @param permissions 이 운영자가 보유한 {@code platform:*} 권한 코드
 */
public record PlatformTokenResponse(
    String accessToken,
    String refreshToken,
    String tokenType,
    long expiresIn,
    List<String> permissions) {}
