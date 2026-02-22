package com.smartfirehub.global.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.util.Base64;
import java.util.Date;
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

  public String generateAccessToken(Long userId, String username) {
    Date now = new Date();
    return Jwts.builder()
        .subject(userId.toString())
        .claim("username", username)
        .claim("type", "access")
        .issuedAt(now)
        .expiration(new Date(now.getTime() + accessExpiration))
        .signWith(key)
        .compact();
  }

  public String generateRefreshToken(Long userId) {
    Date now = new Date();
    return Jwts.builder()
        .subject(userId.toString())
        .claim("type", "refresh")
        .issuedAt(now)
        .expiration(new Date(now.getTime() + refreshExpiration))
        .signWith(key)
        .compact();
  }

  public Long getUserIdFromToken(String token) {
    String subject = parseClaims(token).getSubject();
    return Long.parseLong(subject);
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
}
