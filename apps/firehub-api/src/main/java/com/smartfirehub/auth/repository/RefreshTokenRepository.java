package com.smartfirehub.auth.repository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

  /**
   * {@code revoked_at} 에 적을(그리고 회전 유예를 잴) 시각 — **UTC 벽시계**로 고정한다 (WD-1141 반려 3차 5).
   *
   * <p>왜 JVM 기본 시간대({@code LocalDateTime.now()})를 쓰지 않는가: 폐기는 한 노드의 JVM 시계로 찍히고 유예 판정은 다른 노드의 JVM 시계로
   * 잰다. 두 노드의 기본 시간대가 어긋나면 5초 유예가 몇 시간씩 벌어져, 유예 밖의 재사용(탈취)까지 관용하거나 그 반대가 된다. 이 열을 쓰고 읽는
   * 자리가 **여기 하나**로 모여 있으니 기준을 한 곳에 고정한다.
   *
   * <p>왜 DB 시각(CURRENT_TIMESTAMP) 대신 UTC 인스턴트인가: 폐기 경로는 구현마다 넷(jOOQ 4 · MyBatis 4 — 회전·패밀리·웹 평면·전체 폐기)이고
   * 전부 애플리케이션에서 값을 만들어 바인딩하며, DB 는 이 열을 스스로 쓰지 않는다. 값을 만드는 쪽을 한 곳으로 모으는 편이 문장별
   * {@code CURRENT_TIMESTAMP} 를 넣는 것보다 적다. UTC 로 두면 배포 환경의 시간대·서머타임에도 값이 그대로이고, JDBC 는 {@code LocalDateTime} 를 벽시계 그대로 왕복한다
   * ({@code RefreshTokenRepositoryContractTest#expiresAt_roundTripsAsWallClock}).
   *
   * <p>**이 값을 쓰는 자리와 유예를 잴 자리({@code RefreshTokenRotation#withinGrace})는 항상 이것을 써야 한다** — 한쪽만 바꾸면 유예
   * 창 전체가 그만큼 어긋난다. 테스트가 유예 안/밖을 재현할 때도 이 값에서 빼야 한다.
   *
   * @return UTC 벽시계 기준의 지금
   */
  static LocalDateTime revokedAtNow() {
    return LocalDateTime.now(ZoneOffset.UTC);
  }

  /** 웹 평면 토큰을 저장한다. */
  void save(Long userId, String tokenHash, LocalDateTime expiresAt, UUID familyId);

  /** 지정한 평면({@link #PLANE_WEB}/{@link #PLANE_PLATFORM})의 토큰을 저장한다. */
  void save(Long userId, String tokenHash, LocalDateTime expiresAt, UUID familyId, String plane);

  boolean existsValidToken(String tokenHash);

  boolean isTokenRevoked(String tokenHash);

  /**
   * 이미 폐기된 토큰의 폐기 시각을 돌려준다 (WD-1141 — 회전 유예).
   *
   * <p>회전 직후 짧은 시간 안에 재제시된 토큰이 "동시 갱신의 진 쪽"인지 "오래된 재사용(탈취)"인지 가르는 근거다. 아직 폐기되지 않았거나, 폐기 시각이 없는
   * 행({@code revoked_at} 컬럼 이전에 폐기된 행)이면 비어 있다 — 비어 있으면 유예를 적용하지 않는다.
   *
   * <p>이 값의 기준 시계는 {@link #revokedAtNow()} 와 같다.
   *
   * @return 폐기 시각. 유예 판정에만 쓴다.
   */
  Optional<LocalDateTime> findRevokedAt(String tokenHash);

  Optional<UUID> findFamilyIdByTokenHash(String tokenHash);

  /**
   * 토큰 행의 소유 정보(패밀리·사용자·평면) — 이 브라우저 세션 로그아웃(WD-1176)이 쓴다.
   *
   * <p>JWT 클레임 대신 행에서 읽는 이유: 만료된 토큰은 JWT 파싱이 실패하지만 행은 남아 있고, 평면도 행 값이 진실이다.
   */
  Optional<TokenOwner> findOwnerByTokenHash(String tokenHash);

  /** {@link #findOwnerByTokenHash} 결과 — 폐기 여부·만료와 무관하게 행 그대로다. */
  record TokenOwner(UUID familyId, Long userId, String plane) {}

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
