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

  /** 액세스 토큰을 한 번만 파싱해 인증에 필요한 값을 함께 돌려준다. */
  public record AccessTokenPrincipal(Long userId, Long tenantId) {}

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
      return Optional.of(new AccessTokenPrincipal(userId, tenantId));
    } catch (JwtException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
