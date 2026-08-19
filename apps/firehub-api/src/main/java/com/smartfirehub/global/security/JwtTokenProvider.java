package com.smartfirehub.global.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenProvider {

  private final SecretKey key;
  private final long accessExpiration;
  private final long refreshExpiration;

  public JwtTokenProvider(JwtProperties jwtProperties) {
    byte[] keyBytes = Base64.getDecoder().decode(jwtProperties.secret());
    if (keyBytes.length < 32) {
      throw new IllegalStateException("JWT secret must be at least 256 bits (32 bytes)");
    }
    this.key = Keys.hmacShaKeyFor(keyBytes);
    this.accessExpiration = jwtProperties.accessExpiration();
    this.refreshExpiration = jwtProperties.refreshExpiration();
  }

  /**
   * 액세스 토큰 발급. tenantId 가 null 이면 테넌트 미선택 토큰이 되어 select-tenant 외의 API 에서
   * RLS 가 모든 행을 차단한다(fail-closed).
   */
  public String generateAccessToken(Long userId, String username, Long tenantId) {
    Date now = new Date();
    var builder =
        Jwts.builder()
            .subject(userId.toString())
            .claim("username", username)
            .claim("type", "access")
            .issuedAt(now)
            .expiration(new Date(now.getTime() + accessExpiration));
    if (tenantId != null) {
      builder.claim("tenant", tenantId);
    }
    return builder.signWith(key).compact();
  }

  /** 리프레시 토큰 발급. 갱신 시 활성 테넌트를 유지하기 위해 tenant 클레임을 함께 싣는다. */
  public String generateRefreshToken(Long userId, Long tenantId) {
    Date now = new Date();
    var builder =
        Jwts.builder()
            .subject(userId.toString())
            .claim("type", "refresh")
            .issuedAt(now)
            .expiration(new Date(now.getTime() + refreshExpiration));
    if (tenantId != null) {
      builder.claim("tenant", tenantId);
    }
    return builder.signWith(key).compact();
  }

  /**
   * 플랫폼(운영자) 액세스 토큰. {@code platform: true} 를 싣고 {@code tenant} 클레임은 <b>싣지
   * 않는다</b>.
   *
   * <p>왜 tenant 를 비우는가: 운영자 평면은 전역 테이블만 만진다. 클레임이 실리면 필터가
   * TenantContext 를 세워 운영자 요청이 특정 테넌트의 RLS 안에서 돌게 되고, 그 테넌트의 도메인
   * 데이터가 운영자에게 열린다 — 설계서 §4 가 크로스테넌트 도메인 조회를 제공하지 않기로 한 결정과
   * 어긋난다. 비어 있으면 GUC 미설정 → RLS 전면 차단(fail-closed)이다.
   */
  public String generatePlatformAccessToken(Long userId, String username) {
    Date now = new Date();
    return Jwts.builder()
        .subject(userId.toString())
        .claim("username", username)
        .claim("type", "access")
        .claim("platform", true)
        .issuedAt(now)
        .expiration(new Date(now.getTime() + accessExpiration))
        .signWith(key)
        .compact();
  }

  /** 플랫폼 리프레시 토큰. 마찬가지로 tenant 클레임이 없다. */
  public String generatePlatformRefreshToken(Long userId) {
    Date now = new Date();
    return Jwts.builder()
        .subject(userId.toString())
        .claim("type", "refresh")
        .claim("platform", true)
        .issuedAt(now)
        .expiration(new Date(now.getTime() + refreshExpiration))
        .signWith(key)
        .compact();
  }

  /**
   * 토큰이 플랫폼 평면인지.
   *
   * <p>리프레시 경로에서 평면을 검사하는 데 쓴다 — 테넌트 리프레시 토큰으로 플랫폼 토큰을 받아
   * 평면을 갈아타는 승격 경로를 막는다.
   */
  public boolean isPlatformToken(String token) {
    try {
      return Boolean.TRUE.equals(parseClaims(token).get("platform", Boolean.class));
    } catch (JwtException | IllegalArgumentException e) {
      return false;
    }
  }

  public Long getUserIdFromToken(String token) {
    String subject = parseClaims(token).getSubject();
    return Long.parseLong(subject);
  }

  /** 토큰의 활성 테넌트. 테넌트 미선택 토큰이면 null. */
  public Long getTenantIdFromToken(String token) {
    Number tenant = parseClaims(token).get("tenant", Number.class);
    return tenant == null ? null : tenant.longValue();
  }

  public boolean validateToken(String token) {
    try {
      parseClaims(token);
      return true;
    } catch (JwtException | IllegalArgumentException e) {
      return false;
    }
  }

  public boolean validateAccessToken(String token) {
    try {
      Claims claims = parseClaims(token);
      return "access".equals(claims.get("type", String.class));
    } catch (JwtException | IllegalArgumentException e) {
      return false;
    }
  }

  public boolean validateRefreshToken(String token) {
    try {
      Claims claims = parseClaims(token);
      return "refresh".equals(claims.get("type", String.class));
    } catch (JwtException | IllegalArgumentException e) {
      return false;
    }
  }

  private Claims parseClaims(String token) {
    return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
  }

  /**
   * 액세스 토큰을 한 번만 파싱해 인증에 필요한 값을 함께 돌려준다.
   *
   * <p>{@code platform} 은 평면 표식이다 — true 면 운영자 평면 토큰이고 {@code tenantId} 는 null 이다.
   *
   * <p><b>2인자 축약 생성자를 일부러 두지 않는다.</b> 평면을 생략할 수 있게 만들면 기본값(false)이
   * 두 곳에 존재하게 되고, 운영자 평면 검증을 쓰려던 호출처가 평면을 빼먹은 채 조용히 테넌트 평면을
   * 받는다. 모든 생성 지점이 평면을 명시하게 해서 그 실수를 컴파일 단계에서 막는다.
   */
  public record AccessTokenPrincipal(Long userId, Long tenantId, boolean platform) {}

  /**
   * 액세스 토큰을 1회 파싱해 userId 와 tenantId 를 함께 추출한다.
   *
   * <p>필터는 요청마다 이 경로를 타므로, validate/getUserId/getTenantId 를 따로 호출하면 같은 토큰의
   * 서명 검증과 JSON 파싱이 3번 반복된다. 인증은 가장 뜨거운 경로라 1회 파싱으로 합친다.
   *
   * @return 유효한 access 토큰이면 값이 담긴 Optional, 아니면 Optional.empty()
   */
  public Optional<AccessTokenPrincipal> parseAccessToken(String token) {
    try {
      Claims claims = parseClaims(token);
      if (!"access".equals(claims.get("type", String.class))) {
        return Optional.empty();
      }
      Long userId = Long.parseLong(claims.getSubject());
      Number tenant = claims.get("tenant", Number.class);
      Long tenantId = tenant == null ? null : tenant.longValue();
      // 클레임이 없거나 true 가 아니면 테넌트 평면이다 — 기본값을 false 로 둬야 평면 가드가
      // 알 수 없는 토큰 형태에 대해 fail-closed 한다.
      boolean platform = Boolean.TRUE.equals(claims.get("platform", Boolean.class));
      return Optional.of(new AccessTokenPrincipal(userId, tenantId, platform));
    } catch (JwtException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
