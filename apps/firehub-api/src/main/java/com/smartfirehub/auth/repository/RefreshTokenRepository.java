package com.smartfirehub.auth.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 리프레시 토큰(refresh_token 테이블) 저장·조회·폐기 리포지토리.
 *
 * <p>구현은 {@code JooqRefreshTokenRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface RefreshTokenRepository {

  /**
   * 웹(워크스페이스) 평면. {@code refresh_token.plane}(V154, WD-314) 기본값과 같다 — 웹 로그아웃이 같은 사람의 운영자 콘솔 세션을 끊지
   * 않게 평면별로 폐기하는 데 쓴다.
   */
  String PLANE_WEB = "WEB";

  /** 운영자 콘솔 평면. */
  String PLANE_PLATFORM = "PLATFORM";

  /** 웹 평면 토큰을 저장한다. */
  void save(Long userId, String tokenHash, LocalDateTime expiresAt, UUID familyId);

  /** 지정한 평면({@link #PLANE_WEB}/{@link #PLANE_PLATFORM})의 토큰을 저장한다. */
  void save(Long userId, String tokenHash, LocalDateTime expiresAt, UUID familyId, String plane);

  boolean existsValidToken(String tokenHash);

  boolean isTokenRevoked(String tokenHash);

  Optional<UUID> findFamilyIdByTokenHash(String tokenHash);

  /**
   * 아직 살아 있는 토큰 하나를 폐기한다.
   *
   * @return 이번 호출이 폐기했으면 true. 같은 토큰으로 동시에 들어온 다른 갱신이 먼저 폐기했다면(행 잠금을 기다린 뒤 {@code revoked=true} 를
   *     보게 된다) false — 회전은 한 번만 성공해야 하므로 호출자는 이 갱신을 거부한다(WD-268).
   */
  boolean revokeByTokenHash(String tokenHash);

  void revokeByFamilyId(UUID familyId);

  /**
   * 사용자의 웹 평면 토큰만 모두 폐기한다(웹 로그아웃, WD-314). 같은 사람의 운영자 콘솔 세션(PLATFORM)은 남긴다 — 운영자 콘솔 로그아웃이 웹 세션을
   * 건드리지 않는 것과 같은 원칙이다.
   */
  void revokeAllWebByUserId(Long userId);

  /** 사용자의 모든 평면 토큰을 폐기한다(계정 비활성화·비밀번호 변경처럼 모든 세션을 끊어야 할 때). */
  void revokeAllByUserId(Long userId);

  int deleteExpiredTokens();
}
