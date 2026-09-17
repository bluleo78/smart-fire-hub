package com.smartfirehub.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 리프레시 토큰이 <b>같은 초 안에 두 번 발급돼도</b> 서로 다른 값인지 검증한다.
 *
 * <p>왜 이 테스트가 필요한가: JWT 의 {@code iat}/{@code exp} 는 규격상 <b>초 단위</b>이고 서명은 HMAC 대칭키라 결정적이다. 그래서 고유
 * 클레임이 없으면 같은 사용자·같은 테넌트에 대해 같은 1초 안에 발급한 토큰이 <b>바이트까지 동일</b>해진다. {@code refresh_token.token_hash}
 * 에는 UNIQUE 제약이 있어 두 번째 저장이 409(DataIntegrityViolation)로 실패한다.
 *
 * <p>이건 이론이 아니라 운영에서 터진 경로다(2026-09-16): {@code select-tenant} 가 토큰을 발급하고 곧바로 하드 리로드가 일어나면서 부팅 시 세션
 * 복원 {@code refresh} 가 <b>같은 초에</b> 또 발급해 로그인이 100% 실패했다. 발급 간격은 우리가 통제할 수 없으므로(네트워크·캐시 상태에 좌우된다)
 * 토큰 자체가 매번 달라야 한다.
 *
 * <p>DB 를 띄우지 않는 순수 단위 테스트다 — 이 결함은 토큰 생성기 단독으로 재현된다.
 */
class RefreshTokenUniquenessTest {

  /** HS256 최소 길이(32바이트)를 채운 테스트용 키. 운영 비밀과 무관하다. */
  private static final String TEST_SECRET =
      Base64.getEncoder().encodeToString("test-secret-key-for-unit-test-32b".getBytes());

  private final JwtTokenProvider jwtTokenProvider =
      new JwtTokenProvider(new JwtProperties(TEST_SECRET, 900_000L, 604_800_000L));

  @Test
  @DisplayName("같은 초에 두 번 발급한 테넌트 리프레시 토큰은 서로 다르다")
  void tenantRefreshTokensIssuedInSameSecondDiffer() {
    // 연속 호출 — 사실상 같은 밀리초, 당연히 같은 초다.
    String first = jwtTokenProvider.generateRefreshToken(42L, 7L);
    String second = jwtTokenProvider.generateRefreshToken(42L, 7L);

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  @DisplayName("같은 초에 두 번 발급한 플랫폼 리프레시 토큰은 서로 다르다")
  void platformRefreshTokensIssuedInSameSecondDiffer() {
    // 운영자 평면도 같은 refresh_token 테이블·같은 UNIQUE 제약을 쓴다 — 결함이 대칭이다.
    String first = jwtTokenProvider.generatePlatformRefreshToken(42L);
    String second = jwtTokenProvider.generatePlatformRefreshToken(42L);

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  @DisplayName("고유화 후에도 기존 클레임(sub/tenant/평면 표식)은 그대로 읽힌다")
  void uniquenessDoesNotBreakExistingClaims() {
    String tenantToken = jwtTokenProvider.generateRefreshToken(42L, 7L);
    assertThat(jwtTokenProvider.getUserIdFromToken(tenantToken)).isEqualTo(42L);
    assertThat(jwtTokenProvider.getTenantIdFromToken(tenantToken)).isEqualTo(7L);
    assertThat(jwtTokenProvider.isPlatformToken(tenantToken)).isFalse();

    String platformToken = jwtTokenProvider.generatePlatformRefreshToken(42L);
    assertThat(jwtTokenProvider.getUserIdFromToken(platformToken)).isEqualTo(42L);
    assertThat(jwtTokenProvider.isPlatformToken(platformToken)).isTrue();
  }
}
