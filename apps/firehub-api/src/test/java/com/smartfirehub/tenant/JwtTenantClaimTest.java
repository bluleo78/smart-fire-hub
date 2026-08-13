package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 액세스/리프레시 토큰이 활성 테넌트를 클레임으로 실어 나르는지 검증한다. */
class JwtTenantClaimTest extends IntegrationTestBase {

  @Autowired private JwtTokenProvider jwtTokenProvider;

  @Test
  @DisplayName("액세스 토큰의 tenant 클레임을 다시 읽을 수 있다")
  void accessTokenCarriesTenantClaim() {
    String token = jwtTokenProvider.generateAccessToken(42L, "tester", 7L);

    assertThat(jwtTokenProvider.getTenantIdFromToken(token)).isEqualTo(7L);
    assertThat(jwtTokenProvider.getUserIdFromToken(token)).isEqualTo(42L);
  }

  @Test
  @DisplayName("테넌트 미선택 토큰은 tenant 클레임이 없어 null 을 반환한다")
  void tenantlessTokenReturnsNull() {
    String token = jwtTokenProvider.generateAccessToken(42L, "tester", null);

    assertThat(jwtTokenProvider.getTenantIdFromToken(token)).isNull();
  }

  @Test
  @DisplayName("리프레시 토큰도 tenant 클레임을 유지한다")
  void refreshTokenCarriesTenantClaim() {
    String token = jwtTokenProvider.generateRefreshToken(42L, 9L);

    assertThat(jwtTokenProvider.getTenantIdFromToken(token)).isEqualTo(9L);
  }
}
