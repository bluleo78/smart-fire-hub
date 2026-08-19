package com.smartfirehub.auth.service;

import com.smartfirehub.auth.exception.InvalidTokenException;
import com.smartfirehub.auth.repository.RefreshTokenRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 리프레시 토큰 회전 + 재사용 탐지. <b>두 평면이 공유하는 정책</b>이다.
 *
 * <p>{@code refresh_token} 은 전역 테이블이라 테넌트 평면({@code AuthService})과 운영자 평면
 * ({@code PlatformAuthService})이 같은 회전 기계를 쓴다. 그런데 이 18줄이 두 서비스에 복사돼
 * 있었고 이미 조금씩 어긋나기 시작했다 — 회전 정책이 바뀔 때 한쪽만 고치면 <b>한 평면에서만</b>
 * 탈취 탐지가 느슨해지는데, 그것은 컴파일도 테스트도 알려주지 않는 종류의 어긋남이다. 해시를
 * {@link RefreshTokenHasher} 로 통일한 것과 같은 이유로 회전도 한 곳에 둔다.
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenRotation {

  private final RefreshTokenRepository refreshTokenRepository;

  /**
   * 제시된 토큰을 폐기하고 그 패밀리 id 를 돌려준다. 새 토큰은 호출자가 같은 패밀리로 발급한다.
   *
   * <p>이미 폐기된 토큰이 다시 오면 탈취를 의심해 <b>패밀리 전체</b>를 폐기한다. 회전 체인이
   * 로그인마다 새로 시작되므로 패밀리 = 하나의 로그인 세션이다.
   *
   * @param tokenHash {@link RefreshTokenHasher} 로 해시한 값
   * @return 이 토큰이 속한 패밀리 id
   * @throws InvalidTokenException 재사용·폐기·미존재 — 어느 경우든 다시 로그인해야 한다
   */
  public UUID revokeAndGetFamily(String tokenHash) {
    if (refreshTokenRepository.isTokenRevoked(tokenHash)) {
      refreshTokenRepository
          .findFamilyIdByTokenHash(tokenHash)
          .ifPresent(refreshTokenRepository::revokeByFamilyId);
      throw new InvalidTokenException("이미 사용된 토큰입니다. 다시 로그인해 주세요.");
    }
    if (!refreshTokenRepository.existsValidToken(tokenHash)) {
      throw new InvalidTokenException("만료되었거나 폐기된 토큰입니다. 다시 로그인해 주세요.");
    }

    UUID familyId =
        refreshTokenRepository
            .findFamilyIdByTokenHash(tokenHash)
            .orElseThrow(() -> new InvalidTokenException("토큰 정보를 찾을 수 없습니다. 다시 로그인해 주세요."));
    refreshTokenRepository.revokeByTokenHash(tokenHash);
    return familyId;
  }
}
